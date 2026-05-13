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
from .obs import ObsController, ObsError, positioned_transform, transform_from_fit

LOGGER = logging.getLogger(__name__)


STREAM_WIDTH = 1920
STREAM_HEIGHT = 1080
LABEL_FONT_SIZE = 26
NOTE_FONT_SIZE = 30
TEAM_SCENE_LABEL_FONT_SIZE = 34
OFFLINE_MESSAGE_FONT_SIZE = 42
ALL_STREAMERS_OFFLINE_MESSAGE = "All players offline. Stay tuned for more live coverage of FM Team Hundo!"


def _text_settings(text: str, *, size: int = LABEL_FONT_SIZE) -> dict:
    return {
        "text": text,
        "font": {
            "face": "Segoe UI",
            "style": "Bold",
            "size": size,
        },
    }


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
        self.all_offline_input = f"{config.obs.managed_scene_prefix} Offline Message - All Streamers"
        self.team_offline_inputs = {
            team.id: f"{config.obs.managed_scene_prefix} Offline Message - Team - {team.name}"
            for team in teams
        }
        self.team_label_inputs = {
            team.id: f"{config.obs.managed_scene_prefix} Team Label - {team.name}"
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
        self._validate_master_scene_names()

    def player_scene_name(self, player_id: int) -> str | None:
        sources = self.sources.get(player_id)
        return sources.player_scene if sources else None

    def is_player_active(self, player_id: int) -> bool:
        return self.streams.is_player_active(player_id)

    def player_scene_names(self) -> tuple[str, ...]:
        return tuple(source.player_scene for source in self.sources.values())

    def generated_scene_names(self) -> tuple[str, ...]:
        return (self.all_scene, *self.team_scenes.values(), *self.player_scene_names())

    async def update_roster(self, players: list[Player], teams: list[Team]) -> None:
        next_player_ids = {player.id for player in players}
        for player_id in set(self.players_by_id) - next_player_ids:
            await self._retire_player(player_id)
        self.players = sorted(players, key=lambda player: player.name.lower())
        self.players_by_id = {player.id: player for player in players}
        self.teams_by_id = {team.id: team for team in teams}
        for player in players:
            self.sources.setdefault(player.id, self._sources_for(player))
        self.team_scenes = {
            team.id: f"{self.config.obs.managed_scene_prefix} - Team - {team.name}"
            for team in teams
        }
        self.team_offline_inputs = {
            team.id: f"{self.config.obs.managed_scene_prefix} Offline Message - Team - {team.name}"
            for team in teams
        }
        self.team_label_inputs = {
            team.id: f"{self.config.obs.managed_scene_prefix} Team Label - {team.name}"
            for team in teams
        }
        self.team_rotations = {
            team_id: self._updated_team_rotation(team_id)
            for team_id in self.team_scenes
        }
        await self.setup()

    async def setup(self) -> None:
        await self._ensure_master_scenes()
        for scene in self.generated_scene_names():
            await self.obs.ensure_scene(scene)
            if scene != self.config.obs.overlay_scene:
                await self._ensure_master_scenes_for_scene(scene)
                await self._ensure_overlay_on_top(scene)
        await self._ensure_scene_offline_inputs()
        for player in self.players:
            await self._ensure_player_inputs(player)
            await self._setup_player_scene(player)
        await self.reconcile()

    async def prepare_cut_to_player(self, player_id: int, message: str | None = None) -> None:
        player = self.players_by_id.get(player_id)
        if not player:
            return
        await self._setup_player_scene(player, placeholder_message=message)

    async def focus_player_for_alert(self, player_id: int) -> None:
        if player_id not in self.sources:
            return
        await self._set_only_player_audio(player_id)

    async def focus_player_for_scene(self, player_id: int, scene_name: str) -> None:
        if player_id not in self.sources or not self.streams.is_player_active(player_id):
            return
        if scene_name == self.all_scene:
            await self._focus_all_streamers_audio(player_id)
            return
        player = self.players_by_id.get(player_id)
        if player is None or player.team_id is None:
            return
        team_scene = self.team_scenes.get(player.team_id)
        if scene_name == team_scene:
            await self._focus_team_showcase(player.team_id, player_id)

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
        await self._ensure_overlay_top_for_generated_scenes()

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
        await self._set_only_player_audio(active_player_id)
        for player in self.players:
            sources = self.sources[player.id]
            active = player.id == active_player_id
            note_id = await self.obs.ensure_scene_item(self.all_scene, sources.note_input, enabled=active)
            if active:
                LOGGER.info("Activating all-streamers audio source %s", player.name)
        await self._ensure_overlay_on_top(self.all_scene)

    async def _set_only_player_audio(self, active_player_id: int) -> None:
        for player in self.players:
            sources = self.sources[player.id]
            active = player.id == active_player_id
            await self.obs.set_input_mute(sources.media_input, not active)

    async def _focus_all_streamers_audio(self, player_id: int) -> None:
        active = [player.id for player in self.players if self.streams.is_player_active(player.id)]
        if player_id not in active:
            return
        await self._activate_all_audio(player_id)
        self._all_audio_player_id = player_id
        self._all_audio_last_rotation = monotonic()
        self._all_audio_next_index = (active.index(player_id) + 1) % len(active)

    async def _focus_team_showcase(self, team_id: int, player_id: int) -> None:
        state = self.team_rotations.get(team_id)
        if state is None:
            return
        active = [candidate for candidate in state.player_ids if self.streams.is_player_active(candidate)]
        if player_id not in active:
            return
        state.showcased_player_id = player_id
        state.last_rotation = monotonic()
        state.next_index = (active.index(player_id) + 1) % len(active)
        await self._layout_team_scene(team_id, showcased_player_id=player_id)

    async def _retire_player(self, player_id: int) -> None:
        sources = self.sources.get(player_id)
        if sources is None:
            return
        scene_names = (self.all_scene, *self.team_scenes.values())
        for scene in scene_names:
            for source in (sources.media_input, sources.label_input, sources.note_input):
                item_id = await self.obs.ensure_scene_item(scene, source, enabled=False)
                await self.obs.set_scene_item_enabled(scene, item_id, False)
        media_id = await self.obs.ensure_scene_item(sources.player_scene, sources.media_input, enabled=False)
        placeholder_id = await self.obs.ensure_scene_item(sources.player_scene, sources.placeholder_input, enabled=True)
        await self.obs.set_scene_item_enabled(sources.player_scene, media_id, False)
        await self.obs.set_scene_item_enabled(sources.player_scene, placeholder_id, True)

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
            _text_settings(player.name),
            enabled=True,
        )
        await self.obs.ensure_input(
            sources.player_scene,
            sources.note_input,
            self.config.obs.text_source_kind,
            _text_settings("♪", size=NOTE_FONT_SIZE),
            enabled=False,
        )
        await self.obs.ensure_input(
            sources.player_scene,
            sources.placeholder_input,
            self.config.obs.text_source_kind,
            {"text": f"{player.name}\nStream offline"},
            enabled=True,
        )

    async def _ensure_scene_offline_inputs(self) -> None:
        await self.obs.ensure_input(
            self.all_scene,
            self.all_offline_input,
            self.config.obs.text_source_kind,
            _text_settings(ALL_STREAMERS_OFFLINE_MESSAGE, size=OFFLINE_MESSAGE_FONT_SIZE),
            enabled=False,
        )
        for team_id, scene in self.team_scenes.items():
            team = self.teams_by_id[team_id]
            await self.obs.ensure_input(
                scene,
                self.team_label_inputs[team_id],
                self.config.obs.text_source_kind,
                _text_settings(team.name, size=TEAM_SCENE_LABEL_FONT_SIZE),
                enabled=True,
            )
            await self.obs.ensure_input(
                scene,
                self.team_offline_inputs[team_id],
                self.config.obs.text_source_kind,
                _text_settings(
                    f"All members of {team.name} currently offline",
                    size=OFFLINE_MESSAGE_FONT_SIZE,
                ),
                enabled=False,
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
        await self.obs.set_scene_item_transform(scene, label_id, positioned_transform(32, 28))
        await self._ensure_master_scenes_for_scene(scene)
        await self._ensure_overlay_on_top(scene)

    async def _layout_all_streamers(self) -> None:
        active_players = [player for player in self.players if self.streams.is_player_active(player.id)]
        rects = grid_layout(len(active_players), self.config.overlay.canvas_width, self.config.overlay.canvas_height)
        active_ids = {player.id for player in active_players}
        offline_id = await self.obs.ensure_scene_item(self.all_scene, self.all_offline_input, enabled=not active_players)
        await self.obs.set_scene_item_transform(self.all_scene, offline_id, self._offline_message_transform())
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
                await self.obs.set_scene_item_transform(self.all_scene, label_id, positioned_transform(rect.x + 10, rect.y + 10))
                await self.obs.set_scene_item_transform(self.all_scene, note_id, positioned_transform(rect.x + rect.width - 10, rect.y + 10, alignment=6))
        await self._ensure_master_scenes_for_scene(self.all_scene)
        await self._ensure_overlay_on_top(self.all_scene)

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
        offline_id = await self.obs.ensure_scene_item(scene, self.team_offline_inputs[team_id], enabled=not active_ids)
        await self.obs.set_scene_item_transform(scene, offline_id, self._offline_message_transform())
        team_label_id = await self.obs.ensure_scene_item(scene, self.team_label_inputs[team_id], enabled=True)
        await self.obs.set_scene_item_transform(scene, team_label_id, self._team_label_transform())

        for player_id in team_player_ids:
            player = self.players_by_id[player_id]
            sources = self.sources[player.id]
            enabled = player.id in ordered_ids
            media_id = await self.obs.ensure_scene_item(scene, sources.media_input, enabled=enabled)
            label_id = await self.obs.ensure_scene_item(scene, sources.label_input, enabled=enabled)
            note_id = await self.obs.ensure_scene_item(scene, sources.note_input, enabled=False)
            await self.obs.set_input_mute(sources.media_input, player.id != showcased_player_id)
            if enabled:
                rect = rects[ordered_ids.index(player.id)]
                media_fit = fit_inside(STREAM_WIDTH, STREAM_HEIGHT, rect)
                await self.obs.set_scene_item_transform(scene, media_id, transform_from_fit(media_fit))
                await self.obs.set_scene_item_transform(scene, label_id, positioned_transform(rect.x + 10, rect.y + 10))
        await self._ensure_master_scenes_for_scene(scene)
        await self._ensure_team_label_on_top(team_id)
        await self._ensure_overlay_on_top(scene)

    async def _ensure_overlay_top_for_generated_scenes(self) -> None:
        for scene in self.generated_scene_names():
            if scene != self.config.obs.overlay_scene:
                await self._ensure_overlay_on_top(scene)

    async def _ensure_overlay_on_top(self, scene: str) -> None:
        item_id = await self.obs.ensure_scene_item(scene, self.config.obs.overlay_scene, enabled=True)
        await self.obs.move_scene_item_to_top(scene, item_id)

    async def _ensure_team_label_on_top(self, team_id: int) -> None:
        scene = self.team_scenes[team_id]
        item_id = await self.obs.ensure_scene_item(scene, self.team_label_inputs[team_id], enabled=True)
        await self.obs.move_scene_item_to_top(scene, item_id)

    async def _ensure_master_scenes(self) -> None:
        for scene in self._configured_master_scenes():
            await self.obs.ensure_scene(scene)

    async def _ensure_master_scenes_for_scene(self, scene: str) -> None:
        all_master = self.config.obs.all_managed_master_scene
        if all_master:
            item_id = await self.obs.ensure_scene_item(scene, all_master, enabled=True)
            await self.obs.move_scene_item_to_bottom(scene, item_id)
        stream_master = self.config.obs.stream_layout_master_scene
        if stream_master and scene in (self.all_scene, *self.team_scenes.values()):
            item_id = await self.obs.ensure_scene_item(scene, stream_master, enabled=True)
            await self.obs.move_scene_item_to_top(scene, item_id)

    def _configured_master_scenes(self) -> tuple[str, ...]:
        return tuple(
            scene
            for scene in (
                self.config.obs.all_managed_master_scene,
                self.config.obs.stream_layout_master_scene,
            )
            if scene
        )

    def _validate_master_scene_names(self) -> None:
        generated = set(self.generated_scene_names())
        forbidden = generated | {self.config.obs.overlay_scene}
        for scene in self._configured_master_scenes():
            if scene in forbidden:
                raise ObsError(
                    f"Configured master scene {scene!r} cannot be the overlay scene or a generated managed scene"
                )

    def _offline_message_transform(self):
        width = self.config.overlay.canvas_width * 0.72
        height = 180
        return transform_from_fit(
            Rect(
                (self.config.overlay.canvas_width - width) / 2,
                (self.config.overlay.canvas_height - height) / 2,
                width,
                height,
            )
        )

    def _team_label_transform(self):
        return positioned_transform(self.config.overlay.canvas_width / 2, 12, alignment=4)

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

    def _updated_team_rotation(self, team_id: int) -> TeamRotation:
        player_ids = [player.id for player in self.players if player.team_id == team_id]
        existing = self.team_rotations.get(team_id)
        if existing is None:
            return TeamRotation(player_ids)
        existing.player_ids = player_ids
        if existing.showcased_player_id not in player_ids:
            existing.showcased_player_id = None
            existing.next_index = 0
        return existing


def _slug(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._ -]+", "", value).strip()
    return cleaned or "unknown"
