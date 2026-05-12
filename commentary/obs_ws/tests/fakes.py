from __future__ import annotations

from collections.abc import Sequence

from fm_hundo_obs.obs import ObsController, ObsError, SceneItemTransform, stable_item_id


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
        self.created_scenes: list[str] = []
        self.inputs_settings: dict[str, dict] = {}
        self.scene_items: dict[tuple[str, str], int] = {}
        self.enabled: list[tuple[str, int, bool]] = []
        self.transforms: list[tuple[str, int, SceneItemTransform]] = []

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

    async def ensure_scene(self, scene_name: str) -> None:
        self.scenes.add(scene_name)
        self.created_scenes.append(scene_name)

    async def ensure_input(
        self,
        scene_name: str,
        input_name: str,
        input_kind: str,
        settings: dict,
        *,
        enabled: bool = True,
    ) -> int:
        self.inputs.add(input_name)
        self.inputs_settings[input_name] = settings
        return await self.ensure_scene_item(scene_name, input_name, enabled=enabled)

    async def ensure_scene_item(self, scene_name: str, source_name: str, *, enabled: bool = True) -> int:
        key = (scene_name, source_name)
        item_id = self.scene_items.setdefault(key, stable_item_id(scene_name, source_name))
        await self.set_scene_item_enabled(scene_name, item_id, enabled)
        return item_id

    async def set_scene_item_enabled(self, scene_name: str, item_id: int, enabled: bool) -> None:
        self.enabled.append((scene_name, item_id, enabled))

    async def set_scene_item_transform(self, scene_name: str, item_id: int, transform: SceneItemTransform) -> None:
        self.transforms.append((scene_name, item_id, transform))

    async def set_input_settings(self, input_name: str, settings: dict, *, overlay: bool = True) -> None:
        self.inputs_settings[input_name] = {**self.inputs_settings.get(input_name, {}), **settings}


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
