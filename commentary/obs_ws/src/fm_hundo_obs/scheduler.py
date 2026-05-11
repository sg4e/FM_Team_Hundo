from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging
from typing import Protocol

from .config import FeatureFlags, TimingConfig
from .mapping import NameResolver
from .models import AcquisitionContext, CardAcquisition, LibraryUpdate
from .obs import ObsController
from .overlay import OverlayEvents

LOGGER = logging.getLogger(__name__)


class WindowObserver(Protocol):
    def acquisition_active(self) -> bool:
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
        overlay: OverlayEvents,
        names: NameResolver,
        player_scenes: dict[int, str],
        features: FeatureFlags,
        timing: TimingConfig,
    ) -> None:
        self.obs = obs
        self.overlay = overlay
        self.names = names
        self.player_scenes = player_scenes
        self.features = features
        self.timing = timing
        self._active_task: asyncio.Task[None] | None = None

    def acquisition_active(self) -> bool:
        return self._active_task is not None and not self._active_task.done()

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
        if self.features.paused and not force:
            return AcquisitionResult(False, "paused")
        if self.acquisition_active() and not force:
            return AcquisitionResult(False, "active acquisition window")
        if not self.obs.connected:
            return AcquisitionResult(False, "OBS disconnected")
        if acquisition.alert_label is None:
            return AcquisitionResult(False, f"unsupported source {acquisition.source}")

        scene_name = self.player_scenes.get(acquisition.player_id)
        context = AcquisitionContext(
            acquisition=acquisition,
            player_name=self.names.player_name(acquisition.player_id),
            opponent_name=self.names.opponent_name(acquisition.opponent_id),
            scene_name=scene_name,
            alert_label=acquisition.alert_label,
            team_id=team_id,
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
                    await self.obs.set_current_program_scene(scene_name)
                    automated_scene = scene_name
                    switched_scene = True
                    visible_action = True
                else:
                    visible_action = True

        if self.features.banner_overlay:
            visible_action = await self.overlay.banner(context.alert_label, self.timing.acquisition_window_seconds) or visible_action

        if switched_scene and self.features.intro_overlay:
            await self.overlay.intro(context.player_name, context.opponent_name, self.timing.intro_seconds)

        if not visible_action:
            return AcquisitionResult(False, "no visible action", context)

        self._start_window(previous_scene, automated_scene)
        return AcquisitionResult(True, "accepted", context)

    def _start_window(self, previous_scene: str | None, automated_scene: str | None) -> None:
        if self._active_task is not None and not self._active_task.done():
            self._active_task.cancel()
        self._active_task = asyncio.create_task(self._window(previous_scene, automated_scene))

    async def _window(self, previous_scene: str | None, automated_scene: str | None) -> None:
        try:
            await asyncio.sleep(self.timing.acquisition_window_seconds)
            if previous_scene and automated_scene and self.obs.connected:
                current_scene = await self.obs.get_current_program_scene()
                if current_scene == automated_scene:
                    await self.obs.set_current_program_scene(previous_scene)
        except asyncio.CancelledError:
            raise
        except Exception:
            LOGGER.exception("Failed while completing acquisition window")

