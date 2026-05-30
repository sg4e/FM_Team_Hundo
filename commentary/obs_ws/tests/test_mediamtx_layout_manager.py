from __future__ import annotations

from pathlib import Path

import pytest

from fm_hundo_obs.config import AppConfig, MediaMtxConfig, ObsAudioFilterSpec, ObsConfig, StreamAudioFiltersConfig
from fm_hundo_obs.layout import Rect, fit_inside, grid_layout
from fm_hundo_obs.managed_layout import ObsLayoutManager
from fm_hundo_obs.mediamtx import StreamRegistry, parse_active_paths
from fm_hundo_obs.models import Player, Team
from fm_hundo_obs.obs import ObsError

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


def registry(active: set[str], roster: list[Player] | None = None) -> StreamRegistry:
    streams = StreamRegistry(roster or players(), FakeMediaMtx())  # type: ignore[arg-type]
    streams.set_active_paths_for_tests(active)
    return streams


def app_config_with_master_scenes() -> AppConfig:
    return AppConfig(
        obs=ObsConfig(
            all_managed_master_scene="Prod Global",
            stream_layout_master_scene="Prod Stream Layout",
        )
    )


def latest_transform(obs: FakeObs, scene: str, source: str):
    item_id = obs.scene_items[(scene, source)]
    matches = [
        transform
        for transform_scene, transform_item_id, transform in obs.transforms
        if transform_scene == scene and transform_item_id == item_id
    ]
    assert matches
    return matches[-1]


def latest_enabled(obs: FakeObs, scene: str, source: str) -> bool:
    item_id = obs.scene_items[(scene, source)]
    matches = [
        enabled
        for enabled_scene, enabled_item_id, enabled in obs.enabled
        if enabled_scene == scene and enabled_item_id == item_id
    ]
    assert matches
    return matches[-1]


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
    assert ("FM Hundo - All Streamers", "FM Hundo Overlay") in obs.scene_items
    assert ("FM Hundo - Team - Alpha", "FM Hundo Overlay") in obs.scene_items
    assert ("FM Hundo - Player - Runner Ten", "FM Hundo Overlay") in obs.scene_items
    for scene in ("FM Hundo - All Streamers", "FM Hundo - Team - Alpha", "FM Hundo - Player - Runner Ten"):
        overlay_id = obs.scene_items[(scene, "FM Hundo Overlay")]
        assert (scene, overlay_id) in obs.top_moves
    assert manager.player_scene_name(10) == "FM Hundo - Player - Runner Ten"
    assert manager.is_player_active(10) is True
    assert manager.is_player_active(11) is False
    assert obs.inputs_settings["FM Hundo Media - Runner Ten"]["input"] == "rtsp://127.0.0.1:8554/runner10"
    assert obs.inputs_settings["FM Hundo Media - Runner Ten"]["close_when_inactive"] is True
    assert obs.inputs_settings["FM Hundo Label - Runner Ten"]["text"] == "Runner Ten - Alpha"
    assert obs.inputs_settings["FM Hundo Label - Runner Ten"]["font"]["size"] == 26
    assert obs.inputs_settings["FM Hundo Audio Note - Runner Ten"]["font"]["size"] == 30


@pytest.mark.asyncio
async def test_layout_manager_prepares_inactive_player_placeholder():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry(set()))

    await manager.setup()
    await manager.prepare_cut_to_player(10, "Big Drop Alert\nStream offline")

    assert obs.inputs_settings["FM Hundo Placeholder - Runner Ten"]["text"] == "Runner Ten\nBig Drop Alert\nStream offline"


