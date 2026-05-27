from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
import logging
from typing import Protocol, runtime_checkable

from .config import FeatureFlags, TimingConfig
from .mapping import NameResolver
from .models import AcquisitionContext, CardAcquisition, LibraryUpdate, MessageType
from .obs import ObsController

LOGGER = logging.getLogger(__name__)




class OverlaySink(Protocol):
    async def banner(
        self,
        label: str,
        source: MessageType,
        duration_seconds: float,
        *,
        enter_seconds: float = 0.3,
        exit_seconds: float = 0.3,
    ) -> bool:
        ...

    async def intro(
        self,
        player_name: str,
        opponent_name: str,
        duration_seconds: float,
        *,
        player_id: int = 0,
        opponent_id: int = 0,
        use_twitch_profile: bool = True,
    ) -> bool:
        ...

class WindowObserver(Protocol):
    def acquisition_active(self) -> bool:
        ...


@runtime_checkable
class PlayerSceneResolver(Protocol):
    def player_scene_name(self, player_id: int) -> str | None:
        ...

    def is_player_active(self, player_id: int) -> bool:
        ...

    async def prepare_cut_to_player(self, player_id: int, message: str | None = None) -> None:
        ...

    async def focus_player_for_alert(self, player_id: int) -> None:
        ...

    async def focus_player_for_scene(self, player_id: int, scene_name: str) -> None:
        ...


@dataclass(frozen=True)
class AcquisitionResult:
    accepted: bool
    reason: str
    context: AcquisitionContext | None = None


