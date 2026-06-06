from __future__ import annotations

import asyncio

import pytest

from fm_hundo_obs.config import FeatureFlags, TimingConfig
from fm_hundo_obs.mapping import NameResolver
from fm_hundo_obs.models import CardAcquisition, LibraryUpdate, MessageType, Player, Team
from fm_hundo_obs.obs import stable_item_id
from fm_hundo_obs.scheduler import AcquisitionScheduler

from .fakes import FakeObs, FakeOverlay, IntroCall


class FakeResolver:
    def __init__(self, active: bool) -> None:
        self.active = active
        self.prepared: list[tuple[int, str | None]] = []
        self.alert_focuses: list[int] = []
        self.scene_focuses: list[tuple[int, str]] = []

    def player_scene_name(self, player_id: int) -> str | None:
        return "Player Ten"

    def is_player_active(self, player_id: int) -> bool:
        return self.active

    async def prepare_cut_to_player(self, player_id: int, message: str | None = None) -> None:
        self.prepared.append((player_id, message))

    async def focus_player_for_alert(self, player_id: int) -> None:
        self.alert_focuses.append(player_id)

    async def focus_player_for_scene(self, player_id: int, scene_name: str) -> None:
        self.scene_focuses.append((player_id, scene_name))


def scheduler(
    *,
    obs: FakeObs | None = None,
    overlay: FakeOverlay | None = None,
    features: FeatureFlags | None = None,
    player_scenes: dict[int, str] | None = None,
    window_seconds: float = 0.01,
    acquisition_delay_seconds: float = 0.0,
) -> AcquisitionScheduler:
    names = NameResolver(
        [Player(10, "ten", "Runner Ten", None, 1), Player(11, "eleven", "Runner Eleven", None, 1)],
        {5: "Villager2"},
        [Team(1, "Alpha")],
    )
    return AcquisitionScheduler(
        obs or FakeObs(),
        overlay or FakeOverlay(),
        names,
        player_scenes if player_scenes is not None else {10: "Player Ten", 11: "Player Eleven"},
        features or FeatureFlags(),
        TimingConfig(
            acquisition_window_seconds=window_seconds,
            acquisition_delay_seconds=acquisition_delay_seconds,
            banner_end_buffer_seconds=0,
            intro_seconds=3,
        ),
    )


@pytest.mark.asyncio
async def test_uses_first_acquisition_only_and_switches_then_restores():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay)
    update = LibraryUpdate(
        1,
        (
            CardAcquisition.test_event(10, "drop", 5),
            CardAcquisition.test_event(11, "ritual", 5),
        ),
    )

    result = await subject.handle_update(update)

    assert result.accepted is True
    assert obs.scene_changes == ["Player Ten"]
    assert overlay.banners == [("Big Drop Alert", MessageType.DROP, 0.01)]
    assert overlay.intros == [IntroCall("Alpha - Runner Ten", "Villager2", 3, player_id=10, opponent_id=5)]
    assert subject._active_task is not None
    await subject._active_task
    assert obs.scene_changes == ["Player Ten", "Main"]


@pytest.mark.asyncio
async def test_acquisition_delay_locks_immediately_then_delays_all_actions():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay, window_seconds=0.2, acquisition_delay_seconds=0.05)

    pending = asyncio.create_task(subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5)))
    await asyncio.sleep(0)

    assert subject.acquisition_active()
    assert obs.scene_changes == []
    assert overlay.banners == []
    assert overlay.intros == []

    blocked = await subject.handle_acquisition(CardAcquisition.test_event(11, "ritual", 5))
    assert blocked.accepted is False
    assert blocked.reason == "active acquisition window"

    result = await pending

    assert result.accepted is True
    assert obs.scene_changes == ["Player Ten"]
    assert len(overlay.banners) == 1
    assert overlay.banners[0][:2] == ("Big Drop Alert", MessageType.DROP)
    assert overlay.banners[0][2] == pytest.approx(0.15)
    assert overlay.intros == [IntroCall("Alpha - Runner Ten", "Villager2", 3, player_id=10, opponent_id=5)]
    assert subject._active_task is not None
    await subject._active_task
    assert obs.scene_changes == ["Player Ten", "Main"]


@pytest.mark.asyncio
async def test_active_window_skips_new_acquisitions():
    obs = FakeObs(current_scene="Main")
    subject = scheduler(obs=obs, window_seconds=0.2)

    first = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))
    second = await subject.handle_acquisition(CardAcquisition.test_event(11, "ritual", 5))

    assert first.accepted is True
    assert second.accepted is False
    assert second.reason == "active acquisition window"


@pytest.mark.asyncio
async def test_pause_skips_acquisitions():
    subject = scheduler(features=FeatureFlags(paused=True))

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is False
    assert result.reason == "paused"