@pytest.mark.asyncio
async def test_all_streamers_offline_message_toggles_with_active_streams():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry(set()))

    await manager.setup()

    source = "FM Hundo Offline Message - All Streamers"
    assert obs.inputs_settings[source]["text"] == "All players offline. Stay tuned for more live coverage of FM Team Hundo!"
    assert latest_enabled(obs, "FM Hundo - All Streamers", source) is True
    transform = latest_transform(obs, "FM Hundo - All Streamers", source)
    assert transform.x == pytest.approx(268.8)
    assert transform.y == 450
    assert transform.width == pytest.approx(1382.4)
    overlay_id = obs.scene_items[("FM Hundo - All Streamers", "FM Hundo Overlay")]
    assert ("FM Hundo - All Streamers", overlay_id) in obs.top_moves

    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10"}))
    await manager.setup()

    assert latest_enabled(obs, "FM Hundo - All Streamers", source) is False


@pytest.mark.asyncio
async def test_team_offline_message_toggles_with_active_team_streams():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10"}))

    await manager.setup()

    alpha_source = "FM Hundo Offline Message - Team - Alpha"
    beta_source = "FM Hundo Offline Message - Team - Beta"
    assert obs.inputs_settings[alpha_source]["text"] == "All members of Alpha currently offline"
    assert obs.inputs_settings[beta_source]["text"] == "All members of Beta currently offline"
    assert latest_enabled(obs, "FM Hundo - Team - Alpha", alpha_source) is False
    assert latest_enabled(obs, "FM Hundo - Team - Beta", beta_source) is True
    transform = latest_transform(obs, "FM Hundo - Team - Beta", beta_source)
    assert transform.x == pytest.approx(268.8)
    assert transform.y == 450
    assert transform.width == pytest.approx(1382.4)


@pytest.mark.asyncio
async def test_team_scene_label_is_centered_and_only_on_team_scenes():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry(set()))

    await manager.setup()

    source = "FM Hundo Team Label - Alpha"
    assert obs.inputs_settings[source]["text"] == "Alpha"
    assert obs.inputs_settings[source]["font"]["size"] == 34
    assert ("FM Hundo - Team - Alpha", source) in obs.scene_items
    assert ("FM Hundo - All Streamers", source) not in obs.scene_items
    assert ("FM Hundo - Player - Runner Ten", source) not in obs.scene_items
    assert latest_enabled(obs, "FM Hundo - Team - Alpha", source) is True
    assert latest_enabled(obs, "FM Hundo - Team - Alpha", "FM Hundo Offline Message - Team - Alpha") is True
    transform = latest_transform(obs, "FM Hundo - Team - Alpha", source)
    assert transform.bounds_type == ""
    assert transform.alignment == 4
    assert transform.x == 960
    assert transform.y == 12
    label_id = obs.scene_items[("FM Hundo - Team - Alpha", source)]
    overlay_id = obs.scene_items[("FM Hundo - Team - Alpha", "FM Hundo Overlay")]
    assert ("FM Hundo - Team - Alpha", label_id) in obs.top_moves
    assert ("FM Hundo - Team - Alpha", overlay_id) in obs.top_moves
    label_raise = max(index for index, move in enumerate(obs.top_moves) if move == ("FM Hundo - Team - Alpha", label_id))
    overlay_raise = max(index for index, move in enumerate(obs.top_moves) if move == ("FM Hundo - Team - Alpha", overlay_id))
    assert label_raise < overlay_raise


@pytest.mark.asyncio
async def test_master_scenes_are_nested_into_configured_managed_scenes():
    obs = FakeObs()
    manager = ObsLayoutManager(
        obs,
        app_config_with_master_scenes(),
        players(),
        [Team(1, "Alpha"), Team(2, "Beta")],
        registry({"runner10"}),
    )

    await manager.setup()

    generated_scenes = {
        "FM Hundo - All Streamers",
        "FM Hundo - Team - Alpha",
        "FM Hundo - Team - Beta",
        "FM Hundo - Player - Runner Ten",
        "FM Hundo - Player - Runner Eleven",
        "FM Hundo - Player - Runner Twenty",
    }
    assert "Prod Global" in obs.created_scenes
    assert "Prod Stream Layout" in obs.created_scenes
    for scene in generated_scenes:
        assert (scene, "Prod Global") in obs.scene_items
        global_id = obs.scene_items[(scene, "Prod Global")]
        assert (scene, global_id) in obs.bottom_moves
    for scene in ("FM Hundo - All Streamers", "FM Hundo - Team - Alpha", "FM Hundo - Team - Beta"):
        assert (scene, "Prod Stream Layout") in obs.scene_items
        stream_id = obs.scene_items[(scene, "Prod Stream Layout")]
        overlay_id = obs.scene_items[(scene, "FM Hundo Overlay")]
        assert (scene, stream_id) in obs.top_moves
        assert (scene, overlay_id) in obs.top_moves
        assert obs.top_moves.index((scene, stream_id)) < obs.top_moves.index((scene, overlay_id))
    for scene in ("FM Hundo - Player - Runner Ten", "FM Hundo - Player - Runner Eleven", "FM Hundo - Player - Runner Twenty"):
        assert (scene, "Prod Stream Layout") not in obs.scene_items


