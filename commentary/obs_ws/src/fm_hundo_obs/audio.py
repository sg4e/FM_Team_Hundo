from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging
from time import monotonic

from .config import FeatureFlags, GroupSceneConfig
from .obs import ObsController
from .scheduler import WindowObserver

LOGGER = logging.getLogger(__name__)


@dataclass
class GroupRotationState:
    config: GroupSceneConfig
    next_index: int = 0
    last_rotation: float = 0.0


class AudioRotator:
    def __init__(
        self,
        obs: ObsController,
        features: FeatureFlags,
        window_observer: WindowObserver,
        groups: tuple[GroupSceneConfig, ...],
        poll_seconds: float = 1.0,
    ) -> None:
        self.obs = obs
        self.features = features
        self.window_observer = window_observer
        self.groups = {group.scene: GroupRotationState(group) for group in groups}
        self.poll_seconds = poll_seconds
        self._closed = False
        self._active_scene: str | None = None

    def close(self) -> None:
        self._closed = True

    async def run(self) -> None:
        while not self._closed:
            await self.tick()
            await asyncio.sleep(self.poll_seconds)

    async def tick(self, *, force: bool = False) -> bool:
        if not self.features.audio_rotation or not self.obs.connected or self.window_observer.acquisition_active():
            return False
        current_scene = await self.obs.get_current_program_scene()
        state = self.groups.get(current_scene)
        if state is None or not state.config.audio_sources:
            self._active_scene = None
            return False

        now = monotonic()
        first_visit = self._active_scene != current_scene
        due = first_visit or force or (now - state.last_rotation >= state.config.interval_seconds)
        self._active_scene = current_scene
        if not due:
            return False

        await self._activate(state)
        state.last_rotation = now
        return True

    async def _activate(self, state: GroupRotationState) -> None:
        sources = state.config.audio_sources
        active = sources[state.next_index % len(sources)]
        state.next_index = (state.next_index + 1) % len(sources)
        LOGGER.info("Activating group-scene audio source %s", active)
        for source in sources:
            await self.obs.set_input_mute(source, source != active)

