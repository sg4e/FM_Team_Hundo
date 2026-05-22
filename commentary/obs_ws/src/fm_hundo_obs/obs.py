from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
import logging

import simpleobsws

from .config import ObsConfig
from .layout import Fit, Rect

LOGGER = logging.getLogger(__name__)


class ObsError(RuntimeError):
    pass


@dataclass(frozen=True)
class SceneItemTransform:
    x: float
    y: float
    width: float
    height: float
    bounds_type: str = "OBS_BOUNDS_STRETCH"
    alignment: int = 5


class ObsController:
    async def connect(self) -> None:
        raise NotImplementedError

    async def disconnect(self) -> None:
        raise NotImplementedError

    @property
    def connected(self) -> bool:
        raise NotImplementedError

    async def validate(
        self,
        player_scenes: Sequence[str],
        group_scenes: Sequence[str],
        overlay_scene: str,
        overlay_source: str,
        audio_sources: Sequence[str],
    ) -> None:
        raise NotImplementedError

    async def get_current_program_scene(self) -> str:
        raise NotImplementedError

    async def set_current_program_scene(self, scene_name: str) -> None:
        raise NotImplementedError

    async def register_current_program_scene_callback(self, callback: Callable[[str], Awaitable[None]]) -> None:
        raise NotImplementedError

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        raise NotImplementedError

    async def ensure_scene(self, scene_name: str) -> None:
        raise NotImplementedError

    async def ensure_input(
        self,
        scene_name: str,
        input_name: str,
        input_kind: str,
        settings: dict,
        *,
        enabled: bool = True,
    ) -> int:
        raise NotImplementedError

    async def ensure_scene_item(self, scene_name: str, source_name: str, *, enabled: bool = True) -> int:
        raise NotImplementedError

    async def set_scene_item_enabled(self, scene_name: str, item_id: int, enabled: bool) -> None:
        raise NotImplementedError

    async def set_scene_item_transform(self, scene_name: str, item_id: int, transform: SceneItemTransform) -> None:
        raise NotImplementedError

    async def set_input_settings(self, input_name: str, settings: dict, *, overlay: bool = True) -> None:
        raise NotImplementedError

    async def refresh_browser_source(self, input_name: str) -> None:
        raise NotImplementedError

    async def set_scene_item_index(self, scene_name: str, item_id: int, index: int) -> None:
        raise NotImplementedError

    async def move_scene_item_to_top(self, scene_name: str, item_id: int) -> None:
        raise NotImplementedError

    async def move_scene_item_to_bottom(self, scene_name: str, item_id: int) -> None:
        raise NotImplementedError


