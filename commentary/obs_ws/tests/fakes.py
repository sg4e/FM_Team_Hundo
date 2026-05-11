from __future__ import annotations

from collections.abc import Sequence

from fm_hundo_obs.obs import ObsController, ObsError


class FakeObs(ObsController):
    def __init__(
        self,
        current_scene: str = "Main",
        scenes: set[str] | None = None,
        inputs: set[str] | None = None,
        overlay_items: set[str] | None = None,
    ) -> None:
        self.current_scene = current_scene
        self.scenes = scenes or {"Main", "Player Ten", "Player Eleven", "Group", "Overlay"}
        self.inputs = inputs or {"Audio A", "Audio B", "Overlay Browser"}
        self.overlay_items = overlay_items or {"Overlay Browser"}
        self.connected_value = True
        self.scene_changes: list[str] = []
        self.mutes: list[tuple[str, bool]] = []

    async def connect(self) -> None:
        self.connected_value = True

    async def disconnect(self) -> None:
        self.connected_value = False

    @property
    def connected(self) -> bool:
        return self.connected_value

    async def validate(
        self,
        player_scenes: Sequence[str],
        group_scenes: Sequence[str],
        overlay_scene: str,
        overlay_source: str,
        audio_sources: Sequence[str],
    ) -> None:
        missing_scenes = set(player_scenes) | set(group_scenes) | {overlay_scene}
        missing_scenes -= self.scenes
        if missing_scenes:
            raise ObsError(f"Missing scenes: {missing_scenes}")
        if overlay_source not in self.overlay_items:
            raise ObsError("Missing overlay source")
        missing_inputs = set(audio_sources) - self.inputs
        if missing_inputs:
            raise ObsError(f"Missing inputs: {missing_inputs}")

    async def get_current_program_scene(self) -> str:
        return self.current_scene

    async def set_current_program_scene(self, scene_name: str) -> None:
        self.current_scene = scene_name
        self.scene_changes.append(scene_name)

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        self.mutes.append((input_name, muted))


class FakeOverlay:
    def __init__(self, banner_success: bool = True) -> None:
        self.banner_success = banner_success
        self.banners: list[tuple[str, float]] = []
        self.intros: list[tuple[str, str, float]] = []

    async def banner(self, label: str, duration_seconds: float) -> bool:
        self.banners.append((label, duration_seconds))
        return self.banner_success

    async def intro(self, player_name: str, opponent_name: str, duration_seconds: float) -> bool:
        self.intros.append((player_name, opponent_name, duration_seconds))
        return True

