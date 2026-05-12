from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
import logging

from aiohttp import ClientSession, WSMsgType

from .models import LibraryUpdate, Player, Team

LOGGER = logging.getLogger(__name__)


class HundoApiClient:
    def __init__(self, base_url: str, session: ClientSession) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = session

    async def get_players(self) -> list[Player]:
        async with self.session.get(f"{self.base_url}/api/players", headers={"Accept": "application/json"}) as response:
            response.raise_for_status()
            data = await response.json()
        return [Player.from_json(item) for item in data]

    async def get_teams(self) -> list[Team]:
        async with self.session.get(f"{self.base_url}/api/teams", headers={"Accept": "application/json"}) as response:
            response.raise_for_status()
            data = await response.json()
        return [Team.from_json(item) for item in data]

    def team_firehose_url(self) -> str:
        if self.base_url.startswith("https://"):
            return "wss://" + self.base_url.removeprefix("https://") + "/firehose/team"
        if self.base_url.startswith("http://"):
            return "ws://" + self.base_url.removeprefix("http://") + "/firehose/team"
        raise ValueError(f"API base URL must start with http:// or https://: {self.base_url}")


class TeamFirehose:
    def __init__(
        self,
        url: str,
        session: ClientSession,
        on_connection: Callable[[bool], None] | None = None,
        reconnect_seconds: float = 2.0,
    ) -> None:
        self.url = url
        self.session = session
        self.on_connection = on_connection or (lambda _: None)
        self.reconnect_seconds = reconnect_seconds
        self._closed = False

    def close(self) -> None:
        self._closed = True

    async def updates(self) -> AsyncIterator[LibraryUpdate]:
        while not self._closed:
            try:
                async with self.session.ws_connect(self.url) as ws:
                    self.on_connection(True)
                    async for message in ws:
                        if self._closed:
                            break
                        if message.type == WSMsgType.TEXT:
                            yield LibraryUpdate.from_json(message.json())
                        elif message.type in {WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR}:
                            break
            except asyncio.CancelledError:
                raise
            except Exception:
                LOGGER.exception("Team firehose connection failed")
            self.on_connection(False)
            if not self._closed:
                await asyncio.sleep(self.reconnect_seconds)