class AcquisitionScheduler:
    def __init__(
        self,
        obs: ObsController,
        overlay: OverlaySink,
        names: NameResolver,
        player_scenes: dict[int, str] | PlayerSceneResolver,
        features: FeatureFlags,
        timing: TimingConfig,
        scene_lock: Callable[[], Awaitable[bool]] | None = None,
        *,
        simulate_mediamtx: bool = False,
        alert_audio_source: str | None = None,
        overlay_scene: str | None = None,
    ) -> None:
        self.obs = obs
        self.overlay = overlay
        self.names = names
        self.player_scenes = player_scenes
        self.features = features
        self.timing = timing
        self.scene_lock = scene_lock
        self.simulate_mediamtx = simulate_mediamtx
        self._active_task: asyncio.Task[None] | None = None
        self._alert_audio_source = alert_audio_source
        self._overlay_scene = overlay_scene
        self._alert_audio_task: asyncio.Task[None] | None = None

    def acquisition_active(self) -> bool:
        return self._active_task is not None and not self._active_task.done()

    def cancel_active_window(self) -> None:
        if self._active_task is not None and not self._active_task.done():
            self._active_task.cancel()
        self._active_task = None
        if self._alert_audio_task is not None and not self._alert_audio_task.done():
            self._alert_audio_task.cancel()
        self._alert_audio_task = None

    async def handle_update(self, update: LibraryUpdate) -> AcquisitionResult:
        if not update.new_acquisitions:
            return AcquisitionResult(False, "no acquisitions")
        return await self.handle_acquisition(update.new_acquisitions[0], team_id=update.team_id)

    async def handle_acquisition(
        self,
        acquisition: CardAcquisition,
        team_id: int | None = None,
        *,
        force: bool = False,
    ) -> AcquisitionResult:
        if self.scene_lock is not None and await self.scene_lock():
            return AcquisitionResult(False, "credits scene active")
        if self.features.paused and not force:
            return AcquisitionResult(False, "paused")
        if self.acquisition_active() and not force:
            return AcquisitionResult(False, "active acquisition window")
        if not self.obs.connected:
            return AcquisitionResult(False, "OBS disconnected")
        if acquisition.alert_label is None:
            return AcquisitionResult(False, f"unsupported source {acquisition.source}")

        resolved_team_id = team_id if team_id is not None else self.names.player_team_id(acquisition.player_id)
        scene_name = self._scene_for_player(acquisition.player_id)
        context = AcquisitionContext(
            acquisition=acquisition,
            player_name=self.names.player_name(acquisition.player_id),
            opponent_name=self.names.opponent_name(acquisition.opponent_id),
            scene_name=scene_name,
            alert_label=acquisition.alert_label,
            team_id=resolved_team_id,
        )

        previous_scene: str | None = None
        automated_scene: str | None = None
        visible_action = False
        switched_scene = False

        if scene_name is None:
            LOGGER.warning("No OBS scene configured for player id %s", acquisition.player_id)
        else:
            current_scene = await self.obs.get_current_program_scene()
            previous_scene = current_scene
            if self.features.scene_switching:
                if current_scene != scene_name:
                    if isinstance(self.player_scenes, PlayerSceneResolver):
                        active = self.player_scenes.is_player_active(acquisition.player_id)
                        message = None if active else f"{context.alert_label}\nStream offline"
                        await self.player_scenes.prepare_cut_to_player(acquisition.player_id, message)
                        await self.player_scenes.focus_player_for_alert(acquisition.player_id)
                    await self.obs.set_current_program_scene(scene_name)
                    automated_scene = scene_name
                    switched_scene = True
                    visible_action = True
                else:
                    if isinstance(self.player_scenes, PlayerSceneResolver):
                        await self.player_scenes.focus_player_for_alert(acquisition.player_id)
                    visible_action = True

        if self.features.banner_overlay:
            banner_duration = (
                self.timing.banner_total_seconds
                if self.timing.banner_total_seconds is not None
                else self.timing.acquisition_window_seconds - self.timing.banner_end_buffer_seconds
            )
            visible_action = (
                await self.overlay.banner(
                    context.alert_label,
                    context.acquisition.source,
                    banner_duration,
                    enter_seconds=self.timing.banner_enter_seconds,
                    exit_seconds=self.timing.banner_exit_seconds,
                )
                or visible_action
            )

        # Lock the acquisition window immediately so new acquisitions
        # are rejected during any intro delay that follows.
        if visible_action:
            self._start_window(previous_scene, automated_scene, acquisition.player_id)
            self._start_alert_audio()

        if switched_scene and self.features.intro_overlay:
            if self.timing.intro_delay_seconds > 0:
                await asyncio.sleep(self.timing.intro_delay_seconds)
            await self.overlay.intro(
                self.names.intro_player_name(acquisition.player_id, resolved_team_id),
                context.opponent_name,
                self.timing.intro_seconds,
                player_id=acquisition.player_id,
                opponent_id=acquisition.opponent_id,
                use_twitch_profile=not self.simulate_mediamtx,
            )

        if not visible_action:
            return AcquisitionResult(False, "no visible action", context)

        return AcquisitionResult(True, "accepted", context)

    def _scene_for_player(self, player_id: int) -> str | None:
        if isinstance(self.player_scenes, PlayerSceneResolver):
            return self.player_scenes.player_scene_name(player_id)
        return self.player_scenes.get(player_id)

    def _start_window(self, previous_scene: str | None, automated_scene: str | None, player_id: int) -> None:
        if self._active_task is not None and not self._active_task.done():
            self._active_task.cancel()
        self._active_task = asyncio.create_task(self._window(previous_scene, automated_scene, player_id))

    async def _window(self, previous_scene: str | None, automated_scene: str | None, player_id: int) -> None:
        try:
            await asyncio.sleep(self.timing.acquisition_window_seconds)
            if previous_scene and automated_scene and self.obs.connected:
                current_scene = await self.obs.get_current_program_scene()
                if current_scene == automated_scene:
                    await self.obs.set_current_program_scene(previous_scene)
                    if isinstance(self.player_scenes, PlayerSceneResolver):
                        await self.player_scenes.focus_player_for_scene(player_id, previous_scene)
        except asyncio.CancelledError:
            raise
        except Exception:
            LOGGER.exception("Failed while completing acquisition window")

    def _start_alert_audio(self) -> None:
        if not self.features.alert_audio:
            return
        source = self._alert_audio_source
        scene = self._overlay_scene
        if not source or not scene:
            return
        self._alert_audio_task = asyncio.create_task(self._play_alert_audio(scene, source))

    async def _play_alert_audio(self, scene: str, source: str) -> None:
        item_id: int | None = None
        try:
            item_id = await self.obs.ensure_scene_item(scene, source, enabled=True)
            await asyncio.sleep(self.timing.alert_audio_duration_seconds)
            await self.obs.set_scene_item_enabled(scene, item_id, False)
        except asyncio.CancelledError:
            if item_id is not None:
                try:
                    await self.obs.set_scene_item_enabled(scene, item_id, False)
                except Exception:
                    pass
            raise
        except Exception:
            LOGGER.exception("Failed alert audio playback")
