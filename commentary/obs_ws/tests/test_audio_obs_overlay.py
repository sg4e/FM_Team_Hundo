from __future__ import annotations

import pytest

from fm_hundo_obs.audio import AudioRotator
from fm_hundo_obs.config import ObsConfig
from fm_hundo_obs.config import FeatureFlags, GroupSceneConfig
from fm_hundo_obs.obs import ObsError, SimpleObsController

from .fakes import FakeObs


class Window:
    def __init__(self, active: bool = False) -> None:
        self.active = active

    def acquisition_active(self) -> bool:
        return self.active


class FakeWs:
    close_code = 4009
    close_reason = "Authentication failed."


class FailingObsClient:
    def __init__(self, **_: object) -> None:
        self.ws = FakeWs()
        self.disconnected = False

    async def connect(self) -> None:
        pass

    async def wait_until_identified(self) -> bool:
        return False

    async def disconnect(self) -> None:
        self.disconnected = True


class SuccessfulObsClient:
    latest: SuccessfulObsClient | None = None

    def __init__(self, **_: object) -> None:
        self.callbacks = []
        SuccessfulObsClient.latest = self

    async def connect(self) -> None:
        pass

    async def wait_until_identified(self) -> bool:
        return True

    async def disconnect(self) -> None:
        pass

    def register_event_callback(self, callback, event: str | None = None) -> None:
        self.callbacks.append((callback, event))


@pytest.mark.asyncio
async def test_obs_validation_checks_scenes_overlay_and_audio():
    obs = FakeObs()
    await obs.validate(("Player Ten",), ("Group",), "Overlay", "Overlay Browser", ("Audio A",))

    with pytest.raises(ObsError):
        await obs.validate(("Missing",), (), "Overlay", "Overlay Browser", ())

    with pytest.raises(ObsError):
        await obs.validate((), (), "Overlay", "Missing Browser", ())

    with pytest.raises(ObsError):
        await obs.validate((), (), "Overlay", "Overlay Browser", ("Missing Audio",))


@pytest.mark.asyncio
async def test_obs_authentication_failure_raises_operator_friendly_error():
    controller = SimpleObsController(ObsConfig(password="wrong"), client_factory=FailingObsClient)

    with pytest.raises(ObsError) as error:
        await controller.connect()

    message = str(error.value)
    assert "authentication failed" in message.lower()
    assert "obs.password" in message
    assert "OBS_WS_PASSWORD" in message


@pytest.mark.asyncio
async def test_obs_registers_current_program_scene_callback():
    controller = SimpleObsController(ObsConfig(), client_factory=SuccessfulObsClient)
    await controller.connect()
    seen: list[str] = []

    async def callback(scene_name: str) -> None:
        seen.append(scene_name)

    await controller.register_current_program_scene_callback(callback)

    client = SuccessfulObsClient.latest
    assert client is not None
    assert len(client.callbacks) == 1
    event_callback, event_name = client.callbacks[0]
    assert event_name == "CurrentProgramSceneChanged"

    await event_callback({"sceneName": "FM Hundo - Player - Runner Ten"})

    assert seen == ["FM Hundo - Player - Runner Ten"]


@pytest.mark.asyncio
async def test_audio_rotation_unmutes_one_source_on_group_scene():
    obs = FakeObs(current_scene="Group")
    rotator = AudioRotator(
        obs,
        FeatureFlags(),
        Window(),
        (GroupSceneConfig("Group", ("Audio A", "Audio B"), interval_seconds=180),),
    )

    assert await rotator.tick() is True
    assert obs.mutes == [("Audio A", False), ("Audio B", True)]

    assert await rotator.tick(force=True) is True
    assert obs.mutes[-2:] == [("Audio A", True), ("Audio B", False)]


@pytest.mark.asyncio
async def test_audio_rotation_only_on_group_scene_and_pauses_during_window():
    obs = FakeObs(current_scene="Main")
    window = Window()
    rotator = AudioRotator(
        obs,
        FeatureFlags(),
        window,
        (GroupSceneConfig("Group", ("Audio A", "Audio B"), interval_seconds=180),),
    )

    assert await rotator.tick() is False
    obs.current_scene = "Group"
    window.active = True
    assert await rotator.tick(force=True) is False
    assert obs.mutes == []
