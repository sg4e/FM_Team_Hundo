from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest
from typing import cast

from fm_hundo_obs.config import AppConfig
from fm_hundo_obs.credits import (
    CreditsConfigError,
    build_credits_payload,
    load_credits_scene_config,
    resolve_credits_config_path,
)
from fm_hundo_obs.main import Application
from fm_hundo_obs.models import (
    CreditsAllTeamStats,
    CreditsCountRow,
    CreditsData,
    CreditsPlayer,
    CreditsTeam,
    CreditsTeamStats,
)

from .fakes import FakeObs


START_TIME = 1
COMPLETION = datetime.fromtimestamp(3601, timezone.utc)


def sample_credits_data() -> CreditsData:
    return CreditsData(
        teams=(
            CreditsTeam(
                id=2,
                name="Beta",
                completed=False,
                completion_time=None,
                players=(CreditsPlayer(20, "Zed"),),
            ),
            CreditsTeam(
                id=1,
                name="Alpha",
                completed=True,
                completion_time=COMPLETION,
                players=(CreditsPlayer(11, "Bea"), CreditsPlayer(10, "Ada")),
            ),
        ),
        all_teams=CreditsAllTeamStats(
            total_drops=12,
            total_fusions=4,
            total_rituals=2,
            twin_headed_thunder_dragon_fusions=3,
        ),
        team_stats=(
            CreditsTeamStats(
                team_id=1,
                drop_card_counts=(
                    CreditsCountRow(1, 10),
                    CreditsCountRow(2, 9),
                    CreditsCountRow(3, 8),
                    CreditsCountRow(4, 7),
                    CreditsCountRow(5, 6),
                    CreditsCountRow(6, 6),
                    CreditsCountRow(7, 6),
                    CreditsCountRow(8, 6),
                ),
                fusion_card_counts=(CreditsCountRow(20, 3),),
                heishin_drops=2,
                seto3_drops=1,
                duelist_drop_counts=(CreditsCountRow(8, 4), CreditsCountRow(36, 3)),
            ),
            CreditsTeamStats(
                team_id=2,
                drop_card_counts=(),
                fusion_card_counts=(),
                heishin_drops=0,
                seto3_drops=0,
                duelist_drop_counts=(),
            ),
        ),
    )


def test_load_credits_scene_config_strict_and_resolves_next_to_app_config(tmp_path):
    app_config_path = tmp_path / "config.yml"
    path = tmp_path / "credits_scene.yml"
    path.write_text(
        f"""
start_time: {START_TIME}
scroll_pixels_per_second: 80
credits:
  - title: Director
    names: [Alice, Bob]
billing_blocks:
  - |
    Final Line
""",
        encoding="utf-8",
    )

    assert resolve_credits_config_path(AppConfig(), app_config_path) == path
    config = load_credits_scene_config(path)

    assert config.start_time == START_TIME
    assert config.scroll_pixels_per_second == 80
    assert config.credits[0].names == ("Alice", "Bob")
    assert config.billing_blocks[0].lines == ("Final Line",)


def test_load_credits_scene_config_rejects_unknown_fields(tmp_path):
    path = tmp_path / "credits_scene.yml"
    path.write_text("surprise: true\n", encoding="utf-8")

    with pytest.raises(CreditsConfigError, match="Unknown"):
        load_credits_scene_config(path)


def test_build_credits_payload_orders_sections_and_trims_tied_top_lists():
    config = load_credits_scene_config_from_text(
        f"""
start_time: {START_TIME}
credits:
  - title: Director
    names: [Producer One]
billing_blocks:
  - |
    Thanks
"""
    )

    payload = build_credits_payload(
        sample_credits_data(),
        config,
        {index: f"Card Name {index}" for index in range(1, 21)},
        {8: "Heishin", 36: "Seto 3"},
    )

    texts = [block.get("text") for block in payload["blocks"]]
    assert texts[:3] == ["FM Team Hundo", "Teams", "Alpha (01:00:00)"]
    assert "All-Team Stats" in texts
    assert "Alpha (01:00:00) Stats" in texts
    drop_block = next(block for block in payload["blocks"] if block.get("text") == "Most Common Drops")
    assert drop_block["lines"] == [
        "Card Name 1 (10)",
        "Card Name 2 (9)",
        "Card Name 3 (8)",
        "Card Name 4 (7)",
        "Card Name 5 (6)",
        "Card Name 6 (6)",
        "Card Name 7 (6)",
        "And Others",
    ]
    assert payload["blocks"][-1]["final"] is True