def test_master_scene_names_cannot_self_nest_generated_or_overlay_scenes():
    with pytest.raises(ObsError, match="master scene"):
        ObsLayoutManager(
            FakeObs(),
            AppConfig(obs=ObsConfig(all_managed_master_scene="FM Hundo Overlay")),
            players(),
            [Team(1, "Alpha")],
            registry(set()),
        )
    with pytest.raises(ObsError, match="master scene"):
        ObsLayoutManager(
            FakeObs(),
            AppConfig(obs=ObsConfig(stream_layout_master_scene="FM Hundo - All Streamers")),
            players(),
            [Team(1, "Alpha")],
            registry(set()),
        )


@pytest.mark.asyncio
async def test_all_streamers_and_team_audio_rotation():
    obs = FakeObs(current_scene="FM Hundo - All Streamers")
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    assert await manager.tick_all_streamers_audio(force=True) is True
    assert ("FM Hundo Media - Runner Eleven", False) in obs.mutes
    all_note_id = obs.scene_items[("FM Hundo - All Streamers", "FM Hundo Audio Note - Runner Eleven")]
    assert ("FM Hundo - All Streamers", all_note_id, True) in obs.enabled

    obs.current_scene = "FM Hundo - Team - Alpha"
    assert await manager.tick_team_showcases(force=True) is True
    assert any(muted is False for _, muted in obs.mutes)
    team_note_id = obs.scene_items[("FM Hundo - Team - Alpha", "FM Hundo Audio Note - Runner Ten")]
    assert ("FM Hundo - Team - Alpha", team_note_id, False) in obs.enabled
    assert ("FM Hundo - Team - Alpha", team_note_id, True) not in obs.enabled


@pytest.mark.asyncio
async def test_layout_manager_bounds_stream_tile_labels_without_changing_player_scene_label():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))

    await manager.setup()

    media = latest_transform(obs, "FM Hundo - All Streamers", "FM Hundo Media - Runner Ten")
    all_label = latest_transform(obs, "FM Hundo - All Streamers", "FM Hundo Label - Runner Ten")
    all_note = latest_transform(obs, "FM Hundo - All Streamers", "FM Hundo Audio Note - Runner Ten")
    team_label = latest_transform(obs, "FM Hundo - Team - Alpha", "FM Hundo Label - Runner Ten")
    player_label = latest_transform(obs, "FM Hundo - Player - Runner Ten", "FM Hundo Label - Runner Ten")

    assert media.bounds_type == "OBS_BOUNDS_STRETCH"
    assert all_label.bounds_type == "OBS_BOUNDS_MAX_ONLY"
    assert team_label.bounds_type == "OBS_BOUNDS_MAX_ONLY"
    assert player_label.bounds_type == ""
    assert all_label.alignment == 5
    assert all_note.bounds_type == ""
    assert all_note.alignment == 6


