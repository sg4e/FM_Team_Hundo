from __future__ import annotations

import pytest

from fm_hundo_obs.config import AppConfig, MediaMtxConfig
from fm_hundo_obs.layout import Rect, fit_inside, grid_layout
from fm_hundo_obs.managed_layout import ObsLayoutManager
from fm_hundo_obs.mediamtx import StreamRegistry, parse_active_paths
from fm_hundo_obs.models import Player, Team

from .fakes import FakeObs


class FakeMediaMtx:
    config = MediaMtxConfig()

    def rtsp_url(self, path: str) -> str:
        return f"rtsp://127.0.0.1:8554/{path}"


def players() -> list[Player]:
    return [
        Player(10, "runner10", "Runner Ten", None, 1),
        Player(11, "runner11", "Runner Eleven", None, 1),
        Player(20, "runner20", "Runner Twenty", None, 2),
    ]


def registry(active: set[str]) -> StreamRegistry:
    streams = StreamRegistry(players(), FakeMediaMtx())  # type: ignore[arg-type]
    streams.set_active_paths_for_tests(active)
    return streams


def test_parse_active_paths_handles_ready_and_source_shapes():
    payload = {
        "items": [
            {"name": "runner10", "ready": True},
            {"name": "runner11", "ready": False, "source": {"type": "rtmpSource"}},
            {"name": "runner20", "source": {"type": "rtmpSource"}},
            {"name": "empty"},
        ]
    }

    assert parse_active_paths(payload) == {"runner10", "runner20"}


def test_stream_registry_maps_twitch_id_to_rtsp_url_and_active_state():
    streams = registry({"runner10"})

    assert streams.path_for_player(10) == "runner10"
    assert streams.rtsp_url_for_player(10) == "rtsp://127.0.0.1:8554/runner10"
    assert streams.is_player_active(10) is True
    assert streams.is_player_active(11) is False


def test_layout_math_fit_inside_and_grid():
    fit = fit_inside(1280, 720, Rect(0, 0, 1920, 1080))
    assert fit.width == 1920
    assert fit.height == 1080

    tall_fit = fit_inside(720, 1280, Rect(0, 0, 1920, 1080))
    assert tall_fit.height == 1080
    assert tall_fit.width < 1920

    assert len(grid_layout(5, 1920, 1080)) == 5


@pytest.mark.asyncio
async def test_layout_manager_creates_managed_scenes_and_hides_inactive_streams():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10"}))

    await manager.setup()

    assert "FM Hundo - All Streamers" in obs.scenes
    assert "FM Hundo - Team - Alpha" in obs.scenes
    assert manager.player_scene_name(10) == "FM Hundo - Player - Runner Ten"
    assert manager.is_player_active(10) is True
    assert manager.is_player_active(11) is False
    assert obs.inputs_settings["FM Hundo Media - Runner Ten"]["input"] == "rtsp://127.0.0.1:8554/runner10"
    assert obs.inputs_settings["FM Hundo Media - Runner Ten"]["close_when_inactive"] is True


@pytest.mark.asyncio
async def test_layout_manager_prepares_inactive_player_placeholder():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry(set()))

    await manager.setup()
    await manager.prepare_cut_to_player(10, "Big Drop Alert\nStream offline")

    assert obs.inputs_settings["FM Hundo Placeholder - Runner Ten"]["text"] == "Runner Ten\nBig Drop Alert\nStream offline"


@pytest.mark.asyncio
async def test_all_streamers_and_team_audio_rotation():
    obs = FakeObs(current_scene="FM Hundo - All Streamers")
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    assert await manager.tick_all_streamers_audio(force=True) is True
    assert ("FM Hundo Media - Runner Eleven", False) in obs.mutes

    obs.current_scene = "FM Hundo - Team - Alpha"
    assert await manager.tick_team_showcases(force=True) is True
    assert any(muted is False for _, muted in obs.mutes)