@pytest.mark.asyncio
async def test_missing_scene_alert_only_locks_window():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay, player_scenes={})

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert obs.scene_changes == []
    assert overlay.banners == [("Big Drop Alert", MessageType.DROP, 0.01)]


@pytest.mark.asyncio
async def test_fusion_banner_includes_source_for_overlay_coloring():
    overlay = FakeOverlay()
    subject = scheduler(obs=FakeObs(current_scene="Main"), overlay=overlay)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "fusion", 5))

    assert result.accepted is True
    assert overlay.banners == [("New Fusion Alert", MessageType.FUSE, 0.01)]


@pytest.mark.asyncio
async def test_ritual_banner_includes_source_for_overlay_coloring():
    overlay = FakeOverlay()
    subject = scheduler(obs=FakeObs(current_scene="Main"), overlay=overlay)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "ritual", 5))

    assert result.accepted is True
    assert overlay.banners == [("New Ritual Alert", MessageType.RITUAL, 0.01)]


@pytest.mark.asyncio
async def test_intro_falls_back_to_player_name_when_team_unknown():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 99)], {5: "Villager2"}, [])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5), team_id=99)

    assert result.accepted is True
    assert overlay.intros == [IntroCall("Runner Ten", "Villager2", 3, player_id=10, opponent_id=5)]


@pytest.mark.asyncio
async def test_manual_acquisition_infers_team_from_player_when_team_id_omitted():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert overlay.intros == [IntroCall("Alpha - Runner Ten", "Villager2", 3, player_id=10, opponent_id=5)]


@pytest.mark.asyncio
async def test_same_scene_locks_without_intro_or_switch():
    obs = FakeObs(current_scene="Player Ten")
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert obs.scene_changes == []
    assert overlay.intros == []
    assert subject.acquisition_active()


@pytest.mark.asyncio
async def test_scene_disabled_banner_enabled_still_locks():
    obs = FakeObs(current_scene="Main")
    features = FeatureFlags(scene_switching=False)
    subject = scheduler(obs=obs, features=features)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert obs.scene_changes == []
    assert subject.acquisition_active()


@pytest.mark.asyncio
async def test_banner_disabled_scene_switch_still_locks():
    obs = FakeObs(current_scene="Main")
    features = FeatureFlags(banner_overlay=False)
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay, features=features)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert overlay.banners == []
    assert obs.scene_changes == ["Player Ten"]


@pytest.mark.asyncio
async def test_restore_respects_manual_scene_change():
    obs = FakeObs(current_scene="Main")
    subject = scheduler(obs=obs)

    await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))
    obs.current_scene = "Manual"
    assert subject._active_task is not None
    await subject._active_task

    assert obs.scene_changes == ["Player Ten"]
    assert obs.current_scene == "Manual"


@pytest.mark.asyncio
async def test_obs_disconnected_skips():
    obs = FakeObs()
    obs.connected_value = False
    subject = scheduler(obs=obs)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is False
    assert result.reason == "OBS disconnected"


async def test_credits_scene_lock_skips_acquisitions():
    subject = scheduler()
    subject.scene_lock = lambda: asyncio.sleep(0, result=True)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5), force=True)

    assert result.accepted is False
    assert result.reason == "credits scene active"


@pytest.mark.asyncio
async def test_inactive_managed_player_prepares_placeholder_before_cut():
    obs = FakeObs(current_scene="Main")
    resolver = FakeResolver(active=False)
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        FakeOverlay(),
        names,
        resolver,
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert resolver.prepared == [(10, "Big Drop Alert\nStream offline")]
    assert resolver.alert_focuses == [10]
    assert obs.scene_changes == ["Player Ten"]


@pytest.mark.asyncio
async def test_managed_player_focuses_audio_when_already_on_player_scene():
    obs = FakeObs(current_scene="Player Ten")
    resolver = FakeResolver(active=True)
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        FakeOverlay(),
        names,
        resolver,
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert resolver.alert_focuses == [10]
    assert obs.scene_changes == []


@pytest.mark.asyncio
async def test_restore_focuses_managed_previous_scene_after_switch_back():
    obs = FakeObs(current_scene="FM Hundo - All Streamers")
    resolver = FakeResolver(active=True)
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        FakeOverlay(),
        names,
        resolver,
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))
    assert subject._active_task is not None
    await subject._active_task

    assert obs.scene_changes == ["Player Ten", "FM Hundo - All Streamers"]
    assert resolver.scene_focuses == [(10, "FM Hundo - All Streamers")]


@pytest.mark.asyncio
async def test_manual_scene_change_prevents_restore_focus():
    obs = FakeObs(current_scene="FM Hundo - All Streamers")
    resolver = FakeResolver(active=True)
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        FakeOverlay(),
        names,
        resolver,
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))
    obs.current_scene = "Manual"
    assert subject._active_task is not None
    await subject._active_task

    assert obs.scene_changes == ["Player Ten"]
    assert resolver.scene_focuses == []


