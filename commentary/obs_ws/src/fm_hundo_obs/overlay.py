from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import TYPE_CHECKING, Any

from aiohttp import web

from .config import OverlayConfig, PortraitsConfig
from .models import MessageType

if TYPE_CHECKING:
    from .twitch_cache import TwitchProfileCache

LOGGER = logging.getLogger(__name__)


class OverlayServer:
    def __init__(
        self,
        config: OverlayConfig,
        static_dir: Path,
        portraits_config: PortraitsConfig | None = None,
        profile_cache: TwitchProfileCache | None = None,
    ) -> None:
        self.config = config
        self.static_dir = static_dir
        self.portraits_config = portraits_config
        self.profile_cache = profile_cache
        self._clients: set[web.WebSocketResponse] = set()
        self._credits_clients: set[web.WebSocketResponse] = set()
        self._connected_event = asyncio.Event()
        self._credits_connected_event = asyncio.Event()
        self._runner: web.AppRunner | None = None
        self._latest_credits_payload: dict[str, Any] | None = None

    @property
    def connected_clients(self) -> int:
        return len(self._clients)

    @property
    def credits_connected_clients(self) -> int:
        return len(self._credits_clients)

    async def start(self) -> None:
        app = web.Application()
        app.router.add_get("/overlay", self._overlay)
        app.router.add_get("/events", self._events)
        app.router.add_get("/credits", self._credits)
        app.router.add_get("/credits/events", self._credits_events)
        app.router.add_get("/profile/{player_id}", self._profile_image)
        app.router.add_get("/duelist/{opponent_id}", self._duelist_portrait)
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.config.host, self.config.port)
        await site.start()
        LOGGER.info("Overlay server listening at %s", self.config.url)

    async def stop(self) -> None:
        for client in tuple(self._clients):
            await client.close()
        self._clients.clear()
        for client in tuple(self._credits_clients):
            await client.close()
        self._credits_clients.clear()
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

    async def wait_for_credits_client(self, timeout_seconds: float) -> None:
        if self._credits_clients:
            return
        try:
            await asyncio.wait_for(self._credits_connected_event.wait(), timeout=timeout_seconds)
        except TimeoutError as ex:
            raise OverlayClientTimeout(
                f"Credits Browser Source did not connect to {self.config.credits_url} within {timeout_seconds:g} seconds. "
                "Check that OBS has loaded the generated Credits Browser Source, that its URL is correct, and try refreshing "
                "the source cache if OBS shows a blank browser."
            ) from ex

    async def send(self, event_type: str, payload: dict[str, Any]) -> bool:
        return await self._send(self._clients, event_type, payload)

    async def send_credits(self, payload: dict[str, Any]) -> bool:
        self._latest_credits_payload = payload
        return await self._send(self._credits_clients, "credits", payload)

    async def _send(self, clients: set[web.WebSocketResponse], event_type: str, payload: dict[str, Any]) -> bool:
        if not clients:
            LOGGER.warning("No overlay clients connected; dropped overlay event %s", event_type)
            return False
        message = json.dumps({"type": event_type, "payload": payload})
        stale: list[web.WebSocketResponse] = []
        for client in clients:
            if client.closed:
                stale.append(client)
                continue
            await client.send_str(message)
        for client in stale:
            clients.discard(client)
        return bool(clients)

    async def _overlay(self, _: web.Request) -> web.Response:
        html = (self.static_dir / "overlay.html").read_text(encoding="utf-8")
        html = html.replace("__CANVAS_WIDTH__", str(self.config.canvas_width))
        html = html.replace("__CANVAS_HEIGHT__", str(self.config.canvas_height))
        html = html.replace("__INTRO_BOTTOM__", str(self.config.intro_bottom_px) + "px")
        return web.Response(text=html, content_type="text/html")

    async def _credits(self, _: web.Request) -> web.Response:
        html = (self.static_dir / "credits.html").read_text(encoding="utf-8")
        html = html.replace("__CANVAS_WIDTH__", str(self.config.canvas_width))
        html = html.replace("__CANVAS_HEIGHT__", str(self.config.canvas_height))
        return web.Response(
            text=html,
            content_type="text/html",
            headers={
                "Cache-Control": "no-store, max-age=0",
                "Pragma": "no-cache",
            },
        )

    async def _resolve_portraits_dir(self) -> Path | None:
        if self.portraits_config is None or not self.portraits_config.directory:
            return None
        return Path(self.portraits_config.directory)

    async def _profile_image(self, request: web.Request) -> web.Response:
        player_id = int(request.match_info["player_id"])
        portraits_dir = await self._resolve_portraits_dir()

        # Prefer a cached Twitch profile image.
        if self.profile_cache is not None:
            cached = self.profile_cache.get_image(player_id)
            if cached is not None:
                return web.Response(body=cached, content_type="image/png")

        # Fall back to duelist_000.png.
        if portraits_dir is not None:
            fallback = portraits_dir / "duelist_000.png"
            if fallback.exists():
                return web.Response(body=fallback.read_bytes(), content_type="image/png")

        return web.Response(status=404)

    async def _duelist_portrait(self, request: web.Request) -> web.Response:
        opponent_id = int(request.match_info["opponent_id"])
        portraits_dir = await self._resolve_portraits_dir()
        if portraits_dir is None:
            return web.Response(status=404)

        candidate = portraits_dir / f"duelist_{opponent_id:03d}.png"
        if candidate.exists():
            return web.Response(body=candidate.read_bytes(), content_type="image/png")

        # Fallback.
        fallback = portraits_dir / "duelist_000.png"
        if fallback.exists():
            return web.Response(body=fallback.read_bytes(), content_type="image/png")

        return web.Response(status=404)

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

    async def _credits_events(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=10)
        await ws.prepare(request)
        self._credits_clients.add(ws)
        self._credits_connected_event.set()
        if self._latest_credits_payload is not None:
            await ws.send_str(json.dumps({"type": "credits", "payload": self._latest_credits_payload}))
        LOGGER.info("Credits browser connected")
        try:
            async for _ in ws:
                pass
        finally:
            self._credits_clients.discard(ws)
            if not self._credits_clients:
                self._credits_connected_event.clear()
            LOGGER.info("Credits browser disconnected")
        return ws


class OverlayEvents:
    def __init__(self, server: OverlayServer) -> None:
        self.server = server

    async def banner(
        self,
        label: str,
        source: MessageType,
        duration_seconds: float,
        *,
        enter_seconds: float = 0.3,
        exit_seconds: float = 0.3,
    ) -> bool:
        return await self.server.send(
            "banner",
            {
                "label": label,
                "source": str(source),
                "durationSeconds": duration_seconds,
                "enterSeconds": enter_seconds,
                "exitSeconds": exit_seconds,
            },
        )

    async def intro(
        self,
        player_name: str,
        opponent_name: str,
        duration_seconds: float,
        *,
        player_id: int = 0,
        opponent_id: int = 0,
        use_twitch_profile: bool = True,
    ) -> bool:
        return await self.server.send(
            "intro",
            {
                "playerName": player_name,
                "opponentName": opponent_name,
                "durationSeconds": duration_seconds,
                "playerId": player_id,
                "opponentId": opponent_id,
                "useTwitchProfile": use_twitch_profile,
            },
        )


class OverlayClientTimeout(RuntimeError):
    pass
