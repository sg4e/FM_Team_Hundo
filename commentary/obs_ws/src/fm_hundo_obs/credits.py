from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import logging
from pathlib import Path
from typing import Any

import yaml

from .config import AppConfig
from .models import CreditsCountRow, CreditsData, CreditsTeam

LOGGER = logging.getLogger(__name__)

TOP_LIST_LIMIT = 5
TOP_LIST_MAX_WITH_TIES = 7


class CreditsConfigError(ValueError):
    pass


@dataclass(frozen=True)
class ProductionCredit:
    title: str
    names: tuple[str, ...]


@dataclass(frozen=True)
class BillingBlock:
    lines: tuple[str, ...]


@dataclass(frozen=True)
class CreditsSceneConfig:
    start_time: int | None = None
    scroll_pixels_per_second: float = 70.0
    section_gap_px: int = 96
    credit_gap_px: int = 28
    billing_gap_px: int = 320
    main_title_font_px: int = 72
    section_font_px: int = 48
    label_font_px: int = 34
    body_font_px: int = 32
    billing_font_px: int = 44
    credits: tuple[ProductionCredit, ...] = ()
    billing_blocks: tuple[BillingBlock, ...] = ()


def resolve_credits_config_path(config: AppConfig, app_config_path: Path) -> Path:
    raw_path = config.credits.config_path
    if raw_path:
        path = Path(raw_path)
        return path if path.is_absolute() else app_config_path.parent / path
    return app_config_path.parent / "credits_scene.yml"


def load_credits_scene_config(path: Path) -> CreditsSceneConfig:
    if not path.exists():
        raise CreditsConfigError(f"Credits config not found: {path}")
    with path.open("r", encoding="utf-8") as handle:
        loaded = yaml.safe_load(handle) or {}
    if not isinstance(loaded, dict):
        raise CreditsConfigError(f"{path} must contain a YAML mapping")

    allowed = {
        "start_time",
        "scroll_pixels_per_second",
        "section_gap_px",
        "credit_gap_px",
        "billing_gap_px",
        "main_title_font_px",
        "section_font_px",
        "label_font_px",
        "body_font_px",
        "billing_font_px",
        "credits",
        "billing_blocks",
    }
    unknown = sorted(set(loaded) - allowed)
    if unknown:
        raise CreditsConfigError(f"Unknown credits config field(s): {', '.join(unknown)}")

    start_time = _optional_positive_int(loaded.get("start_time"), "start_time")
    if start_time is not None and start_time > int(datetime.now(timezone.utc).timestamp()):
        raise CreditsConfigError("start_time cannot be in the future")

    return CreditsSceneConfig(
        start_time=start_time,
        scroll_pixels_per_second=_positive_float(loaded.get("scroll_pixels_per_second", 70.0), "scroll_pixels_per_second"),
        section_gap_px=_nonnegative_int(loaded.get("section_gap_px", 96), "section_gap_px"),
        credit_gap_px=_nonnegative_int(loaded.get("credit_gap_px", 28), "credit_gap_px"),
        billing_gap_px=_nonnegative_int(loaded.get("billing_gap_px", 320), "billing_gap_px"),
        main_title_font_px=_positive_int(loaded.get("main_title_font_px", 72), "main_title_font_px"),
        section_font_px=_positive_int(loaded.get("section_font_px", 48), "section_font_px"),
        label_font_px=_positive_int(loaded.get("label_font_px", 34), "label_font_px"),
        body_font_px=_positive_int(loaded.get("body_font_px", 32), "body_font_px"),
        billing_font_px=_positive_int(loaded.get("billing_font_px", 44), "billing_font_px"),
        credits=tuple(_production_credit(item, index) for index, item in enumerate(loaded.get("credits") or ())),
        billing_blocks=tuple(_billing_block(item, index) for index, item in enumerate(loaded.get("billing_blocks") or ())),
    )


