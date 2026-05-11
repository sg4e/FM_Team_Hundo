from __future__ import annotations

import pytest

from fm_hundo_obs.audio import AudioRotator
from fm_hundo_obs.config import FeatureFlags, GroupSceneConfig
from fm_hundo_obs.obs import ObsError

from .fakes import FakeObs


class Window:
    def __init__(self, active: bool = False) -> None:
        self.active = active

    def acquisition_active(self) -> bool:
        return self.active


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

