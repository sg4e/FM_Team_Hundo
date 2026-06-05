from __future__ import annotations

import asyncio
import logging

from aiohttp import ClientSession

from .mapping import NameResolver
from .models import Player

LOGGER = logging.getLogger(__name__)
TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token"
TWITCH_USERS_URL = "https://api.twitch.tv/helix/users"
TWITCH_MAX_USERS_PER_REQUEST = 100


class TwitchProfileCache:
    """Fetches and caches Twitch profile images for streaming players.

    Fetches profile images once per player at startup/join; images are never
    refreshed during the lifetime of the application. Uses a single in-flight
    semaphore to avoid accidental rate-limit bursts.

    Production players expose numeric Twitch IDs and are queried with Helix
    ``id`` parameters. Simulation players use MediaMTX paths as Twitch logins
    and are queried with Helix ``login`` parameters.
    """

    def __init__(
        self,
        client_id: str,
        client_secret: str,
        session: ClientSession,
        fallback_image: bytes | None = None,
    ) -> None:
        self._client_id = client_id
        self._client_secret = client_secret
        self._session = session
        self._fallback_image = fallback_image
        self._token: str | None = None
        self._cache: dict[int, bytes] = {}
        self._cached_twitch_identifiers: set[str] = set()
        self._semaphore = asyncio.Semaphore(1)

    @property
    def has_images(self) -> bool:
        return bool(self._cache)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def start(self) -> None:
        """Obtain an App Access Token from Twitch."""
        LOGGER.info("Obtaining Twitch App Access Token")
        async with self._semaphore:
            await self._fetch_token()

    async def resolve_player_logins(self, players: list[Player]) -> dict[int, str]:
        """Return player id -> canonical lowercase Twitch login for players with Twitch IDs."""
        needed = [(player.id, player.twitch_id) for player in players if player.twitch_id]
        if not needed:
            return {}

        resolved: dict[int, str] = {}
        async with self._semaphore:
            for i in range(0, len(needed), TWITCH_MAX_USERS_PER_REQUEST):
                batch = needed[i: i + TWITCH_MAX_USERS_PER_REQUEST]
                data = await self._fetch_users_by_ids([twitch_id for _, twitch_id in batch])
                twitch_id_to_player: dict[str, int] = {
                    twitch_id: player_id
                    for player_id, twitch_id in batch
                    if twitch_id
                }
                for user in data.get("data", ()):
                    twitch_id = str(user.get("id", ""))
                    login = str(user.get("login", "")).strip().lower()
                    player_id = twitch_id_to_player.get(twitch_id)
                    if player_id is not None and login:
                        resolved[player_id] = login
        return resolved

    async def sync_streaming_players(
        self,
        active_player_ids: set[int],
        names: NameResolver,
        *,
        twitch_ids_are_logins: bool = False,
    ) -> None:
        """Fetch profiles for actively-streaming players not yet cached.

        Production roster values are numeric Twitch IDs. Simulation roster
        values are lowercase MediaMTX paths, which follow Twitch login names.
        """
        parameter = "login" if twitch_ids_are_logins else "id"
        needed: list[tuple[int, str]] = []
        for player_id in active_player_ids:
            identifier = self._twitch_id(names, player_id)
            cache_key = self._cache_key(parameter, identifier) if identifier is not None else None
            if identifier is not None and cache_key not in self._cached_twitch_identifiers:
                needed.append((player_id, identifier))

        if not needed:
            return

        LOGGER.info("Fetching %d Twitch profile(s) not yet cached", len(needed))
        async with self._semaphore:
            for i in range(0, len(needed), TWITCH_MAX_USERS_PER_REQUEST):
                batch = needed[i: i + TWITCH_MAX_USERS_PER_REQUEST]
                await self._fetch_batch(batch, parameter=parameter)

    def get_image(self, player_id: int) -> bytes | None:
        """Return cached profile image bytes for *player_id*, or *None*."""
        return self._cache.get(player_id)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _fetch_token(self) -> None:
        async with self._session.post(
            TWITCH_TOKEN_URL,
            params={
                "client_id": self._client_id,
                "client_secret": self._client_secret,
                "grant_type": "client_credentials",
            },
            headers={"Accept": "application/json"},
        ) as resp:
            resp.raise_for_status()
            body = await resp.json()
            self._token = str(body["access_token"])
            LOGGER.info("Twitch App Access Token obtained (expires in %s s)", body.get("expires_in", "?"))

    async def _fetch_users_by_ids(self, twitch_ids: list[str]) -> dict:
        return await self._fetch_users("id", twitch_ids)

    async def _fetch_users(self, parameter: str, identifiers: list[str]) -> dict:
        params = [(parameter, identifier) for identifier in identifiers]
        headers = {
            "Client-ID": self._client_id,
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/json",
        }
        async with self._session.get(
            TWITCH_USERS_URL,
            params=params,
            headers=headers,
        ) as resp:
            if resp.status == 401:
                LOGGER.info("Twitch token expired; re-authenticating")
                await self._fetch_token()
                headers["Authorization"] = f"Bearer {self._token}"
                async with self._session.get(
                    TWITCH_USERS_URL,
                    params=params,
                    headers=headers,
                ) as retry_resp:
                    retry_resp.raise_for_status()
                    return await retry_resp.json()
            resp.raise_for_status()
            return await resp.json()

    async def _fetch_batch(self, batch: list[tuple[int, str]], *, parameter: str = "id") -> None:
        data = await self._fetch_users(parameter, [identifier for _, identifier in batch])

        identifier_to_player: dict[str, int] = {
            self._canonical_identifier(parameter, identifier): pid
            for pid, identifier in batch
        }
        for user in data.get("data", ()):
            identifier = self._canonical_identifier(parameter, str(user.get(parameter, "")))
            profile_url = user.get("profile_image_url")
            pid = identifier_to_player.get(identifier)
            if pid is not None and profile_url:
                await self._download_profile(pid, profile_url)
        self._cached_twitch_identifiers.update(
            self._cache_key(parameter, identifier)
            for _, identifier in batch
        )

    @staticmethod
    def _canonical_identifier(parameter: str, identifier: str) -> str:
        return identifier.strip().lower() if parameter == "login" else identifier.strip()

    @classmethod
    def _cache_key(cls, parameter: str, identifier: str) -> str:
        return f"{parameter}:{cls._canonical_identifier(parameter, identifier)}"

    async def _download_profile(self, player_id: int, url: str) -> None:
        try:
            async with self._session.get(url) as resp:
                resp.raise_for_status()
                self._cache[player_id] = await resp.read()
                LOGGER.debug("Cached profile image for player %s (%d bytes)", player_id, len(self._cache[player_id]))
        except asyncio.CancelledError:
            raise
        except Exception:
            LOGGER.warning("Failed to download Twitch profile image for player %s from %s", player_id, url)

    @staticmethod
    def _twitch_id(names: NameResolver, player_id: int) -> str | None:
        """Return the roster's Twitch identifier for *player_id*, or *None*."""
        return names.twitch_id_for(player_id)