def build_credits_payload(
    data: CreditsData,
    scene_config: CreditsSceneConfig,
    card_names: dict[int, str],
    duelist_names: dict[int, str],
) -> dict[str, Any]:
    blocks: list[dict[str, Any]] = []
    team_stats = {stats.team_id: stats for stats in data.team_stats}
    teams = _ordered_teams(data.teams)

    blocks.append(_block("main-title", "FM Team Hundo"))
    blocks.append(_block("section", "Teams", gap=scene_config.section_gap_px))
    for team in teams:
        blocks.append(
            _block(
                "team-roster",
                _team_name_with_duration(team, scene_config),
                [player.name for player in sorted(team.players, key=lambda player: player.name.lower())],
                gap=scene_config.credit_gap_px,
            )
        )

    blocks.append(_block("section", "All-Team Stats", gap=scene_config.section_gap_px))
    blocks.append(
        _block(
            "stat-lines",
            lines=[
                f"Total drops: {data.all_teams.total_drops}",
                f"Total fusions: {data.all_teams.total_fusions}",
                f"Total rituals: {data.all_teams.total_rituals}",
                f"Twin-Headed Thunder Dragon fusions: {data.all_teams.twin_headed_thunder_dragon_fusions}",
            ],
            gap=scene_config.credit_gap_px,
        )
    )

    for team in teams:
        stats = team_stats.get(team.id)
        if stats is None:
            continue
        blocks.append(_block("section", f"{_team_name_with_duration(team, scene_config)} Stats", gap=scene_config.section_gap_px))
        _append_frequency_block(
            blocks,
            "Most Common Drops",
            stats.drop_card_counts,
            lambda card_id: _name_for(card_names, card_id, "Card"),
            scene_config,
        )
        _append_frequency_block(
            blocks,
            "Top Fusions Besides Twin-Headed Thunder Dragon",
            stats.fusion_card_counts,
            lambda card_id: _name_for(card_names, card_id, "Card"),
            scene_config,
        )
        blocks.append(
            _block(
                "stat-lines",
                lines=[
                    f"Heishin drops farmed: {stats.heishin_drops}",
                    f"Seto 3 drops farmed: {stats.seto3_drops}",
                ],
                gap=scene_config.credit_gap_px,
            )
        )
        _append_frequency_block(
            blocks,
            "Most Farmed Duelists",
            stats.duelist_drop_counts,
            lambda opponent_id: _name_for(duelist_names, opponent_id, "Duelist"),
            scene_config,
        )

    blocks.append(_block("section", "Restream Production Credits", gap=scene_config.section_gap_px))
    for credit in scene_config.credits:
        blocks.append(_block("credit", credit.title, credit.names, gap=scene_config.credit_gap_px))

    for index, billing in enumerate(scene_config.billing_blocks):
        blocks.append(
            _block(
                "billing",
                lines=billing.lines,
                gap=scene_config.billing_gap_px,
                final=index == len(scene_config.billing_blocks) - 1,
            )
        )

    if blocks:
        blocks[-1]["final"] = True

    return {
        "settings": {
            "scrollPixelsPerSecond": scene_config.scroll_pixels_per_second,
            "mainTitleFontPx": scene_config.main_title_font_px,
            "sectionFontPx": scene_config.section_font_px,
            "labelFontPx": scene_config.label_font_px,
            "bodyFontPx": scene_config.body_font_px,
            "billingFontPx": scene_config.billing_font_px,
        },
        "blocks": blocks,
    }


def _append_frequency_block(
    blocks: list[dict[str, Any]],
    title: str,
    rows: tuple[CreditsCountRow, ...],
    name_for_id,
    scene_config: CreditsSceneConfig,
) -> None:
    display_rows, overflow = _top_rows(rows)
    if not display_rows:
        return
    lines = [f"{name_for_id(row.id)} ({row.count})" for row in display_rows]
    if overflow:
        lines.append("And Others")
    blocks.append(_block("stat-group", title, lines, gap=scene_config.credit_gap_px))


