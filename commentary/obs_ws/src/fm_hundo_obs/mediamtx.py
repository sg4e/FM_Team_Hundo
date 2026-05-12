from __future__ import annotations

from dataclasses import dataclass
import logging

from aiohttp import ClientSession

from .config import MediaMtxConfig
from .models import Player

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class StreamState:
    path: str
    active: bool


class MediaMtxClient:
    def __init__(self, config: MediaMtxConfig, session: ClientSession) -> None:
        self.config = config
        self.session = session

    async def active_paths(self) -> set[str]:
        async with self.session.get(f"{self.config.api_base_url.rstrip('/')}/v3/paths/list") as response:
            response.raise_for_status()
            payload = await response.json()
        return parse_active_paths(payload)

    def rtsp_url(self, path: str) -> str:
        return f"{self.config.rtsp_base_url.rstrip('/')}/{path}"


class StreamRegistry:
    def __init__(self, players: list[Player], mediamtx: MediaMtxClient) -> None:
        self.mediamtx = mediamtx
        self.paths_by_player_id = {
            player.id: player.twitch_id
            for player in players
            if player.twitch_id
        }
        self._active_paths: set[str] = set()

    async def refresh(self) -> bool:
        next_paths = await self.mediamtx.active_paths()
        changed = next_paths != self._active_paths
        self._active_paths = next_paths
        return changed

    def set_active_paths_for_tests(self, paths: set[str]) -> None:
        self._active_paths = set(paths)

    def path_for_player(self, player_id: int) -> str | None:
        return self.paths_by_player_id.get(player_id)

    def rtsp_url_for_player(self, player_id: int) -> str | None:
        path = self.path_for_player(player_id)
        return self.mediamtx.rtsp_url(path) if path else None

    def is_player_active(self, player_id: int) -> bool:
        path = self.path_for_player(player_id)
        return path in self._active_paths if path else False

    def active_player_ids(self) -> set[int]:
        return {
            player_id
            for player_id, path in self.paths_by_player_id.items()
            if path in self._active_paths
        }


def parse_active_paths(payload: dict) -> set[str]:
    paths: set[str] = set()
    for item in payload.get("items") or ():
        name = item.get("name")
        if not name:
            continue
        if _path_is_active(item):
            paths.add(str(name))
    return paths


def _path_is_active(item: dict) -> bool:
    if item.get("ready") is True:
        return True
    if item.get("ready") is False:
        return False
    source = item.get("source")
    if isinstance(source, dict):
        return bool(source) and source.get("type") not in {None, ""}
    if source:
        return True
    return bool(item.get("readers"))

