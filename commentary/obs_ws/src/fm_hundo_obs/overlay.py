from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Any

from aiohttp import web

from .config import OverlayConfig
from .models import MessageType

LOGGER = logging.getLogger(__name__)


class OverlayServer:
    def __init__(self, config: OverlayConfig, static_dir: Path) -> None:
        self.config = config
        self.static_dir = static_dir
        self._clients: set[web.WebSocketResponse] = set()
        self._connected_event = asyncio.Event()
        self._runner: web.AppRunner | None = None

    @property
    def connected_clients(self) -> int:
        return len(self._clients)

    async def start(self) -> None:
        app = web.Application()
        app.router.add_get("/overlay", self._overlay)
        app.router.add_get("/events", self._events)
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.config.host, self.config.port)
        await site.start()
        LOGGER.info("Overlay server listening at %s", self.config.url)

    async def stop(self) -> None:
        for client in tuple(self._clients):
            await client.close()
        self._clients.clear()
        if self._runner is not None:
            await self._runner.cleanup()

    async def wait_for_client(self, timeout_seconds: float) -> None:
        if self._clients:
            return
        try:
            await asyncio.wait_for(self._connected_event.wait(), timeout=timeout_seconds)
        except TimeoutError as ex:
            raise OverlayClientTimeout(
                f"Overlay Browser Source did not connect to {self.config.url} within {timeout_seconds:g} seconds. "
                "Check that OBS has loaded the generated Browser Source, that its URL is correct, and try refreshing "
                "the source cache if OBS shows a blank browser."
            ) from ex

    async def send(self, event_type: str, payload: dict[str, Any]) -> bool:
        if not self._clients:
            LOGGER.warning("No overlay clients connected; dropped overlay event %s", event_type)
            return False
        message = json.dumps({"type": event_type, "payload": payload})
        stale: list[web.WebSocketResponse] = []
        for client in self._clients:
            if client.closed:
                stale.append(client)
                continue
            await client.send_str(message)
        for client in stale:
            self._clients.discard(client)
        return bool(self._clients)

    async def _overlay(self, _: web.Request) -> web.Response:
        html = (self.static_dir / "overlay.html").read_text(encoding="utf-8")
        html = html.replace("__CANVAS_WIDTH__", str(self.config.canvas_width))
        html = html.replace("__CANVAS_HEIGHT__", str(self.config.canvas_height))
        return web.Response(text=html, content_type="text/html")

    async def _events(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=10)
        await ws.prepare(request)
        self._clients.add(ws)
        self._connected_event.set()
        LOGGER.info("Overlay browser connected")
        try:
            async for _ in ws:
                pass
        finally:
            self._clients.discard(ws)
            if not self._clients:
                self._connected_event.clear()
            LOGGER.info("Overlay browser disconnected")
        return ws


class OverlayEvents:
    def __init__(self, server: OverlayServer) -> None:
        self.server = server

    async def banner(self, label: str, source: MessageType, duration_seconds: float) -> bool:
        return await self.server.send(
            "banner",
            {"label": label, "source": str(source), "durationSeconds": duration_seconds},
        )

    async def intro(self, player_name: str, opponent_name: str, duration_seconds: float) -> bool:
        return await self.server.send(
            "intro",
            {
                "playerName": player_name,
                "opponentName": opponent_name,
                "durationSeconds": duration_seconds,
            },
        )


class OverlayClientTimeout(RuntimeError):
    pass