@pytest.mark.asyncio
async def test_all_streamers_orders_active_players_by_team_id_then_player_name():
    roster = [
        Player(1, "zeta", "Zeta", None, 2),
        Player(2, "bravo", "Bravo", None, 1),
        Player(3, "alpha", "Alpha", None, 2),
    ]
    obs = FakeObs()
    manager = ObsLayoutManager(
        obs,
        AppConfig(),
        roster,
        [Team(1, "One"), Team(2, "Two")],
        registry({"alpha", "bravo", "zeta"}, roster),
    )

    await manager.setup()

    bravo_media = latest_transform(obs, "FM Hundo - All Streamers", "FM Hundo Media - Bravo")
    alpha_media = latest_transform(obs, "FM Hundo - All Streamers", "FM Hundo Media - Alpha")
    zeta_media = latest_transform(obs, "FM Hundo - All Streamers", "FM Hundo Media - Zeta")

    assert (bravo_media.y, bravo_media.x) < (alpha_media.y, alpha_media.x)
    assert (alpha_media.y, alpha_media.x) < (zeta_media.y, zeta_media.x)


@pytest.mark.asyncio
async def test_missing_team_label_falls_back_to_team_id():
    roster = [Player(30, "runner30", "Runner Thirty", None, 30)]
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), roster, [], registry({"runner30"}, roster))

    await manager.setup()

    assert obs.inputs_settings["FM Hundo Label - Runner Thirty"]["text"] == "Runner Thirty - Team 30"


def test_decisions_file_records_durable_obs_layout_decisions():
    decisions = Path(__file__).parents[1] / "DECISIONS.md"

    assert decisions.exists()
    text = decisions.read_text(encoding="utf-8")
    assert "Player Name - Team Name" in text
    assert "OBS_BOUNDS_MAX_ONLY" in text
    assert "Simulation" in text
    assert "confirming a revised decision" in text


@pytest.mark.asyncio
async def test_focus_player_for_alert_unmutes_only_target_media():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    await manager.focus_player_for_alert(10)

    assert obs.mutes[-3:] == [
        ("FM Hundo Media - Runner Eleven", True),
        ("FM Hundo Media - Runner Ten", False),
        ("FM Hundo Media - Runner Twenty", True),
    ]


@pytest.mark.asyncio
async def test_focus_player_for_all_streamers_sets_note_and_resets_rotation():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    await manager.focus_player_for_scene(10, "FM Hundo - All Streamers")

    assert obs.mutes[-3:] == [
        ("FM Hundo Media - Runner Eleven", True),
        ("FM Hundo Media - Runner Ten", False),
        ("FM Hundo Media - Runner Twenty", True),
    ]
    assert latest_enabled(obs, "FM Hundo - All Streamers", "FM Hundo Audio Note - Runner Ten") is True
    assert latest_enabled(obs, "FM Hundo - All Streamers", "FM Hundo Audio Note - Runner Eleven") is False
    assert manager._all_audio_player_id == 10
    assert manager._all_audio_last_rotation > 0


@pytest.mark.asyncio
async def test_focus_player_for_team_scene_showcases_target_and_resets_rotation():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    await manager.focus_player_for_scene(10, "FM Hundo - Team - Alpha")

    state = manager.team_rotations[1]
    ten_media = latest_transform(obs, "FM Hundo - Team - Alpha", "FM Hundo Media - Runner Ten")
    eleven_media = latest_transform(obs, "FM Hundo - Team - Alpha", "FM Hundo Media - Runner Eleven")
    assert state.showcased_player_id == 10
    assert state.last_rotation > 0
    assert ten_media.width > eleven_media.width
    assert ("FM Hundo Media - Runner Ten", False) in obs.mutes
    assert ("FM Hundo Media - Runner Eleven", True) in obs.mutes


@pytest.mark.asyncio
async def test_focus_player_for_scene_ignores_inactive_player():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner11"}))
    await manager.setup()
    mute_count = len(obs.mutes)

    await manager.focus_player_for_scene(10, "FM Hundo - All Streamers")
    await manager.focus_player_for_scene(10, "FM Hundo - Team - Alpha")

    assert len(obs.mutes) == mute_count
    assert manager._all_audio_player_id is None


