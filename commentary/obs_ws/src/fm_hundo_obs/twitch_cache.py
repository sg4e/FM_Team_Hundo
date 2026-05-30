from __future__ import annotations

import asyncio
import logging

from aiohttp import ClientSession

from .mapping import NameResolver

LOGGER = logging.getLogger(__name__)
TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token"
TWITCH_USERS_URL = "https://api.twitch.tv/helix/users"
TWITCH_MAX_LOGINS_PER_REQUEST = 100


class TwitchProfileCache:
    """Fetches and caches Twitch profile images for streaming players.

    Fetches profile images once per player at startup/join; images are never
    refreshed during the lifetime of the application.  Uses a single in-flight
    semaphore to avoid accidental rate-limit bursts.
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
        self._cached_twitch_ids: set[str] = set()
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

    async def sync_streaming_players(
        self,
        active_player_ids: set[int],
        names: NameResolver,
    ) -> None:
        """Fetch profiles for any actively-streaming players not yet cached.

        Safe to call repeatedly — it compares against the internal set of
        already-fetched Twitch usernames and only fetches what is missing.
        """
        needed: list[tuple[int, str]] = []
        for player_id in active_player_ids:
            twitch_id = self._twitch_login(names, player_id)
            if twitch_id is not None and twitch_id not in self._cached_twitch_ids:
                needed.append((player_id, twitch_id))

        if not needed:
            return

        LOGGER.info("Fetching %d Twitch profile(s) not yet cached", len(needed))
        async with self._semaphore:
            for i in range(0, len(needed), TWITCH_MAX_LOGINS_PER_REQUEST):
                batch = needed[i: i + TWITCH_MAX_LOGINS_PER_REQUEST]
                await self._fetch_batch(batch)

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

    async def _fetch_batch(self, batch: list[tuple[int, str]]) -> None:
        logins = [tw_id for _, tw_id in batch]
        headers = {
            "Client-ID": self._client_id,
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/json",
        }
        async with self._session.get(
            TWITCH_USERS_URL,
            params={"login": logins},
            headers=headers,
        ) as resp:
            if resp.status == 401:
                LOGGER.info("Twitch token expired; re-authenticating")
                await self._fetch_token()
                headers["Authorization"] = f"Bearer {self._token}"
                async with self._session.get(
                    TWITCH_USERS_URL,
                    params={"login": logins},
                    headers=headers,
                ) as retry_resp:
                    retry_resp.raise_for_status()
                    data = await retry_resp.json()
            else:
                resp.raise_for_status()
                data = await resp.json()

        twitch_id_to_player: dict[str, int] = {tw_id: pid for pid, tw_id in batch}
        for user in data.get("data", ()):
            tw_login = str(user.get("login", ""))
            profile_url = user.get("profile_image_url")
            pid = twitch_id_to_player.get(tw_login)
            if pid is not None and profile_url:
                await self._download_profile(pid, profile_url)
            if tw_login:
                self._cached_twitch_ids.add(tw_login)

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
    def _twitch_login(names: NameResolver, player_id: int) -> str | None:
        """Return the Twitch login for *player_id*, or *None* if unknown."""
        return names.twitch_username_for(player_id)