class SimpleObsController(ObsController):
    def __init__(
        self,
        config: ObsConfig,
        client_factory: Callable[..., simpleobsws.WebSocketClient] = simpleobsws.WebSocketClient,
    ) -> None:
        self.config = config
        self._client_factory = client_factory
        self._client: simpleobsws.WebSocketClient | None = None
        self._connected = False

    @property
    def connected(self) -> bool:
        return self._connected

    async def connect(self) -> None:
        LOGGER.info("Connecting to OBS WebSocket at %s", self.config.websocket_url)
        client = self._client_factory(url=self.config.websocket_url, password=self.config.password or "")
        await client.connect()
        identified = await client.wait_until_identified()
        if not identified:
            await self._disconnect_failed_client(client)
            raise ObsError(self._identification_failure_message(client))
        self._client = client
        self._connected = True
        LOGGER.info("Connected to OBS WebSocket at %s", self.config.websocket_url)

    async def disconnect(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
        self._connected = False

    async def _disconnect_failed_client(self, client) -> None:
        try:
            await client.disconnect()
        except Exception:
            LOGGER.debug("Failed to disconnect un-identified OBS WebSocket client cleanly", exc_info=True)
        self._connected = False

    def _identification_failure_message(self, client) -> str:
        ws = getattr(client, "ws", None)
        close_code = getattr(ws, "close_code", None)
        close_reason = getattr(ws, "close_reason", None)
        detail = f"close code {close_code}, reason {close_reason!r}" if close_code else "no close code reported"
        if close_code == 4009 or close_reason == "Authentication failed.":
            return (
                "OBS WebSocket authentication failed. Check obs.password in config.yml or "
                "set OBS_WS_PASSWORD to the password shown in OBS Tools > WebSocket Server Settings. "
                f"Connection detail: {detail}."
            )
        return (
            "OBS WebSocket connected but did not complete identification. "
            "Verify OBS WebSocket Server Settings, password, and that OBS is fully started. "
            f"Connection detail: {detail}."
        )

    async def validate(
        self,
        player_scenes: Sequence[str],
        group_scenes: Sequence[str],
        overlay_scene: str,
        overlay_source: str,
        audio_sources: Sequence[str],
    ) -> None:
        scenes = set(await self._scene_names())
        missing_scenes = sorted((set(player_scenes) | set(group_scenes) | {overlay_scene}) - scenes)
        if missing_scenes:
            raise ObsError(f"Missing OBS scene(s): {', '.join(missing_scenes)}")

        scene_items = await self._scene_item_sources(overlay_scene)
        if overlay_source not in scene_items:
            raise ObsError(f"Overlay source {overlay_source!r} is not present in scene {overlay_scene!r}")

        inputs = set(await self._input_names())
        missing_audio = sorted(set(audio_sources) - inputs)
        if missing_audio:
            raise ObsError(f"Missing OBS audio input(s): {', '.join(missing_audio)}")

    async def get_current_program_scene(self) -> str:
        data = await self._call("GetCurrentProgramScene")
        return str(data["currentProgramSceneName"])

    async def set_current_program_scene(self, scene_name: str) -> None:
        await self._call("SetCurrentProgramScene", {"sceneName": scene_name})

    async def register_current_program_scene_callback(self, callback: Callable[[str], Awaitable[None]]) -> None:
        if self._client is None:
            raise ObsError("OBS is not connected")

        async def _handle_current_program_scene_changed(event_data: dict | None) -> None:
            if event_data is None:
                return
            scene_name = event_data.get("sceneName")
            if scene_name is not None:
                await callback(str(scene_name))

        self._client.register_event_callback(
            _handle_current_program_scene_changed,
            "CurrentProgramSceneChanged",
        )

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        await self._call("SetInputMute", {"inputName": input_name, "inputMuted": muted})

    async def ensure_scene(self, scene_name: str) -> None:
        if scene_name not in set(await self._scene_names()):
            await self._call("CreateScene", {"sceneName": scene_name})

    async def ensure_input(
        self,
        scene_name: str,
        input_name: str,
        input_kind: str,
        settings: dict,
        *,
        enabled: bool = True,
    ) -> int:
        inputs = set(await self._input_names())
        if input_name not in inputs:
            data = await self._call(
                "CreateInput",
                {
                    "sceneName": scene_name,
                    "inputName": input_name,
                    "inputKind": input_kind,
                    "inputSettings": settings,
                    "sceneItemEnabled": enabled,
                },
            )
            return int(data["sceneItemId"])
        await self.set_input_settings(input_name, settings)
        return await self.ensure_scene_item(scene_name, input_name, enabled=enabled)

    async def ensure_scene_item(self, scene_name: str, source_name: str, *, enabled: bool = True) -> int:
        item_id = await self._find_scene_item_id(scene_name, source_name)
        if item_id is None:
            data = await self._call(
                "CreateSceneItem",
                {"sceneName": scene_name, "sourceName": source_name, "sceneItemEnabled": enabled},
            )
            return int(data["sceneItemId"])
        await self.set_scene_item_enabled(scene_name, item_id, enabled)
        return item_id

    async def set_scene_item_enabled(self, scene_name: str, item_id: int, enabled: bool) -> None:
        await self._call(
            "SetSceneItemEnabled",
            {"sceneName": scene_name, "sceneItemId": item_id, "sceneItemEnabled": enabled},
        )

    async def set_scene_item_transform(self, scene_name: str, item_id: int, transform: SceneItemTransform) -> None:
        scene_item_transform = {
            "positionX": transform.x,
            "positionY": transform.y,
            "alignment": transform.alignment,
        }
        if transform.bounds_type:
            scene_item_transform["boundsType"] = transform.bounds_type
            scene_item_transform["boundsWidth"] = transform.width
            scene_item_transform["boundsHeight"] = transform.height
        await self._call(
            "SetSceneItemTransform",
            {"sceneName": scene_name, "sceneItemId": item_id, "sceneItemTransform": scene_item_transform},
        )

    async def set_input_settings(self, input_name: str, settings: dict, *, overlay: bool = True) -> None:
        await self._call(
            "SetInputSettings",
            {"inputName": input_name, "inputSettings": settings, "overlay": overlay},
        )

    async def refresh_browser_source(self, input_name: str) -> None:
        try:
            await self._call(
                "PressInputPropertiesButton",
                {"inputName": input_name, "propertyName": "refreshnocache"},
            )
        except ObsError:
            LOGGER.warning("Unable to refresh OBS browser source %s", input_name, exc_info=True)

    async def set_scene_item_index(self, scene_name: str, item_id: int, index: int) -> None:
        await self._call(
            "SetSceneItemIndex",
            {"sceneName": scene_name, "sceneItemId": item_id, "sceneItemIndex": index},
        )

    async def move_scene_item_to_top(self, scene_name: str, item_id: int) -> None:
        data = await self._call("GetSceneItemList", {"sceneName": scene_name})
        top_index = max(0, len(data["sceneItems"]) - 1)
        await self.set_scene_item_index(scene_name, item_id, top_index)

    async def move_scene_item_to_bottom(self, scene_name: str, item_id: int) -> None:
        await self.set_scene_item_index(scene_name, item_id, 0)

    async def _scene_names(self) -> list[str]:
        data = await self._call("GetSceneList")
        return [str(scene["sceneName"]) for scene in data["scenes"]]

    async def _input_names(self) -> list[str]:
        data = await self._call("GetInputList")
        return [str(item["inputName"]) for item in data["inputs"]]

    async def _scene_item_sources(self, scene_name: str) -> set[str]:
        data = await self._call("GetSceneItemList", {"sceneName": scene_name})
        return {str(item["sourceName"]) for item in data["sceneItems"]}

    async def _find_scene_item_id(self, scene_name: str, source_name: str) -> int | None:
        data = await self._call("GetSceneItemList", {"sceneName": scene_name})
        for item in data["sceneItems"]:
            if item["sourceName"] == source_name:
                return int(item["sceneItemId"])
        return None

    async def _call(self, request_type: str, request_data: dict | None = None) -> dict:
        if self._client is None:
            raise ObsError("OBS is not connected")
        response = await self._client.call(simpleobsws.Request(request_type, request_data or {}))
        if not response.ok():
            raise ObsError(f"OBS request {request_type} failed: {response.requestStatus}")
        return dict(response.responseData or {})


class DryRunObsController(ObsController):
    def __init__(self, wrapped: ObsController) -> None:
        self.wrapped = wrapped
        self.actions: list[str] = []

    @property
    def connected(self) -> bool:
        return self.wrapped.connected

    async def connect(self) -> None:
        await self.wrapped.connect()

    async def disconnect(self) -> None:
        await self.wrapped.disconnect()

    async def validate(
        self,
        player_scenes: Sequence[str],
        group_scenes: Sequence[str],
        overlay_scene: str,
        overlay_source: str,
        audio_sources: Sequence[str],
    ) -> None:
        await self.wrapped.validate(player_scenes, group_scenes, overlay_scene, overlay_source, audio_sources)

    async def get_current_program_scene(self) -> str:
        return await self.wrapped.get_current_program_scene()

    async def set_current_program_scene(self, scene_name: str) -> None:
        self.actions.append(f"set current scene -> {scene_name}")

    async def register_current_program_scene_callback(self, callback: Callable[[str], Awaitable[None]]) -> None:
        await self.wrapped.register_current_program_scene_callback(callback)

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        self.actions.append(f"set mute {input_name} -> {muted}")

    async def ensure_scene(self, scene_name: str) -> None:
        self.actions.append(f"ensure scene {scene_name}")

    async def ensure_input(
        self,
        scene_name: str,
        input_name: str,
        input_kind: str,
        settings: dict,
        *,
        enabled: bool = True,
    ) -> int:
        self.actions.append(f"ensure input {input_name} ({input_kind}) in {scene_name}, enabled={enabled}")
        return stable_item_id(scene_name, input_name)

    async def ensure_scene_item(self, scene_name: str, source_name: str, *, enabled: bool = True) -> int:
        self.actions.append(f"ensure scene item {source_name} in {scene_name}, enabled={enabled}")
        return stable_item_id(scene_name, source_name)

    async def set_scene_item_enabled(self, scene_name: str, item_id: int, enabled: bool) -> None:
        self.actions.append(f"set item {item_id} enabled in {scene_name} -> {enabled}")

    async def set_scene_item_transform(self, scene_name: str, item_id: int, transform: SceneItemTransform) -> None:
        self.actions.append(f"set item {item_id} transform in {scene_name} -> {transform}")

    async def set_input_settings(self, input_name: str, settings: dict, *, overlay: bool = True) -> None:
        self.actions.append(f"set input settings {input_name} overlay={overlay} -> {settings}")

    async def refresh_browser_source(self, input_name: str) -> None:
        self.actions.append(f"refresh browser source {input_name}")

    async def set_scene_item_index(self, scene_name: str, item_id: int, index: int) -> None:
        self.actions.append(f"set item {item_id} index in {scene_name} -> {index}")

    async def move_scene_item_to_top(self, scene_name: str, item_id: int) -> None:
        self.actions.append(f"move item {item_id} to top in {scene_name}")

    async def move_scene_item_to_bottom(self, scene_name: str, item_id: int) -> None:
        self.actions.append(f"move item {item_id} to bottom in {scene_name}")

    def report(self) -> str:
        return "\n".join(self.actions)


def stable_item_id(scene_name: str, source_name: str) -> int:
    return abs(hash((scene_name, source_name))) % 1_000_000


def transform_from_fit(fit: Fit | Rect) -> SceneItemTransform:
    return SceneItemTransform(x=fit.x, y=fit.y, width=fit.width, height=fit.height)


def positioned_transform(x: float, y: float, alignment: int = 5) -> SceneItemTransform:
    return SceneItemTransform(x=x, y=y, width=0, height=0, bounds_type="", alignment=alignment)


def max_only_positioned_transform(
    x: float,
    y: float,
    width: float,
    height: float,
    alignment: int = 5,
) -> SceneItemTransform:
    return SceneItemTransform(
        x=x,
        y=y,
        width=width,
        height=height,
        bounds_type="OBS_BOUNDS_MAX_ONLY",
        alignment=alignment,
    )
