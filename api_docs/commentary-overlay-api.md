# Commentary and Overlay APIs

This page documents API consumers and local overlay APIs outside the core emulator → middleware → server ingestion path. These contracts matter because commentary tools are operational clients of the public server API and expose browser-facing local routes.

## LiveStats server consumption

The JavaFX LiveStats app uses:

| Contract | Purpose |
| --- | --- |
| `GET /api/players` | Load player roster. |
| `GET /api/teams` | Load team list. |
| `GET /api/library/{teamId}` | Load initial team library snapshots. |
| `WS /firehose/player` | Receive `PlayerUpdate` text frames. |
| `WS /firehose/team` | Receive `LibraryUpdate` text frames. |

LiveStats reconnects firehose clients after a short delay and expects JSON field names to match the server DTOs exactly.

## OBS controller server consumption

The Python OBS/MediaMTX controller uses:

| Contract | Purpose |
| --- | --- |
| `GET /api/players` | Build player/team stream mappings and profile-cache lookup data; `twitchId` is treated as a numeric Twitch ID and resolved through Twitch Helix to the lowercase main-account login used as the MediaMTX path. |
| `GET /api/teams` | Build managed OBS team scenes. |
| `GET /api/credits` | Build credits-scene scroll payloads. |
| `WS /firehose/team` | Watch `LibraryUpdate.newAcquisitions` and schedule acquisition alerts. |

The OBS controller currently does not subscribe to `/firehose/player`. MediaMTX
paths are lowercase main Twitch logins. The restream helper publishes alternate
Twitch sources into the lowercase main-login path so the OBS controller can keep
one stable per-player RTSP URL. If Helix login resolution is unavailable, the
controller logs a warning and falls back to the lowercase player display name.
Simulation mode queries active MediaMTX paths as Twitch logins and caches their
profile images so simulated alerts retain production Twitch-avatar behavior.

## Local overlay HTTP routes

The OBS controller starts a local aiohttp server for OBS browser sources.

| Route | Type | Response | Notes |
| --- | --- | --- | --- |
| `GET /overlay` | HTTP | HTML | Main acquisition overlay page. Config values are substituted into the static HTML. |
| `GET /events` | WebSocket | JSON text frames | Browser event channel for banner/intro commands. |
| `GET /credits` | HTTP | HTML | Credits-scene page. Served with no-store cache headers. |
| `GET /credits/events` | WebSocket | JSON text frames | Credits event channel. New clients immediately receive the latest credits payload if available. |
| `GET /profile/{player_id}` | HTTP | `image/png` or 404 | Uses cached Twitch profile image when available; otherwise falls back to `duelist_000.png`. |
| `GET /duelist/{opponent_id}` | HTTP | `image/png` or 404 | Serves `duelist_{opponent_id:03d}.png`, falling back to `duelist_000.png`. |

## Overlay WebSocket messages

All overlay event frames have this envelope:

```json
{"type":"banner","payload":{}}
```

### Banner event

Sent on `/events`:

```json
{
  "type": "banner",
  "payload": {
    "label": "Big Drop Alert",
    "source": "drop",
    "durationSeconds": 6.0,
    "delaySeconds": 0.0,
    "enterSeconds": 0.3,
    "exitSeconds": 0.3
  }
}
```

| Payload field | Type | Notes |
| --- | --- | --- |
| `label` | string | Human display label. |
| `source` | `drop`, `fuse`, or `ritual` | Acquisition source. |
| `durationSeconds` | number | Total visible duration. |
| `delaySeconds` | number | Delay before banner starts. |
| `enterSeconds` | number | Enter animation time. |
| `exitSeconds` | number | Exit animation time. |

### Intro event

Sent on `/events`:

```json
{
  "type": "intro",
  "payload": {
    "playerName": "PlayerName",
    "opponentName": "Seto 3",
    "durationSeconds": 6.0,
    "playerId": 101,
    "opponentId": 24,
    "useTwitchProfile": true
  }
}
```

| Payload field | Type | Notes |
| --- | --- | --- |
| `playerName` | string | Displayed player name. |
| `opponentName` | string | Displayed duelist/opponent name. |
| `durationSeconds` | number | Total visible duration. |
| `playerId` | integer | Used by browser to request `/profile/{player_id}`. |
| `opponentId` | integer | Used by browser to request `/duelist/{opponent_id}`. |
| `useTwitchProfile` | boolean | Whether the browser requests the player's cached Twitch profile. Production and simulation alerts set this true; the profile route serves the duelist fallback when no cached image is available. |

### Credits event

Sent on `/credits/events`:

```json
{
  "type": "credits",
  "payload": {
    "settings": {
      "scrollPixelsPerSecond": 70,
      "mainTitleFontPx": 72,
      "sectionFontPx": 48,
      "labelFontPx": 34,
      "bodyFontPx": 32,
      "billingFontPx": 44
    },
    "blocks": [
      {"type":"main-title","title":"FM Team Hundo","final":false}
    ]
  }
}
```

Credits payloads are generated from `/api/credits`, local card/duelist name data, and credits-scene config.