@pytest.mark.asyncio
async def test_manual_player_scene_focus_unmutes_selected_player_only():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    focused = await manager.reconcile_current_scene_audio("FM Hundo - Player - Runner Eleven", force=True)

    assert focused is True
    assert obs.mutes[-3:] == [
        ("FM Hundo Media - Runner Eleven", False),
        ("FM Hundo Media - Runner Ten", True),
        ("FM Hundo Media - Runner Twenty", True),
    ]
    assert manager._recent_scene_player_id == 11


@pytest.mark.asyncio
async def test_manual_player_scene_focus_unmutes_offline_selected_player():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry(set()))
    await manager.setup()

    focused = await manager.reconcile_current_scene_audio("FM Hundo - Player - Runner Ten", force=True)

    assert focused is True
    assert obs.mutes[-3:] == [
        ("FM Hundo Media - Runner Eleven", True),
        ("FM Hundo Media - Runner Ten", False),
        ("FM Hundo Media - Runner Twenty", True),
    ]


@pytest.mark.asyncio
async def test_return_to_all_streamers_keeps_recent_manual_player_audio():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()

    await manager.reconcile_current_scene_audio("FM Hundo - Player - Runner Ten", force=True)
    focused = await manager.reconcile_current_scene_audio("FM Hundo - All Streamers", force=True)

    assert focused is True
    assert obs.mutes[-3:] == [
        ("FM Hundo Media - Runner Eleven", True),
        ("FM Hundo Media - Runner Ten", False),
        ("FM Hundo Media - Runner Twenty", True),
    ]
    assert latest_enabled(obs, "FM Hundo - All Streamers", "FM Hundo Audio Note - Runner Ten") is True
    assert manager._all_audio_player_id == 10


@pytest.mark.asyncio
async def test_return_to_team_scene_keeps_recent_manual_player_showcased():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10", "runner11", "runner20"}))
    await manager.setup()

    await manager.reconcile_current_scene_audio("FM Hundo - Player - Runner Ten", force=True)
    focused = await manager.reconcile_current_scene_audio("FM Hundo - Team - Alpha", force=True)

    state = manager.team_rotations[1]
    ten_media = latest_transform(obs, "FM Hundo - Team - Alpha", "FM Hundo Media - Runner Ten")
    eleven_media = latest_transform(obs, "FM Hundo - Team - Alpha", "FM Hundo Media - Runner Eleven")
    assert focused is True
    assert state.showcased_player_id == 10
    assert ten_media.width > eleven_media.width
    assert obs.mutes[-3:] == [
        ("FM Hundo Media - Runner Eleven", True),
        ("FM Hundo Media - Runner Ten", False),
        ("FM Hundo Media - Runner Twenty", True),
    ]


@pytest.mark.asyncio
async def test_non_managed_scene_does_not_change_managed_audio_focus():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha")], registry({"runner10", "runner11"}))
    await manager.setup()
    await manager.reconcile_current_scene_audio("FM Hundo - Player - Runner Ten", force=True)
    mute_count = len(obs.mutes)

    focused = await manager.reconcile_current_scene_audio("Production Scene", force=True)

    assert focused is False
    assert len(obs.mutes) == mute_count


