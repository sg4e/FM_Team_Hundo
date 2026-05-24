# Durable OBS Controller Decisions

This file records design decisions that should be treated as durable during future agentic work. Do not reverse, re-litigate, or work around these decisions without first confirming a revised decision with the user.

## Streamer Team Labels

- The shared per-player OBS label source is intentionally reused across `FM Hundo - All Streamers`, `FM Hundo - Team - {team name}`, and `FM Hundo - Player - {player name}` scenes.
- Player labels should show `Player Name - Team Name`.
- If a player's team ID is missing from the loaded team roster, use `Team {id}` as the fallback team label.
- Simulation mode follows the same label rule, using the generated `Simulation` team. Simulated stream labels should read `{MediaMTX path} - Simulation`.

## All Streamers Layout

- `FM Hundo - All Streamers` should group active streams by team ID.
- Within each team group, active streams should be ordered by player name.
- This grouping is part of the managed scene behavior, not a configurable option.

## Label Sizing

- Stream-tile labels should use OBS max-only bounds, specifically `OBS_BOUNDS_MAX_ONLY`, so long labels are constrained but short labels are not stretched to fill the bounds.
- Apply max-only bounds to stream-tile labels in All Streamers and Team scenes.
- Player scene labels should remain natural-size and unbounded.

## Team Scenes

- Keep the centered team title on `FM Hundo - Team - {team name}` scenes, even though per-player labels also include the team name.

## Cut-In Popup Images

- Player profile image (left) is served via `GET /profile/{player_id}` on the overlay server.
- Opponent duelist portrait (right) is served via `GET /duelist/{opponent_id}` on the overlay server.
- Images are sized to match the text content area height (72 px) inside the intro card, with `object-fit: contain` to preserve aspect ratio.
- The existing intro card CSS (background, padding, border-left, box-shadow) is kept exactly as-is; images bookend it outside the card background.
- The intro WebSocket payload was extended with `playerId`, `opponentId`, and `useTwitchProfile`.

## Twitch Profile Image Cache

- Profile images are fetched via the Twitch API `Get Users` endpoint using an App Access Token (`client_credentials` grant).
- Credentials (`twitch.client_id`, `twitch.client_secret`) are stored in `config.yml`.
- Images are fetched once per player at startup or when they first appear as streaming, and never refreshed during the app lifetime.
- Only currently-streaming players (from MediaMTX `StreamRegistry`) are pre-fetched — not the full roster.
- A periodic sync loop (every 10 seconds) checks for new streaming players and fetches their profiles.
- Batch requests (up to 100 logins per Twitch API call) are used at startup; on-demand fetches use `asyncio.Semaphore(1)` to prevent rate-limit bursts.

## Simulation Mode & Image Fallbacks

- In simulation mode (`--simulate-mediamtx`), no `TwitchProfileCache` is created; `useTwitchProfile: false` is sent in every intro payload.
- When `useTwitchProfile` is false, the browser loads `/profile/0`, which serves `duelist_000.png`.
- Opponent portraits are served from the `portraits.directory` path: `duelist_{opponent_id:03d}.png`.
- If the specific portrait file doesn't exist, `duelist_000.png` is served as a fallback.
- Both fallback behaviors are handled server-side; the browser overlay is unaware of fallback logic.

## Intro Popup Delay

- A configurable `timing.intro_delay_seconds` (default 0) delays the intro card + images after the scene switch and banner alert.
- The delay is applied server-side in `AcquisitionScheduler` via `await asyncio.sleep(delay)`.
- The scene switch and banner alert are not delayed — only the intro pop-up is.

## Portraits Directory Configuration

- `portraits.directory` in `config.yml` is required and has no default.
- The path is resolved as-is (relative paths are relative to the CWD when the app starts).
- At startup, the app validates that the directory exists and contains at least one `duelist_*.png` file, raising a clear error if not.
- The `ygofm_portraits/` directory is gitignored (listed in `obs_ws/.gitignore`).
- Twitch credentials (`twitch.client_id`, `twitch.client_secret`) are validated at startup in production mode and raise a clear error if missing. Not checked in simulation mode.
