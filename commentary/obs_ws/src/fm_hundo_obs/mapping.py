from __future__ import annotations

import json
from pathlib import Path

from .models import Player, Team


def load_duelist_names(path: Path) -> dict[int, str]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    return {int(item["duelistId"]): str(item["duelist"]) for item in data}


def load_card_names(path: Path) -> dict[int, str]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    return {int(item["cardId"]): str(item["cardName"]) for item in data}


class NameResolver:
    def __init__(self, players: list[Player], duelists: dict[int, str], teams: list[Team] | None = None) -> None:
        self._players: dict[int, Player] = {}
        self.update_players(players)
        self._teams: dict[int, Team] = {}
        self.update_teams(teams or [])
        self._duelists = duelists

    def update_players(self, players: list[Player]) -> None:
        self._players = {player.id: player for player in players}

    def update_teams(self, teams: list[Team]) -> None:
        self._teams = {team.id: team for team in teams}

    def player_name(self, player_id: int) -> str:
        player = self._players.get(player_id)
        return player.name if player else f"Player {player_id}"

    def team_name(self, team_id: int | None) -> str | None:
        if team_id is None:
            return None
        team = self._teams.get(team_id)
        return team.name if team else None

    def player_team_id(self, player_id: int) -> int | None:
        player = self._players.get(player_id)
        return player.team_id if player else None

    def intro_player_name(self, player_id: int, team_id: int | None) -> str:
        player_name = self.player_name(player_id)
        team_name = self.team_name(team_id)
        return f"{team_name} - {player_name}" if team_name else player_name

    def opponent_name(self, opponent_id: int) -> str:
        return self._duelists.get(opponent_id, f"Opponent {opponent_id}")
