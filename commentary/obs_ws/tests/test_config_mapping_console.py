from __future__ import annotations

import json

import pytest

from fm_hundo_obs.config import (
    AppConfig,
    ObsAudioFilterSpec,
    ObsConfig,
    PortraitsConfig,
    StreamAudioFiltersConfig,
    TwitchConfig,
    load_config,
)
from fm_hundo_obs.main import _stream_filter_sidechain_sources, _validate_config
from fm_hundo_obs.console import CommandType, parse_command, parse_on_off
from fm_hundo_obs.mapping import NameResolver, load_duelist_names
from fm_hundo_obs.models import ALERT_LABELS, MessageType, Player, Team


def test_load_config_and_env_override(tmp_path, monkeypatch):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        """
obs:
  password: yaml-secret
  all_managed_master_scene: Prod Global
  stream_layout_master_scene: Prod Stream Layout
player_scenes:
  10: Player Ten
group_scenes:
  - scene: Group
    interval_seconds: 120
    audio_sources: [A, B]
""",
        encoding="utf-8",
    )
    monkeypatch.setenv("OBS_WS_PASSWORD", "env-secret")

    config = load_config(config_path)

    assert config.obs.password == "env-secret"
    assert config.obs.all_managed_master_scene == "Prod Global"
    assert config.obs.stream_layout_master_scene == "Prod Stream Layout"
    assert config.obs.manual_background_scene == "Manual Background"
    assert config.player_scenes == {10: "Player Ten"}
    assert config.group_scenes[0].scene == "Group"
    assert config.group_scenes[0].interval_seconds == 120
    assert config.group_scenes[0].audio_sources == ("A", "B")


def test_load_config_stream_audio_filters(tmp_path):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        """
obs:
  stream_audio_filters:
    enabled: true
    sync_settings: false
    filters:
      - name: Leveling Compressor
        kind: compressor_filter
        settings:
          ratio: 4.0
          threshold: -18.0
      - name: Commentary Duck
        kind: compressor_filter
        enabled: false
        settings:
          sidechain_source: Production Commentary Bus
""",
        encoding="utf-8",
    )

    config = load_config(config_path)

    assert config.obs.stream_audio_filters.enabled is True
    assert config.obs.stream_audio_filters.sync_settings is False
    assert [filter_spec.name for filter_spec in config.obs.stream_audio_filters.filters] == [
        "Leveling Compressor",
        "Commentary Duck",
    ]
    assert config.obs.stream_audio_filters.filters[0].kind == "compressor_filter"
    assert config.obs.stream_audio_filters.filters[0].settings == {"ratio": 4.0, "threshold": -18.0}
    assert config.obs.stream_audio_filters.filters[1].enabled is False
    assert config.obs.stream_audio_filters.filters[1].settings == {
        "sidechain_source": "Production Commentary Bus"
    }


def test_stream_filter_sidechain_sources_are_collected_for_obs_validation():
    config = AppConfig(
        obs=ObsConfig(
            stream_audio_filters=StreamAudioFiltersConfig(
                enabled=True,
                filters=(
                    ObsAudioFilterSpec(
                        name="Duck",
                        kind="compressor_filter",
                        settings={"sidechain_source": "Production Commentary Bus"},
                    ),
                    ObsAudioFilterSpec(
                        name="Duplicate Duck",
                        kind="compressor_filter",
                        settings={"sidechain_source": "Production Commentary Bus"},
                    ),
                    ObsAudioFilterSpec(
                        name="No Sidechain",
                        kind="compressor_filter",
                        settings={"sidechain_source": "none"},
                    ),
                ),
            )
        )
    )

    assert _stream_filter_sidechain_sources(config) == ["Production Commentary Bus"]


def test_load_config_rejects_duplicate_stream_audio_filter_names(tmp_path):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        """
obs:
  stream_audio_filters:
    filters:
      - name: Compressor
        kind: compressor_filter
      - name: Compressor
        kind: limiter_filter
""",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="unique names"):
        load_config(config_path)


def test_master_scene_config_defaults_disabled(tmp_path):
    config_path = tmp_path / "config.yml"
    config_path.write_text("obs:\n  password: secret\n", encoding="utf-8")

    config = load_config(config_path)

    assert config.obs.all_managed_master_scene is None
    assert config.obs.stream_layout_master_scene is None
    assert config.obs.manual_background_scene == "Manual Background"


