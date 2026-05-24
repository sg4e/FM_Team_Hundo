from __future__ import annotations

from pathlib import Path
from unittest import mock

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from fm_hundo_obs.config import OverlayConfig, PortraitsConfig
from fm_hundo_obs.overlay import OverlayServer


@pytest.fixture
def portraits_dir(tmp_path: Path) -> Path:
    """Create a temporary portraits directory with known duelist images."""
    d = tmp_path / "portraits"
    d.mkdir()
    (d / "duelist_000.png").write_bytes(b"fallback_image_bytes")
    (d / "duelist_005.png").write_bytes(b"opponent_5_image_bytes")
    return d


def _make_app(server: OverlayServer) -> web.Application:
    """Build the aiohttp Application from an OverlayServer's route handlers."""
    app = web.Application()
    app.router.add_get("/profile/{player_id}", server._profile_image)
    app.router.add_get("/duelist/{opponent_id}", server._duelist_portrait)
    return app


@pytest.fixture
async def client_factory(portraits_dir: Path):
    """Yields a factory that creates a TestClient from an OverlayServer."""
    created: list[tuple[TestServer, TestClient, OverlayServer]] = []

    async def _make(overlay_server: OverlayServer) -> TestClient:
        app = _make_app(overlay_server)
        srv = TestServer(app)
        await srv.start_server()
        cl = TestClient(srv)
        created.append((srv, cl, overlay_server))
        return cl

    try:
        yield _make
    finally:
        for srv, _cl, _ in reversed(created):
            await srv.close()


def _build_server(portraits_dir: Path | None = None) -> OverlayServer:
    c = OverlayConfig(host="127.0.0.1", port=0)
    pc = PortraitsConfig(directory=str(portraits_dir)) if portraits_dir else None
    return OverlayServer(
        c,
        static_dir=Path(__file__).parents[2] / "src" / "fm_hundo_obs" / "static",
        portraits_config=pc,
    )


@pytest.mark.asyncio
async def test_profile_image_returns_fallback_for_uncached_player(portraits_dir: Path, client_factory):
    """GET /profile/{id} returns duelist_000.png when no Twitch cache is active."""
    server = _build_server(portraits_dir)
    client = await client_factory(server)

    resp = await client.get("/profile/999")
    assert resp.status == 200
    assert resp.content_type == "image/png"
    body = await resp.read()
    assert body == b"fallback_image_bytes"


@pytest.mark.asyncio
async def test_profile_image_returns_cached_twitch_image(portraits_dir: Path, client_factory):
    """GET /profile/{id} returns Twitch-cached image when available."""
    server = _build_server(portraits_dir)
    server.profile_cache = mock.Mock()
    server.profile_cache.get_image.return_value = b"twitch_profile_bytes"
    client = await client_factory(server)

    resp = await client.get("/profile/42")
    assert resp.status == 200
    assert resp.content_type == "image/png"
    body = await resp.read()
    assert body == b"twitch_profile_bytes"
    server.profile_cache.get_image.assert_called_once_with(42)


@pytest.mark.asyncio
async def test_profile_image_404_when_no_portraits_dir(client_factory):
    """GET /profile/{id} returns 404 when portraits directory is not configured."""
    server = _build_server(portraits_dir=None)
    client = await client_factory(server)

    resp = await client.get("/profile/999")
    assert resp.status == 404


@pytest.mark.asyncio
async def test_duelist_portrait_returns_correct_image(portraits_dir: Path, client_factory):
    """GET /duelist/{id} returns the correct portrait for a known opponent."""
    server = _build_server(portraits_dir)
    client = await client_factory(server)

    resp = await client.get("/duelist/5")
    assert resp.status == 200
    assert resp.content_type == "image/png"
    body = await resp.read()
    assert body == b"opponent_5_image_bytes"


@pytest.mark.asyncio
async def test_duelist_portrait_falls_back_for_unknown_opponent(portraits_dir: Path, client_factory):
    """GET /duelist/{id} returns duelist_000.png for an ID without a portrait."""
    server = _build_server(portraits_dir)
    client = await client_factory(server)

    resp = await client.get("/duelist/999")
    assert resp.status == 200
    assert resp.content_type == "image/png"
    body = await resp.read()
    assert body == b"fallback_image_bytes"


@pytest.mark.asyncio
async def test_duelist_portrait_404_when_no_portraits_dir(client_factory):
    """GET /duelist/{id} returns 404 when portraits directory is not configured."""
    server = _build_server(portraits_dir=None)
    client = await client_factory(server)

    resp = await client.get("/duelist/5")
    assert resp.status == 404
