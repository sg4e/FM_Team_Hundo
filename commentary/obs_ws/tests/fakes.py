from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass

from fm_hundo_obs.models import MessageType
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
        self.volumes: list[tuple[str, float]] = []
        self.source_filters: dict[tuple[str, str], dict] = {}
        self.source_filter_calls: list[dict] = []
        self.created_scenes: list[str] = []
        self.inputs_settings: dict[str, dict] = {}
        self.scene_items: dict[tuple[str, str], int] = {}
        self.enabled: list[tuple[str, int, bool]] = []
        self.transforms: list[tuple[str, int, SceneItemTransform]] = []
        self.refreshed_browser_sources: list[str] = []
        self.indices: list[tuple[str, int, int]] = []
        self.top_moves: list[tuple[str, int]] = []
        self.bottom_moves: list[tuple[str, int]] = []
        self.current_program_scene_callbacks: list[Callable[[str], Awaitable[None]]] = []

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

    async def register_current_program_scene_callback(self, callback: Callable[[str], Awaitable[None]]) -> None:
        self.current_program_scene_callbacks.append(callback)

    async def trigger_current_program_scene_changed(self, scene_name: str) -> None:
        self.current_scene = scene_name
        for callback in self.current_program_scene_callbacks:
            await callback(scene_name)

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        self.mutes.append((input_name, muted))

    async def set_input_volume(self, input_name: str, volume_mul: float) -> None:
        self.volumes.append((input_name, volume_mul))

    async def ensure_source_filter(
        self,
        source_name: str,
        filter_name: str,
        filter_kind: str,
        settings: dict,
        *,
        enabled: bool = True,
        index: int | None = None,
        sync_settings: bool = True,
        overlay: bool = True,
    ) -> None:
        key = (source_name, filter_name)
        existing = self.source_filters.get(key)
        if existing is None:
            self.source_filters[key] = {
                "kind": filter_kind,
                "settings": dict(settings),
                "enabled": enabled,
                "index": index,
            }
            action = "create"
        else:
            if sync_settings:
                existing["settings"] = dict(settings)
            existing["kind"] = filter_kind
            existing["enabled"] = enabled
            existing["index"] = index
            action = "update"
        self.source_filter_calls.append(
            {
                "action": action,
                "source_name": source_name,
                "filter_name": filter_name,
                "filter_kind": filter_kind,
                "settings": dict(settings),
                "enabled": enabled,
                "index": index,
                "sync_settings": sync_settings,
                "overlay": overlay,
            }
        )

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

    async def refresh_browser_source(self, input_name: str) -> None:
        self.refreshed_browser_sources.append(input_name)

    async def set_scene_item_index(self, scene_name: str, item_id: int, index: int) -> None:
        self.indices.append((scene_name, item_id, index))

    async def move_scene_item_to_top(self, scene_name: str, item_id: int) -> None:
        scene_item_count = sum(1 for item_scene, _source in self.scene_items if item_scene == scene_name)
        top_index = max(0, scene_item_count - 1)
        self.top_moves.append((scene_name, item_id))
        await self.set_scene_item_index(scene_name, item_id, top_index)

    async def move_scene_item_to_bottom(self, scene_name: str, item_id: int) -> None:
        self.bottom_moves.append((scene_name, item_id))
        await self.set_scene_item_index(scene_name, item_id, 0)


@dataclass
class IntroCall:
    player_name: str
    opponent_name: str
    duration_seconds: float
    player_id: int = 0
    opponent_id: int = 0
    use_twitch_profile: bool = True


class FakeOverlay:
    def __init__(self, banner_success: bool = True) -> None:
        self.banner_success = banner_success
        self.banners: list[tuple[str, MessageType, float]] = []
        self.intros: list[IntroCall] = []

    async def banner(
        self,
        label: str,
        source: MessageType,
        duration_seconds: float,
        *,
        delay_seconds: float = 0.0,
        enter_seconds: float = 0.3,
        exit_seconds: float = 0.3,
    ) -> bool:
        self.banners.append((label, source, duration_seconds))
        return self.banner_success

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
        self.intros.append(IntroCall(
            player_name=player_name,
            opponent_name=opponent_name,
            duration_seconds=duration_seconds,
            player_id=player_id,
            opponent_id=opponent_id,
            use_twitch_profile=use_twitch_profile,
        ))
        return True