@pytest.mark.asyncio
async def test_intro_uses_twitch_profile_in_production():
    """In production mode (default), intro uses use_twitch_profile=True."""
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    subject = scheduler(obs=obs, overlay=overlay)

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert overlay.intros == [IntroCall("Alpha - Runner Ten", "Villager2", 3, player_id=10, opponent_id=5, use_twitch_profile=True)]


@pytest.mark.asyncio
async def test_intro_uses_twitch_profile_in_simulation():
    """Simulation alerts keep Twitch profile behavior at production parity."""
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert overlay.intros == [IntroCall("Alpha - Runner Ten", "Villager2", 3, player_id=10, opponent_id=5, use_twitch_profile=True)]


@pytest.mark.asyncio
async def test_intro_respects_delay_when_configured():
    """Intro delay > 0 should delay the intro message, not the scene switch."""
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.5, banner_end_buffer_seconds=0, intro_seconds=3, intro_delay_seconds=0.05),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    assert obs.scene_changes == ["Player Ten"], "Scene switch should happen immediately"
    assert overlay.banners == [("Big Drop Alert", MessageType.DROP, 0.5)], "Banner should happen immediately"
    assert len(overlay.intros) == 1
    assert overlay.intros[0].player_name == "Alpha - Runner Ten"
    assert overlay.intros[0].use_twitch_profile is True
    # Window should have been started BEFORE the delay, so it's already active.
    assert subject.acquisition_active(), "Window should lock before intro delay"
    assert subject._active_task is not None
    await subject._active_task


@pytest.mark.asyncio
async def test_second_acquisition_blocked_during_intro_delay():
    """A second acquisition arriving during the intro delay is rejected because
    the window is locked before the delay begins."""
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1), Player(11, "eleven", "Runner Eleven", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten", 11: "Player Eleven"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.5, intro_seconds=3, intro_delay_seconds=0.1),
    )

    # First acquisition starts the window immediately (before the 0.1s intro delay).
    first = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))
    assert first.accepted is True
    assert subject.acquisition_active(), "Window should be active immediately"

    # Second acquisition is rejected because the window was already locked.
    second = await subject.handle_acquisition(CardAcquisition.test_event(11, "drop", 5))
    assert second.accepted is False
    assert second.reason == "active acquisition window"
    # Intro for the first player was sent (handle_acquisition blocks through the delay).
    assert len(overlay.intros) == 1
    assert overlay.intros[0].player_name == "Alpha - Runner Ten"

    assert subject._active_task is not None
    await subject._active_task


@pytest.mark.asyncio
async def test_alert_audio_enables_then_disables():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.05, intro_seconds=0.01, alert_audio_duration_seconds=0.01),
        alert_audio_source="FM Hundo Alert Audio",
        overlay_scene="FM Hundo Overlay",
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    assert result.accepted is True
    await asyncio.sleep(0)
    alert_id = stable_item_id("FM Hundo Overlay", "FM Hundo Alert Audio")
    enable_calls = [entry for entry in obs.enabled if entry[0] == "FM Hundo Overlay" and entry[1] == alert_id]
    assert len(enable_calls) >= 1
    assert enable_calls[0][2] is True
    assert subject._alert_audio_task is not None
    await subject._alert_audio_task
    disable_calls = [entry for entry in obs.enabled if entry[0] == "FM Hundo Overlay" and entry[1] == alert_id and entry[2] is False]
    assert len(disable_calls) >= 1
    assert subject._active_task is not None
    await subject._active_task


@pytest.mark.asyncio
async def test_alert_audio_skipped_when_feature_disabled():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten"},
        FeatureFlags(alert_audio=False),
        TimingConfig(acquisition_window_seconds=0.05, intro_seconds=0.01),
        alert_audio_source="FM Hundo Alert Audio",
        overlay_scene="FM Hundo Overlay",
    )

    await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))

    alert_id = stable_item_id("FM Hundo Overlay", "FM Hundo Alert Audio")
    audio_calls = [
        entry for entry in obs.enabled
        if entry[0] == "FM Hundo Overlay" and entry[1] == alert_id
    ]
    assert audio_calls == []


@pytest.mark.asyncio
async def test_alert_audio_skipped_when_no_source_configured():
    obs = FakeObs(current_scene="Main")
    overlay = FakeOverlay()
    names = NameResolver([Player(10, "ten", "Runner Ten", None, 1)], {5: "Villager2"}, [Team(1, "Alpha")])
    subject = AcquisitionScheduler(
        obs,
        overlay,
        names,
        {10: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.05, intro_seconds=0.01),
    )

    await subject.handle_acquisition(CardAcquisition.test_event(10, "drop", 5))
    assert subject._alert_audio_task is None
