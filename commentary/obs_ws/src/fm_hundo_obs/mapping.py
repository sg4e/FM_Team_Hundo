from __future__ import annotations

import json
from pathlib import Path

from .models import Player


def load_duelist_names(path: Path) -> dict[int, str]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    return {int(item["duelistId"]): str(item["duelist"]) for item in data}


class NameResolver:
    def __init__(self, players: list[Player], duelists: dict[int, str]) -> None:
        self._players = {player.id: player for player in players}
        self._duelists = duelists

    def player_name(self, player_id: int) -> str:
        player = self._players.get(player_id)
        return player.name if player else f"Player {player_id}"

    def opponent_name(self, opponent_id: int) -> str:
        return self._duelists.get(opponent_id, f"Opponent {opponent_id}")

