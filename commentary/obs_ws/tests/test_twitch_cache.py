from __future__ import annotations

import asyncio
from unittest import mock

import pytest
from aiohttp import ClientSession

from fm_hundo_obs.mapping import NameResolver
from fm_hundo_obs.models import Player, Team
from fm_hundo_obs.twitch_cache import TwitchProfileCache


def _fake_json_response(data, status: int = 200):
    """Create a fake async response context manager that returns JSON."""
    resp = mock.AsyncMock()
    resp.status = status
    resp.raise_for_status = mock.MagicMock() if status < 400 else mock.Mock(side_effect=Exception("HTTP error"))
    resp.json = mock.AsyncMock(return_value=data)
    return resp


class FakeContextManager:
    """Mimics an async context manager returned by session.get/post."""

    def __init__(self, resp):
        self._resp = resp

    async def __aenter__(self):
        return self._resp

    async def __aexit__(self, *args):
        pass


@pytest.mark.asyncio
async def test_fetch_token_on_start():
    """start() posts to Twitch OAuth and stores the token."""
    session = mock.AsyncMock(spec=ClientSession)
    token_resp = _fake_json_response({"access_token": "my_token", "expires_in": 50000})
    session.post.return_value = FakeContextManager(token_resp)

    cache = TwitchProfileCache("test_client", "test_secret", session)
    await cache.start()

    session.post.assert_called_once()
    assert cache._token == "my_token"


@pytest.mark.asyncio
async def test_sync_streaming_players_fetches_new_profiles():
    """sync_streaming_players() fetches profiles for uncached players."""
    session = mock.AsyncMock(spec=ClientSession)

    # Simulate Twitch /users response
    users_resp = _fake_json_response({
        "data": [
            {"id": "id1", "login": "twitch_1", "display_name": "S1", "profile_image_url": "http://fake/img1"},
            {"id": "id2", "login": "twitch_2", "display_name": "S2", "profile_image_url": "http://fake/img2"},
        ],
    })
    session.get.return_value = FakeContextManager(users_resp)

    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._token = "test_token"

    names = NameResolver(
        [Player(1, "twitch_1", "Streamer One", None, 1), Player(2, "twitch_2", "Streamer Two", None, 1)],
        {},
        [Team(1, "Alpha")],
    )

    # The profile image download requests need to succeed after the users lookup.
    # Override _download_profile to avoid needing HTTP server for image URLs.
    original_download = cache._download_profile

    async def fake_download(pid, url):
        cache._cache[pid] = b"img_bytes"

    cache._download_profile = fake_download  # type: ignore[method-assign]

    await cache.sync_streaming_players({1, 2}, names)

    assert cache.get_image(1) == b"img_bytes"
    assert cache.get_image(2) == b"img_bytes"


@pytest.mark.asyncio
async def test_sync_streaming_players_skips_cached():
    """Does not re-fetch already cached profiles."""
    session = mock.AsyncMock(spec=ClientSession)
    users_resp = _fake_json_response({"data": []})
    session.get.return_value = FakeContextManager(users_resp)

    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._token = "test_token"
    cache._cache[1] = b"existing"
    cache._cached_twitch_ids.add("twitch_1")

    names = NameResolver(
        [Player(1, "twitch_1", "Streamer One", None, 1), Player(2, "twitch_2", "Streamer Two", None, 1)],
        {},
        [Team(1, "Alpha")],
    )

    await cache.sync_streaming_players({1, 2}, names)

    # Player 1 should keep its cached image
    assert cache.get_image(1) == b"existing"
    assert session.get.called, "Request should have been made for player 2"


@pytest.mark.asyncio
async def test_get_image_returns_none_for_missing():
    """get_image() returns None for uncached players."""
    session = mock.AsyncMock(spec=ClientSession)
    cache = TwitchProfileCache("test_client", "test_secret", session)
    assert cache.get_image(999) is None


@pytest.mark.asyncio
async def test_get_image_returns_cached_bytes():
    """get_image() returns stored bytes."""
    session = mock.AsyncMock(spec=ClientSession)
    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._cache[42] = b"hello"
    assert cache.get_image(42) == b"hello"


@pytest.mark.asyncio
async def test_skips_player_without_twitch_id():
    """Players without twitch_id are skipped during sync."""
    session = mock.AsyncMock(spec=ClientSession)
    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._token = "test_token"

    names = NameResolver(
        [Player(1, None, "No Twitch", None, 1)],
        {},
        [Team(1, "Alpha")],
    )

    await cache.sync_streaming_players({1}, names)

    assert cache.get_image(1) is None
    assert not session.get.called


@pytest.mark.asyncio
async def test_rate_limiting_semaphore_used():
    """sync_streaming_players uses the semaphore for rate limiting."""
    session = mock.AsyncMock(spec=ClientSession)
    users_resp = _fake_json_response({"data": []})
    session.get.return_value = FakeContextManager(users_resp)

    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._token = "test_token"

    # Verify the semaphore exists and is a bounded semaphore with value 1
    assert isinstance(cache._semaphore, type(asyncio.Semaphore(1)))
    assert cache._semaphore._value == 1  # noqa: SLF001

    names = NameResolver(
        [Player(1, "twitch_1", "Streamer One", None, 1)],
        {},
        [Team(1, "Alpha")],
    )

    await cache.sync_streaming_players({1}, names)

    # If we got here without hanging, the semaphore was released properly


@pytest.mark.asyncio
async def test_download_profile_failure_does_not_raise():
    """A failed profile image download is logged and skipped, not raised."""
    session = mock.AsyncMock(spec=ClientSession)
    # Simulate an HTTP error when downloading the image.
    bad_resp = mock.AsyncMock()
    bad_resp.status = 404
    bad_resp.raise_for_status = mock.MagicMock()
    bad_resp.read.side_effect = Exception("Connection error")
    session.get.return_value = FakeContextManager(bad_resp)

    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._token = "test_token"

    # This should not raise — the error is logged and the player is skipped.
    await cache._download_profile(42, "http://fake/image.png")

    assert cache.get_image(42) is None, "Image should not be cached on failure"


@pytest.mark.asyncio
async def test_fetch_batch_retries_on_401():
    """_fetch_batch retries on 401 by re-authenticating."""
    session = mock.AsyncMock(spec=ClientSession)

    # First call returns 401 (expired token), second returns success.
    first_resp = mock.AsyncMock()
    first_resp.status = 401
    first_resp.__aenter__.return_value = first_resp

    second_resp = mock.AsyncMock()
    second_resp.status = 200
    second_resp.raise_for_status = mock.MagicMock()
    second_resp.json = mock.AsyncMock(return_value={"data": []})
    second_resp.__aenter__.return_value = second_resp

    session.get.side_effect = [first_resp, second_resp]

    # Token refresh succeeds.
    token_resp = _fake_json_response({"access_token": "new_token", "expires_in": 50000})
    session.post.return_value = FakeContextManager(token_resp)

    cache = TwitchProfileCache("test_client", "test_secret", session)
    cache._token = "expired_token"

    await cache._fetch_batch([(1, "twitch_1")])

    assert cache._token == "new_token", "Token should have been refreshed"
    assert session.get.call_count == 2, "Should have retried after 401"

