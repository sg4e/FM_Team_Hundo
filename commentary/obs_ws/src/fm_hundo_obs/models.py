from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from enum import StrEnum
from typing import Any


class MessageType(StrEnum):
    DROP = "drop"
    FUSE = "fuse"
    RITUAL = "ritual"
    STARCHIPS = "starchips"


ALERT_LABELS = {
    MessageType.DROP: "Big Drop Alert",
    MessageType.FUSE: "New Fusion Alert",
    MessageType.RITUAL: "New Ritual Alert",
}


@dataclass(frozen=True)
class Player:
    id: int
    twitch_id: str | None
    name: str
    alt_account: str | None
    team_id: int

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> Player:
        return cls(
            id=int(data["id"]),
            twitch_id=data.get("twitchId"),
            name=str(data.get("name") or data["id"]),
            alt_account=data.get("altAccount"),
            team_id=int(data["teamId"]),
        )


@dataclass(frozen=True)
class Team:
    id: int
    name: str

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> Team:
        return cls(id=int(data["id"]), name=str(data["name"]))


@dataclass(frozen=True)
class CreditsCountRow:
    id: int
    count: int

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CreditsCountRow:
        return cls(id=int(data["id"]), count=int(data["count"]))


@dataclass(frozen=True)
class CreditsPlayer:
    id: int
    name: str

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CreditsPlayer:
        return cls(id=int(data["id"]), name=str(data.get("name") or data["id"]))


@dataclass(frozen=True)
class CreditsTeam:
    id: int
    name: str
    completed: bool
    completion_time: datetime | None
    players: tuple[CreditsPlayer, ...]

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CreditsTeam:
        raw_time = data.get("completionTime")
        completion_time = None
        if raw_time:
            completion_time = datetime.fromisoformat(str(raw_time).replace("Z", "+00:00"))
        return cls(
            id=int(data["id"]),
            name=str(data["name"]),
            completed=bool(data.get("completed", False)),
            completion_time=completion_time,
            players=tuple(CreditsPlayer.from_json(item) for item in data.get("players") or ()),
        )


@dataclass(frozen=True)
class CreditsAllTeamStats:
    total_drops: int
    total_fusions: int
    total_rituals: int
    twin_headed_thunder_dragon_fusions: int

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CreditsAllTeamStats:
        return cls(
            total_drops=int(data.get("totalDrops", 0)),
            total_fusions=int(data.get("totalFusions", 0)),
            total_rituals=int(data.get("totalRituals", 0)),
            twin_headed_thunder_dragon_fusions=int(data.get("twinHeadedThunderDragonFusions", 0)),
        )


@dataclass(frozen=True)
class CreditsTeamStats:
    team_id: int
    drop_card_counts: tuple[CreditsCountRow, ...]
    fusion_card_counts: tuple[CreditsCountRow, ...]
    heishin_drops: int
    seto3_drops: int
    duelist_drop_counts: tuple[CreditsCountRow, ...]

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CreditsTeamStats:
        return cls(
            team_id=int(data["teamId"]),
            drop_card_counts=tuple(CreditsCountRow.from_json(item) for item in data.get("dropCardCounts") or ()),
            fusion_card_counts=tuple(CreditsCountRow.from_json(item) for item in data.get("fusionCardCounts") or ()),
            heishin_drops=int(data.get("heishinDrops", 0)),
            seto3_drops=int(data.get("seto3Drops", 0)),
            duelist_drop_counts=tuple(CreditsCountRow.from_json(item) for item in data.get("duelistDropCounts") or ()),
        )


@dataclass(frozen=True)
class CreditsData:
    teams: tuple[CreditsTeam, ...]
    all_teams: CreditsAllTeamStats
    team_stats: tuple[CreditsTeamStats, ...]

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CreditsData:
        return cls(
            teams=tuple(CreditsTeam.from_json(item) for item in data.get("teams") or ()),
            all_teams=CreditsAllTeamStats.from_json(data.get("allTeams") or {}),
            team_stats=tuple(CreditsTeamStats.from_json(item) for item in data.get("teamStats") or ()),
        )


@dataclass(frozen=True)
class CardAcquisition:
    card_id: int
    acquisition_time: datetime | None
    source: MessageType
    player_id: int
    opponent_id: int

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> CardAcquisition:
        raw_time = data.get("acquisitionTime")
        parsed_time = None
        if raw_time:
            parsed_time = datetime.fromisoformat(str(raw_time).replace("Z", "+00:00"))
        return cls(
            card_id=int(data["cardId"]),
            acquisition_time=parsed_time,
            source=MessageType(str(data["source"])),
            player_id=int(data["playerId"]),
            opponent_id=int(data["opponentId"]),
        )

    @classmethod
    def test_event(cls, player_id: int, source: str, opponent_id: int) -> CardAcquisition:
        normalized = "fuse" if source == "fusion" else source
        return cls(
            card_id=0,
            acquisition_time=datetime.now(timezone.utc),
            source=MessageType(normalized),
            player_id=player_id,
            opponent_id=opponent_id,
        )

    @property
    def alert_label(self) -> str | None:
        return ALERT_LABELS.get(self.source)


@dataclass(frozen=True)
class LibraryUpdate:
    team_id: int
    new_acquisitions: tuple[CardAcquisition, ...]

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> LibraryUpdate:
        acquisitions = tuple(CardAcquisition.from_json(item) for item in data.get("newAcquisitions") or ())
        return cls(team_id=int(data["teamId"]), new_acquisitions=acquisitions)


@dataclass(frozen=True)
class AcquisitionContext:
    acquisition: CardAcquisition
    player_name: str
    opponent_name: str
    scene_name: str | None
    alert_label: str
    team_id: int | None = None