def _top_rows(rows: tuple[CreditsCountRow, ...]) -> tuple[list[CreditsCountRow], bool]:
    sorted_rows = sorted(rows, key=lambda row: (-row.count, row.id))
    if len(sorted_rows) <= TOP_LIST_LIMIT:
        return sorted_rows, False
    cutoff_count = sorted_rows[TOP_LIST_LIMIT - 1].count
    tied_rows = [row for row in sorted_rows if row.count >= cutoff_count]
    return tied_rows[:TOP_LIST_MAX_WITH_TIES], len(tied_rows) > TOP_LIST_MAX_WITH_TIES


def _ordered_teams(teams: tuple[CreditsTeam, ...]) -> list[CreditsTeam]:
    far_future = datetime.max.replace(tzinfo=timezone.utc)

    def key(team: CreditsTeam):
        completion = team.completion_time
        if completion is not None and completion.tzinfo is None:
            completion = completion.replace(tzinfo=timezone.utc)
        if team.completed and completion is not None:
            return (0, completion, team.name.lower())
        return (1, far_future, team.name.lower())

    return sorted(teams, key=key)


def _team_name_with_duration(team: CreditsTeam, scene_config: CreditsSceneConfig) -> str:
    if not scene_config.start_time or not team.completed or team.completion_time is None:
        return team.name
    completion = team.completion_time
    if completion.tzinfo is None:
        completion = completion.replace(tzinfo=timezone.utc)
    start = datetime.fromtimestamp(scene_config.start_time, timezone.utc)
    duration_seconds = int((completion - start).total_seconds())
    if duration_seconds < 0:
        raise CreditsConfigError(f"start_time is after completion time for team {team.name}")
    hours = duration_seconds // 3600
    minutes = (duration_seconds % 3600) // 60
    seconds = duration_seconds % 60
    return f"{team.name} ({hours:02d}:{minutes:02d}:{seconds:02d})"


def _name_for(names: dict[int, str], item_id: int, prefix: str) -> str:
    name = names.get(item_id)
    if name is None:
        LOGGER.warning("No %s name found for id %s", prefix.lower(), item_id)
        return f"{prefix} {item_id}"
    return name


def _block(
    kind: str,
    text: str | None = None,
    lines: tuple[str, ...] | list[str] = (),
    *,
    gap: int | None = None,
    final: bool = False,
) -> dict[str, Any]:
    block: dict[str, Any] = {"type": kind, "lines": list(lines)}
    if text is not None:
        block["text"] = text
    if gap is not None:
        block["gapBeforePx"] = gap
    if final:
        block["final"] = True
    return block


def _production_credit(item: Any, index: int) -> ProductionCredit:
    if not isinstance(item, dict):
        raise CreditsConfigError(f"credits[{index}] must be a mapping")
    unknown = sorted(set(item) - {"title", "names"})
    if unknown:
        raise CreditsConfigError(f"credits[{index}] has unknown field(s): {', '.join(unknown)}")
    title = item.get("title")
    names = item.get("names")
    if not isinstance(title, str) or not title.strip():
        raise CreditsConfigError(f"credits[{index}].title must be a non-empty string")
    if isinstance(names, str):
        parsed_names = (names,)
    elif isinstance(names, list) and all(isinstance(name, str) for name in names):
        parsed_names = tuple(names)
    else:
        raise CreditsConfigError(f"credits[{index}].names must be a string or list of strings")
    return ProductionCredit(title=title, names=parsed_names)


def _billing_block(item: Any, index: int) -> BillingBlock:
    if isinstance(item, str):
        return BillingBlock(lines=tuple(item.splitlines() or [""]))
    if isinstance(item, list) and all(isinstance(line, str) for line in item):
        return BillingBlock(lines=tuple(item))
    raise CreditsConfigError(f"billing_blocks[{index}] must be a string or list of strings")


def _optional_positive_int(value: Any, field: str) -> int | None:
    if value is None:
        return None
    return _positive_int(value, field)


def _positive_int(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise CreditsConfigError(f"{field} must be a positive integer")
    return value


def _nonnegative_int(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise CreditsConfigError(f"{field} must be a non-negative integer")
    return value


def _positive_float(value: Any, field: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
        raise CreditsConfigError(f"{field} must be a positive number")
    return float(value)