def test_build_credits_payload_uses_id_fallback_for_missing_names():
    config = load_credits_scene_config_from_text(f"start_time: {START_TIME}\n")

    payload = build_credits_payload(sample_credits_data(), config, {}, {})

    drop_block = next(block for block in payload["blocks"] if block.get("text") == "Most Common Drops")
    duelist_block = next(block for block in payload["blocks"] if block.get("text") == "Most Farmed Duelists")
    assert drop_block["lines"][0] == "Card 1 (10)"
    assert duelist_block["lines"][0] == "Duelist 8 (4)"


def test_credits_browser_pins_final_block_while_roll_continues():
    html = (Path(__file__).parents[1] / "src" / "fm_hundo_obs" / "static" / "credits.html").read_text(encoding="utf-8")

    assert ".pinned-final" in html
    assert "function pinFinalBlock(final)" in html
    assert "final.style.visibility = \"hidden\"" in html
    assert "continueRollPastFinal(endY, pixelsPerSecond, final.offsetTop)" in html
    assert "document.querySelectorAll(\".pinned-final\").forEach(element => element.remove())" in html


@pytest.mark.asyncio
async def test_credits_command_fetches_payload_switches_scene_and_sends_to_browser(tmp_path, monkeypatch):
    config_path = tmp_path / "config.yml"
    (tmp_path / "credits_scene.yml").write_text(f"start_time: {START_TIME}\n", encoding="utf-8")
    app = Application(AppConfig(), config_path=config_path, simulate_mediamtx=True)
    app.api = FakeCreditsApi(sample_credits_data())  # type: ignore[assignment]
    app.obs = FakeObs(current_scene="Main")  # type: ignore[assignment]
    app.overlay_server = FakeCreditsOverlay()  # type: ignore[assignment]
    app.scheduler = FakeScheduler()  # type: ignore[assignment]
    app.duelist_names = {8: "Heishin", 36: "Seto 3"}
    monkeypatch.setattr("fm_hundo_obs.main.load_card_names", lambda _path: {1: "Blue-eyes White Dragon", 20: "Fusion Card"})

    await app._handle_credits()

    obs = cast(FakeObs, app.obs)
    scheduler = cast(FakeScheduler, app.scheduler)
    overlay_server = cast(FakeCreditsOverlay, app.overlay_server)

    assert obs.current_scene == "FM Hundo - Credits"
    assert scheduler.cancelled is True
    assert obs.refreshed_browser_sources == ["FM Hundo Credits Browser"]
    assert overlay_server.payloads
    assert overlay_server.payloads[0]["blocks"][0]["text"] == "FM Team Hundo"


@pytest.mark.asyncio
async def test_credits_command_does_not_switch_when_config_missing(tmp_path):
    app = Application(AppConfig(), config_path=tmp_path / "config.yml", simulate_mediamtx=True)
    app.api = FakeCreditsApi(sample_credits_data())  # type: ignore[assignment]
    app.obs = FakeObs(current_scene="Main")  # type: ignore[assignment]
    app.overlay_server = FakeCreditsOverlay()  # type: ignore[assignment]

    await app._handle_credits()

    obs = cast(FakeObs, app.obs)
    overlay_server = cast(FakeCreditsOverlay, app.overlay_server)
    assert obs.current_scene == "Main"
    assert not overlay_server.payloads


def load_credits_scene_config_from_text(text: str):
    import tempfile
    from pathlib import Path

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "credits_scene.yml"
        path.write_text(text, encoding="utf-8")
        return load_credits_scene_config(path)


class FakeCreditsApi:
    def __init__(self, data: CreditsData) -> None:
        self.data = data

    async def get_credits(self) -> CreditsData:
        return self.data


class FakeCreditsOverlay:
    credits_connected_clients = 1

    def __init__(self) -> None:
        self.payloads: list[dict] = []

    async def send_credits(self, payload: dict) -> bool:
        self.payloads.append(payload)
        return True


class FakeScheduler:
    def __init__(self) -> None:
        self.cancelled = False

    def cancel_active_window(self) -> None:
        self.cancelled = True
