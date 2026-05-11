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

