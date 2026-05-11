from __future__ import annotations

from collections.abc import Sequence
import logging

import simpleobsws

from .config import ObsConfig

LOGGER = logging.getLogger(__name__)


class ObsError(RuntimeError):
    pass


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

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        raise NotImplementedError


class SimpleObsController(ObsController):
    def __init__(self, config: ObsConfig) -> None:
        self.config = config
        self._client: simpleobsws.WebSocketClient | None = None
        self._connected = False

    @property
    def connected(self) -> bool:
        return self._connected

    async def connect(self) -> None:
        client = simpleobsws.WebSocketClient(url=self.config.websocket_url, password=self.config.password or "")
        await client.connect()
        await client.wait_until_identified()
        self._client = client
        self._connected = True

    async def disconnect(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
        self._connected = False

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

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        await self._call("SetInputMute", {"inputName": input_name, "inputMuted": muted})

    async def _scene_names(self) -> list[str]:
        data = await self._call("GetSceneList")
        return [str(scene["sceneName"]) for scene in data["scenes"]]

    async def _input_names(self) -> list[str]:
        data = await self._call("GetInputList")
        return [str(item["inputName"]) for item in data["inputs"]]

    async def _scene_item_sources(self, scene_name: str) -> set[str]:
        data = await self._call("GetSceneItemList", {"sceneName": scene_name})
        return {str(item["sourceName"]) for item in data["sceneItems"]}

    async def _call(self, request_type: str, request_data: dict | None = None) -> dict:
        if self._client is None:
            raise ObsError("OBS is not connected")
        response = await self._client.call(simpleobsws.Request(request_type, request_data or {}))
        if not response.ok():
            raise ObsError(f"OBS request {request_type} failed: {response.requestStatus}")
        return dict(response.responseData or {})
