from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging
import re
from time import monotonic

from .config import AppConfig
from .layout import Rect, fit_inside, grid_layout, team_showcase_layout
from .mediamtx import StreamRegistry
from .models import Player, Team
from .obs import ObsController, transform_from_fit

LOGGER = logging.getLogger(__name__)


STREAM_WIDTH = 1920
STREAM_HEIGHT = 1080


@dataclass(frozen=True)
class PlayerSources:
    media_input: str
    label_input: str
    note_input: str
    placeholder_input: str
    player_scene: str


@dataclass
class TeamRotation:
    player_ids: list[int]
    next_index: int = 0
    last_rotation: float = 0.0
    showcased_player_id: int | None = None


class ObsLayoutManager:
    def __init__(
        self,
        obs: ObsController,
        config: AppConfig,
        players: list[Player],
        teams: list[Team],
        streams: StreamRegistry,
    ) -> None:
        self.obs = obs
        self.config = config
        self.players = sorted(players, key=lambda player: player.name.lower())
        self.players_by_id = {player.id: player for player in players}
        self.teams_by_id = {team.id: team for team in teams}
        self.streams = streams
        self.sources = {player.id: self._sources_for(player) for player in players}
        self.all_scene = f"{config.obs.managed_scene_prefix} - All Streamers"
        self.team_scenes = {
            team.id: f"{config.obs.managed_scene_prefix} - Team - {team.name}"
            for team in teams
        }
        self.team_rotations = {
            team_id: TeamRotation([player.id for player in self.players if player.team_id == team_id])
            for team_id in self.team_scenes
        }
        self._all_audio_next_index = 0
        self._all_audio_last_rotation = 0.0
        self._all_audio_player_id: int | None = None
        self._closed = False

    def player_scene_name(self, player_id: int) -> str | None:
        sources = self.sources.get(player_id)
        return sources.player_scene if sources else None

    def is_player_active(self, player_id: int) -> bool:
        return self.streams.is_player_active(player_id)

    def player_scene_names(self) -> tuple[str, ...]:
        return tuple(source.player_scene for source in self.sources.values())

    def generated_scene_names(self) -> tuple[str, ...]:
        return (self.all_scene, *self.team_scenes.values(), *self.player_scene_names())

    async def setup(self) -> None:
        for scene in self.generated_scene_names():
            await self.obs.ensure_scene(scene)
        for player in self.players:
            await self._ensure_player_inputs(player)
            await self._setup_player_scene(player)
        await self.reconcile()

    async def prepare_cut_to_player(self, player_id: int, message: str | None = None) -> None:
        player = self.players_by_id.get(player_id)
        if not player:
            return
        await self._setup_player_scene(player, placeholder_message=message)

    async def run(self) -> None:
        while not self._closed:
            try:
                changed = await self.streams.refresh()
                if changed:
                    await self.reconcile()
            except asyncio.CancelledError:
                raise
            except Exception:
                LOGGER.exception("Unable to refresh MediaMTX stream state")
            await asyncio.sleep(self.config.mediamtx.poll_seconds)

    def close(self) -> None:
        self._closed = True

    async def reconcile(self) -> None:
        await self._layout_all_streamers()
        await self._layout_team_scenes()
        for player in self.players:
            await self._setup_player_scene(player)

    async def tick_team_showcases(self, *, force: bool = False) -> bool:
        if not self.obs.connected:
            return False
        current_scene = await self.obs.get_current_program_scene()
        team_id = next((team_id for team_id, scene in self.team_scenes.items() if scene == current_scene), None)
        if team_id is None:
            return False
        state = self.team_rotations[team_id]
        active = [player_id for player_id in state.player_ids if self.streams.is_player_active(player_id)]
        if not active:
            return False
        now = monotonic()
        due = force or state.showcased_player_id not in active or now - state.last_rotation >= self.config.timing.team_showcase_seconds
        if not due:
            return False
        next_player = active[state.next_index % len(active)]
        state.next_index = (state.next_index + 1) % len(active)
        state.showcased_player_id = next_player
        state.last_rotation = now
        await self._layout_team_scene(team_id, showcased_player_id=next_player)
        return True

    async def tick_all_streamers_audio(self, *, force: bool = False) -> bool:
        if not self.obs.connected:
            return False
        if await self.obs.get_current_program_scene() != self.all_scene:
            return False
        active = [player.id for player in self.players if self.streams.is_player_active(player.id)]
        if not active:
            return False
        now = monotonic()
        due = force or self._all_audio_player_id not in active or now - self._all_audio_last_rotation >= self.config.timing.all_streamers_audio_seconds
        if not due:
            return False
        player_id = active[self._all_audio_next_index % len(active)]
        self._all_audio_next_index = (self._all_audio_next_index + 1) % len(active)
        self._all_audio_player_id = player_id
        self._all_audio_last_rotation = now
        await self._activate_all_audio(player_id)
        return True

    async def _activate_all_audio(self, active_player_id: int) -> None:
        for player in self.players:
            sources = self.sources[player.id]
            active = player.id == active_player_id
            await self.obs.set_input_mute(sources.media_input, not active)
            note_id = await self.obs.ensure_scene_item(self.all_scene, sources.note_input, enabled=active)
            if active:
                LOGGER.info("Activating all-streamers audio source %s", player.name)

    async def _ensure_player_inputs(self, player: Player) -> None:
        sources = self.sources[player.id]
        rtsp_url = self.streams.rtsp_url_for_player(player.id) or "rtsp://127.0.0.1:8554/missing"
        await self.obs.ensure_input(
            sources.player_scene,
            sources.media_input,
            self.config.obs.media_source_kind,
            {
                "input": rtsp_url,
                "is_local_file": False,
                "restart_on_activate": False,
                "close_when_inactive": True,
                "hw_decode": True,
            },
            enabled=False,
        )
        await self.obs.ensure_input(
            sources.player_scene,
            sources.label_input,
            self.config.obs.text_source_kind,
            {"text": player.name},
            enabled=True,
        )
        await self.obs.ensure_input(
            sources.player_scene,
            sources.note_input,
            self.config.obs.text_source_kind,
            {"text": "♪"},
            enabled=False,
        )
        await self.obs.ensure_input(
            sources.player_scene,
            sources.placeholder_input,
            self.config.obs.text_source_kind,
            {"text": f"{player.name}\nStream offline"},
            enabled=True,
        )

    async def _setup_player_scene(self, player: Player, placeholder_message: str | None = None) -> None:
        sources = self.sources[player.id]
        scene = sources.player_scene
        active = self.streams.is_player_active(player.id)
        media_id = await self.obs.ensure_scene_item(scene, sources.media_input, enabled=active)
        placeholder_id = await self.obs.ensure_scene_item(scene, sources.placeholder_input, enabled=not active)
        label_id = await self.obs.ensure_scene_item(scene, sources.label_input, enabled=True)
        if placeholder_message is not None:
            await self.obs.set_input_settings(
                sources.placeholder_input,
                {"text": f"{player.name}\n{placeholder_message}"},
            )
        await self.obs.set_scene_item_transform(
            scene,
            media_id,
            transform_from_fit(fit_inside(STREAM_WIDTH, STREAM_HEIGHT, Rect(0, 0, self.config.overlay.canvas_width, self.config.overlay.canvas_height))),
        )
        await self.obs.set_scene_item_transform(
            scene,
            placeholder_id,
            transform_from_fit(Rect(0, self.config.overlay.canvas_height * 0.38, self.config.overlay.canvas_width, 180)),
        )
        await self.obs.set_scene_item_transform(scene, label_id, transform_from_fit(Rect(32, 28, 700, 70)))

    async def _layout_all_streamers(self) -> None:
        active_players = [player for player in self.players if self.streams.is_player_active(player.id)]
        rects = grid_layout(len(active_players), self.config.overlay.canvas_width, self.config.overlay.canvas_height)
        active_ids = {player.id for player in active_players}
        for player in self.players:
            sources = self.sources[player.id]
            enabled = player.id in active_ids
            media_id = await self.obs.ensure_scene_item(self.all_scene, sources.media_input, enabled=enabled)
            label_id = await self.obs.ensure_scene_item(self.all_scene, sources.label_input, enabled=enabled)
            note_id = await self.obs.ensure_scene_item(self.all_scene, sources.note_input, enabled=False)
            if enabled:
                rect = rects[active_players.index(player)]
                media_fit = fit_inside(STREAM_WIDTH, STREAM_HEIGHT, rect)
                await self.obs.set_scene_item_transform(self.all_scene, media_id, transform_from_fit(media_fit))
                await self.obs.set_scene_item_transform(self.all_scene, label_id, transform_from_fit(Rect(rect.x + 10, rect.y + 10, rect.width - 20, 44)))
                await self.obs.set_scene_item_transform(self.all_scene, note_id, transform_from_fit(Rect(rect.x + rect.width - 54, rect.y + 10, 44, 44)))

    async def _layout_team_scenes(self) -> None:
        for team_id in self.team_scenes:
            await self._layout_team_scene(team_id)

    async def _layout_team_scene(self, team_id: int, showcased_player_id: int | None = None) -> None:
        scene = self.team_scenes[team_id]
        team_player_ids = self.team_rotations[team_id].player_ids
        active_ids = [player_id for player_id in team_player_ids if self.streams.is_player_active(player_id)]
        if showcased_player_id is None:
            showcased_player_id = self.team_rotations[team_id].showcased_player_id if self.team_rotations[team_id].showcased_player_id in active_ids else None
        if showcased_player_id is None and active_ids:
            showcased_player_id = active_ids[0]
            self.team_rotations[team_id].showcased_player_id = showcased_player_id
        ordered_ids = ([showcased_player_id] if showcased_player_id else []) + [player_id for player_id in active_ids if player_id != showcased_player_id]
        rects = team_showcase_layout(len(ordered_ids), self.config.overlay.canvas_width, self.config.overlay.canvas_height)

        for player_id in team_player_ids:
            player = self.players_by_id[player_id]
            sources = self.sources[player.id]
            enabled = player.id in ordered_ids
            media_id = await self.obs.ensure_scene_item(scene, sources.media_input, enabled=enabled)
            label_id = await self.obs.ensure_scene_item(scene, sources.label_input, enabled=enabled)
            note_id = await self.obs.ensure_scene_item(scene, sources.note_input, enabled=enabled and player.id == showcased_player_id)
            await self.obs.set_input_mute(sources.media_input, player.id != showcased_player_id)
            if enabled:
                rect = rects[ordered_ids.index(player.id)]
                media_fit = fit_inside(STREAM_WIDTH, STREAM_HEIGHT, rect)
                await self.obs.set_scene_item_transform(scene, media_id, transform_from_fit(media_fit))
                await self.obs.set_scene_item_transform(scene, label_id, transform_from_fit(Rect(rect.x + 10, rect.y + 10, rect.width - 20, 44)))
                await self.obs.set_scene_item_transform(scene, note_id, transform_from_fit(Rect(rect.x + rect.width - 54, rect.y + 10, 44, 44)))

    def _sources_for(self, player: Player) -> PlayerSources:
        slug = _slug(player.name or str(player.id))
        prefix = self.config.obs.managed_scene_prefix
        return PlayerSources(
            media_input=f"{prefix} Media - {slug}",
            label_input=f"{prefix} Label - {slug}",
            note_input=f"{prefix} Audio Note - {slug}",
            placeholder_input=f"{prefix} Placeholder - {slug}",
            player_scene=f"{prefix} - Player - {player.name}",
        )


def _slug(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._ -]+", "", value).strip()
    return cleaned or "unknown"