def test_name_resolver_and_duelist_file(tmp_path):
    path = tmp_path / "duelistinfo.json"
    path.write_text(json.dumps([{"duelistId": 5, "duelist": "Villager2"}]), encoding="utf-8")
    resolver = NameResolver(
        [Player(id=10, twitch_id="tw", name="Runner", alt_account=None, team_id=1)],
        load_duelist_names(path),
        [Team(1, "Alpha")],
    )

    assert resolver.player_name(10) == "Runner"
    assert resolver.player_name(99) == "Player 99"
    assert resolver.team_name(1) == "Alpha"
    assert resolver.team_name(99) is None
    assert resolver.player_team_id(10) == 1
    assert resolver.player_team_id(99) is None
    assert resolver.intro_player_name(10, 1) == "Alpha - Runner"
    assert resolver.intro_player_name(10, 99) == "Runner"
    assert resolver.opponent_name(5) == "Villager2"
    assert resolver.opponent_name(42) == "Opponent 42"


def test_twitch_id_for():
    resolver = NameResolver(
        [Player(id=10, twitch_id="twitch_name", name="Runner", alt_account=None, team_id=1)],
        {},
        [Team(1, "Alpha")],
    )
    assert resolver.twitch_id_for(10) == "twitch_name"
    assert resolver.twitch_id_for(999) is None


def test_alert_labels():
    assert ALERT_LABELS[MessageType.DROP] == "Big Drop Alert"
    assert ALERT_LABELS[MessageType.RITUAL] == "New Ritual Alert"
    assert ALERT_LABELS[MessageType.FUSE] == "New Fusion Alert"


def test_parse_commands():
    assert parse_command("status").type == CommandType.STATUS
    assert parse_command("quit").type == CommandType.QUIT
    assert parse_command("credits").type == CommandType.CREDITS
    assert parse_command("test 10 drop 5 --force").force is True
    assert parse_command("test 10 drop 5 --force").args == ("10", "drop", "5")
    assert parse_on_off(("on",)) is True
    assert parse_on_off(("off",)) is False
    assert parse_on_off(("maybe",)) is None


def test_timing_config_includes_acquisition_delay(tmp_path):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        "timing:\n  acquisition_window_seconds: 30\n  acquisition_delay_seconds: 5\n",
        encoding="utf-8",
    )

    config = load_config(config_path)

    assert config.timing.acquisition_delay_seconds == 5


def test_timing_config_rejects_acquisition_delay_longer_than_window(tmp_path):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        "timing:\n  acquisition_window_seconds: 5\n  acquisition_delay_seconds: 6\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="acquisition_delay_seconds cannot exceed"):
        load_config(config_path)


def test_timing_config_validates_banner_against_delayed_window_end(tmp_path):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        "timing:\n  acquisition_window_seconds: 10\n  acquisition_delay_seconds: 4\n"
        "  banner_end_buffer_seconds: 1\n  banner_total_seconds: 6\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="banner_total_seconds exceeds"):
        load_config(config_path)


def test_timing_config_includes_intro_delay(tmp_path):
    """TimingConfig loads intro_delay_seconds from YAML."""
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        "timing:\n  intro_delay_seconds: 1.5\n",
        encoding="utf-8",
    )
    config = load_config(config_path)
    assert config.timing.intro_delay_seconds == 1.5


def test_timing_config_defaults_intro_delay_to_zero(tmp_path):
    """Default intro_delay_seconds is 0 (no delay)."""
    config_path = tmp_path / "config.yml"
    config_path.write_text("obs:\n  password: secret\n", encoding="utf-8")
    config = load_config(config_path)
    assert config.timing.intro_delay_seconds == 0.0
    assert config.timing.acquisition_delay_seconds == 0.0


def test_portraits_config_loads_from_yaml(tmp_path):
    """PortraitsConfig loads directory path from YAML."""
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        "portraits:\n  directory: 'some/path'\n",
        encoding="utf-8",
    )
    config = load_config(config_path)
    assert config.portraits.directory == "some/path"


def test_portraits_config_defaults_to_empty(tmp_path):
    """Default portraits.directory is empty string (no default path)."""
    config_path = tmp_path / "config.yml"
    config_path.write_text("obs:\n  password: secret\n", encoding="utf-8")
    config = load_config(config_path)
    assert config.portraits.directory == ""


def test_twitch_config_loads_from_yaml(tmp_path):
    """TwitchConfig loads client_id and client_secret from YAML."""
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        "twitch:\n  client_id: 'my_id'\n  client_secret: 'my_secret'\n",
        encoding="utf-8",
    )
    config = load_config(config_path)
    assert config.twitch.client_id == "my_id"
    assert config.twitch.client_secret == "my_secret"


