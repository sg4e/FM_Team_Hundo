from __future__ import annotations

import json

from fm_hundo_obs.config import load_config
from fm_hundo_obs.console import CommandType, parse_command, parse_on_off
from fm_hundo_obs.mapping import NameResolver, load_duelist_names
from fm_hundo_obs.models import ALERT_LABELS, MessageType, Player


def test_load_config_and_env_override(tmp_path, monkeypatch):
    config_path = tmp_path / "config.yml"
    config_path.write_text(
        """
obs:
  password: yaml-secret
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
    assert config.player_scenes == {10: "Player Ten"}
    assert config.group_scenes[0].scene == "Group"
    assert config.group_scenes[0].interval_seconds == 120
    assert config.group_scenes[0].audio_sources == ("A", "B")


def test_name_resolver_and_duelist_file(tmp_path):
    path = tmp_path / "duelistinfo.json"
    path.write_text(json.dumps([{"duelistId": 5, "duelist": "Villager2"}]), encoding="utf-8")
    resolver = NameResolver(
        [Player(id=10, twitch_id="tw", name="Runner", alt_account=None, team_id=1)],
        load_duelist_names(path),
    )

    assert resolver.player_name(10) == "Runner"
    assert resolver.player_name(99) == "Player 99"
    assert resolver.opponent_name(5) == "Villager2"
    assert resolver.opponent_name(42) == "Opponent 42"


def test_alert_labels():
    assert ALERT_LABELS[MessageType.DROP] == "Big Drop Alert"
    assert ALERT_LABELS[MessageType.RITUAL] == "New Ritual Alert"
    assert ALERT_LABELS[MessageType.FUSE] == "New Fusion Alert"


def test_parse_commands():
    assert parse_command("status").type == CommandType.STATUS
    assert parse_command("quit").type == CommandType.QUIT
    assert parse_command("test 10 drop 5 --force").force is True
    assert parse_command("test 10 drop 5 --force").args == ("10", "drop", "5")
    assert parse_on_off(("on",)) is True
    assert parse_on_off(("off",)) is False
    assert parse_on_off(("maybe",)) is None