@pytest.mark.asyncio
async def test_stream_audio_filters_are_disabled_by_default():
    obs = FakeObs()
    manager = ObsLayoutManager(obs, AppConfig(), players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10"}))

    await manager.setup()

    assert obs.source_filter_calls == []
    assert obs.source_filters == {}


@pytest.mark.asyncio
async def test_stream_audio_filter_chain_applies_to_managed_media_sources_only():
    obs = FakeObs()
    filter_config = StreamAudioFiltersConfig(
        enabled=True,
        filters=(
            ObsAudioFilterSpec(
                name="FM Hundo Leveling Compressor",
                kind="compressor_filter",
                settings={"ratio": 4.0, "threshold": -18.0, "attack_time": 6, "release_time": 60},
            ),
            ObsAudioFilterSpec(
                name="FM Hundo Commentary Duck",
                kind="compressor_filter",
                settings={
                    "ratio": 10.0,
                    "threshold": -24.0,
                    "attack_time": 6,
                    "release_time": 250,
                    "sidechain_source": "Production Commentary Bus",
                },
            ),
            ObsAudioFilterSpec(
                name="FM Hundo Safety Limiter",
                kind="limiter_filter",
                enabled=False,
                settings={"threshold": -1.0, "release_time": 60},
            ),
        ),
    )
    config = AppConfig(obs=ObsConfig(stream_audio_filters=filter_config))
    manager = ObsLayoutManager(obs, config, players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10"}))

    await manager.setup()

    media_sources = {
        "FM Hundo Media - Runner Ten",
        "FM Hundo Media - Runner Eleven",
        "FM Hundo Media - Runner Twenty",
    }
    for source in media_sources:
        assert obs.source_filters[(source, "FM Hundo Leveling Compressor")] == {
            "kind": "compressor_filter",
            "settings": {"ratio": 4.0, "threshold": -18.0, "attack_time": 6, "release_time": 60},
            "enabled": True,
            "index": 0,
        }
        assert obs.source_filters[(source, "FM Hundo Commentary Duck")]["settings"]["sidechain_source"] == (
            "Production Commentary Bus"
        )
        assert obs.source_filters[(source, "FM Hundo Commentary Duck")]["index"] == 1
        assert obs.source_filters[(source, "FM Hundo Safety Limiter")] == {
            "kind": "limiter_filter",
            "settings": {"threshold": -1.0, "release_time": 60},
            "enabled": False,
            "index": 2,
        }

    assert {call["source_name"] for call in obs.source_filter_calls} == media_sources
    assert all(not source.startswith("FM Hundo Label -") for source, _filter in obs.source_filters)
    assert all(not source.startswith("FM Hundo Audio Note -") for source, _filter in obs.source_filters)
    assert all(not source.startswith("FM Hundo Placeholder -") for source, _filter in obs.source_filters)


@pytest.mark.asyncio
async def test_stream_audio_filters_can_leave_existing_settings_under_obs_control():
    obs = FakeObs()
    obs.source_filters[("FM Hundo Media - Runner Ten", "Manual Compressor")] = {
        "kind": "compressor_filter",
        "settings": {"threshold": -12.0},
        "enabled": True,
        "index": 5,
    }
    filter_config = StreamAudioFiltersConfig(
        enabled=True,
        sync_settings=False,
        filters=(
            ObsAudioFilterSpec(
                name="Manual Compressor",
                kind="compressor_filter",
                settings={"threshold": -24.0, "sidechain_source": "Production Commentary Bus"},
            ),
        ),
    )
    config = AppConfig(obs=ObsConfig(stream_audio_filters=filter_config))
    manager = ObsLayoutManager(obs, config, players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10"}))

    await manager.setup()

    assert obs.source_filters[("FM Hundo Media - Runner Ten", "Manual Compressor")]["settings"] == {
        "threshold": -12.0
    }
    assert obs.source_filters[("FM Hundo Media - Runner Ten", "Manual Compressor")]["index"] == 0
    assert obs.source_filters[("FM Hundo Media - Runner Eleven", "Manual Compressor")]["settings"] == {
        "threshold": -24.0,
        "sidechain_source": "Production Commentary Bus",
    }
    assert all(call["sync_settings"] is False for call in obs.source_filter_calls)


@pytest.mark.asyncio
async def test_stream_volume_is_set_on_media_sources():
    obs = FakeObs()
    config = AppConfig(obs=ObsConfig(stream_volume_mul=0.5))
    manager = ObsLayoutManager(obs, config, players(), [Team(1, "Alpha"), Team(2, "Beta")], registry({"runner10"}))

    await manager.setup()

    volumes = {name: mul for name, mul in obs.volumes}
    assert volumes.get("FM Hundo Media - Runner Ten") == 0.5
    assert volumes.get("FM Hundo Media - Runner Eleven") == 0.5
    assert volumes.get("FM Hundo Media - Runner Twenty") == 0.5