def test_validate_config_raises_on_missing_portraits_dir(tmp_path):
    """_validate_config raises ValueError when portraits directory is missing."""
    config_path = tmp_path / "config.yml"
    config = AppConfig(portraits=PortraitsConfig(directory=str(tmp_path / "nonexistent")))
    with pytest.raises(ValueError, match="does not exist"):
        _validate_config(config, config_path)


def test_validate_config_raises_on_empty_portraits_dir(tmp_path):
    """_validate_config raises ValueError when portraits directory has no duelist_*.png."""
    config_path = tmp_path / "config.yml"
    empty_dir = tmp_path / "empty_portraits"
    empty_dir.mkdir()
    config = AppConfig(portraits=PortraitsConfig(directory=str(empty_dir)))
    with pytest.raises(ValueError, match="contains no duelist"):
        _validate_config(config, config_path)


def test_validate_config_passes_with_valid_portraits_dir(tmp_path):
    """_validate_config passes when portraits directory has duelist_*.png."""
    config_path = tmp_path / "config.yml"
    portraits_dir = tmp_path / "portraits"
    portraits_dir.mkdir()
    (portraits_dir / "duelist_001.png").write_bytes(b"fake")
    audio_file = tmp_path / "alert.wav"
    audio_file.write_bytes(b"fake")
    config = AppConfig(
        obs=ObsConfig(alert_audio_path=str(audio_file), stream_volume_mul=0.5),
        portraits=PortraitsConfig(directory=str(portraits_dir)),
        twitch=TwitchConfig(client_id="test_id", client_secret="test_secret"),
    )
    _validate_config(config, config_path)


def test_validate_config_raises_on_missing_stream_volume_mul(tmp_path):
    """_validate_config raises ValueError when stream_volume_mul is <= 0."""
    config_path = tmp_path / "config.yml"
    portraits_dir = tmp_path / "portraits"
    portraits_dir.mkdir()
    (portraits_dir / "duelist_001.png").write_bytes(b"fake")
    audio_file = tmp_path / "alert.wav"
    audio_file.write_bytes(b"fake")

    config = AppConfig(
        obs=ObsConfig(alert_audio_path=str(audio_file), stream_volume_mul=0.0),
        portraits=PortraitsConfig(directory=str(portraits_dir)),
    )
    with pytest.raises(ValueError, match="stream_volume_mul"):
        _validate_config(config, config_path)


def test_validate_config_raises_on_missing_alert_audio_path(tmp_path):
    """_validate_config raises ValueError when alert_audio_path is not set."""
    config_path = tmp_path / "config.yml"
    portraits_dir = tmp_path / "portraits"
    portraits_dir.mkdir()
    (portraits_dir / "duelist_001.png").write_bytes(b"fake")
    config = AppConfig(portraits=PortraitsConfig(directory=str(portraits_dir)))
    with pytest.raises(ValueError, match="alert_audio_path"):
        _validate_config(config, config_path)


def test_validate_config_raises_on_missing_twitch_creds_in_production(tmp_path):
    """_validate_config raises ValueError when Twitch creds are missing in production."""
    config_path = tmp_path / "config.yml"
    portraits_dir = tmp_path / "portraits"
    portraits_dir.mkdir()
    (portraits_dir / "duelist_001.png").write_bytes(b"fake")
    audio_file = tmp_path / "alert.wav"
    audio_file.write_bytes(b"fake")
    config = AppConfig(
        obs=ObsConfig(alert_audio_path=str(audio_file), stream_volume_mul=0.5),
        portraits=PortraitsConfig(directory=str(portraits_dir)),
        twitch=TwitchConfig(),
    )
    with pytest.raises(ValueError, match="twitch.client_id"):
        _validate_config(config, config_path)


def test_validate_config_requires_twitch_creds_in_simulation(tmp_path):
    """Simulation requires Twitch credentials for feature parity with production."""
    config_path = tmp_path / "config.yml"
    portraits_dir = tmp_path / "portraits"
    portraits_dir.mkdir()
    (portraits_dir / "duelist_001.png").write_bytes(b"fake")
    audio_file = tmp_path / "alert.wav"
    audio_file.write_bytes(b"fake")
    config = AppConfig(
        obs=ObsConfig(alert_audio_path=str(audio_file), stream_volume_mul=0.5),
        portraits=PortraitsConfig(directory=str(portraits_dir)),
        twitch=TwitchConfig(),
    )
    with pytest.raises(ValueError, match="twitch.client_id"):
        _validate_config(config, config_path)
