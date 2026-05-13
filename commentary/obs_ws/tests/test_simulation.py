from __future__ import annotations

import pytest

from fm_hundo_obs.config import AppConfig, FeatureFlags, MediaMtxConfig, TimingConfig
from fm_hundo_obs.main import Application
from fm_hundo_obs.managed_layout import ObsLayoutManager
from fm_hundo_obs.mapping import NameResolver
from fm_hundo_obs.mediamtx import StreamRegistry
from fm_hundo_obs.models import CardAcquisition, Player, Team
from fm_hundo_obs.scheduler import AcquisitionScheduler
from fm_hundo_obs.simulation import SIMULATION_TEAM, build_simulation_roster, simulated_player_id

from .fakes import FakeObs, FakeOverlay


class FakeMediaMtx:
    config = MediaMtxConfig()

    async def active_paths(self) -> set[str]:
        return set()

    def rtsp_url(self, path: str) -> str:
        return f"rtsp://127.0.0.1:8554/{path}"


class CapturingScheduler:
    def __init__(self) -> None:
        self.events: list[tuple[CardAcquisition, int | None, bool]] = []

    async def handle_acquisition(self, acquisition: CardAcquisition, team_id: int | None = None, *, force: bool = False):
        self.events.append((acquisition, team_id, force))

        class Result:
            reason = "accepted"

        return Result()


def test_build_simulation_roster_uses_paths_as_players_and_is_deterministic():
    roster = build_simulation_roster({"alpha_cam", "beta_cam"})

    assert roster.teams == [SIMULATION_TEAM]
    assert [player.name for player in roster.players] == ["alpha_cam", "beta_cam"]
    assert roster.player_id_for_path("alpha_cam") == simulated_player_id("alpha_cam")
    assert simulated_player_id("alpha_cam") == simulated_player_id("alpha_cam")


def test_simulation_test_player_id_looks_up_path():
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.simulation_roster = build_simulation_roster({"alpha_cam"})

    assert app._test_player_id("alpha_cam") == simulated_player_id("alpha_cam")

    with pytest.raises(ValueError):
        app._test_player_id("missing_cam")


@pytest.mark.asyncio
async def test_simulation_intro_includes_simulation_team_name():
    roster = build_simulation_roster({"alpha_cam"})
    player = roster.players[0]
    overlay = FakeOverlay()
    subject = AcquisitionScheduler(
        FakeObs(current_scene="Main"),
        overlay,
        NameResolver(roster.players, {5: "Villager2"}, roster.teams),
        {player.id: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(player.id, "drop", 5), team_id=SIMULATION_TEAM.id)

    assert result.accepted is True
    assert overlay.intros == [("Simulation - alpha_cam", "Villager2", 3.0)]
    await subject._active_task


@pytest.mark.asyncio
async def test_simulation_intro_infers_team_for_manual_acquisition():
    roster = build_simulation_roster({"alpha_cam"})
    player = roster.players[0]
    overlay = FakeOverlay()
    subject = AcquisitionScheduler(
        FakeObs(current_scene="Main"),
        overlay,
        NameResolver(roster.players, {5: "Villager2"}, roster.teams),
        {player.id: "Player Ten"},
        FeatureFlags(),
        TimingConfig(acquisition_window_seconds=0.01, intro_seconds=3),
    )

    result = await subject.handle_acquisition(CardAcquisition.test_event(player.id, "drop", 5))

    assert result.accepted is True
    assert overlay.intros == [("Simulation - alpha_cam", "Villager2", 3)]
    await subject._active_task


@pytest.mark.asyncio
async def test_simulation_path_test_command_routes_to_generated_player():
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.simulation_roster = build_simulation_roster({"alpha_cam"})
    scheduler = CapturingScheduler()
    app.scheduler = scheduler  # type: ignore[assignment]

    await app._handle_test(("alpha_cam", "drop", "5"), force=True)

    assert scheduler.events[0][0].player_id == simulated_player_id("alpha_cam")
    assert scheduler.events[0][0].source == "drop"
    assert scheduler.events[0][1] is None
    assert scheduler.events[0][2] is True


@pytest.mark.asyncio
async def test_layout_update_roster_adds_and_retires_simulated_paths():
    obs = FakeObs()
    streams = StreamRegistry([], FakeMediaMtx())  # type: ignore[arg-type]
    roster = build_simulation_roster({"alpha_cam"})
    streams.set_active_paths_for_tests({"alpha_cam"})
    streams.update_players(roster.players)
    manager = ObsLayoutManager(obs, AppConfig(), roster.players, roster.teams, streams)
    await manager.setup()

    assert manager.player_scene_name(simulated_player_id("alpha_cam")) == "FM Hundo - Player - alpha_cam"

    next_roster = build_simulation_roster({"beta_cam"})
    streams.set_active_paths_for_tests({"beta_cam"})
    streams.update_players(next_roster.players)
    await manager.update_roster(next_roster.players, next_roster.teams)

    assert manager.player_scene_name(simulated_player_id("beta_cam")) == "FM Hundo - Player - beta_cam"
    assert manager.player_scene_name(simulated_player_id("alpha_cam")) is not None
    retired_media = "FM Hundo Media - alpha_cam"
    retired_item_ids = [
        item_id
        for (scene, source), item_id in obs.scene_items.items()
        if source == retired_media and scene == "FM Hundo - All Streamers"
    ]
    assert retired_item_ids
    assert ("FM Hundo - All Streamers", retired_item_ids[0], False) in obs.enabled
