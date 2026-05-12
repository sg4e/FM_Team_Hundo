from __future__ import annotations

from dataclasses import dataclass
import zlib

from .models import Player, Team


SIMULATION_TEAM_ID = 1
SIMULATION_TEAM = Team(SIMULATION_TEAM_ID, "Simulation")
SIMULATION_ID_BASE = 9_000_000_000


@dataclass(frozen=True)
class SimulationRoster:
    players: list[Player]
    teams: list[Team]
    path_to_player_id: dict[str, int]

    def player_id_for_path(self, path: str) -> int | None:
        return self.path_to_player_id.get(path)


def build_simulation_roster(paths: set[str]) -> SimulationRoster:
    sorted_paths = sorted(paths, key=str.lower)
    players = [
        Player(
            id=simulated_player_id(path),
            twitch_id=path,
            name=path,
            alt_account=None,
            team_id=SIMULATION_TEAM_ID,
        )
        for path in sorted_paths
    ]
    return SimulationRoster(
        players=players,
        teams=[SIMULATION_TEAM],
        path_to_player_id={player.twitch_id or player.name: player.id for player in players},
    )


def simulated_player_id(path: str) -> int:
    return SIMULATION_ID_BASE + zlib.crc32(path.encode("utf-8"))

