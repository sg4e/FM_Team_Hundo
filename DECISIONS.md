# FM Team Hundo Durable Decisions

This file records design decisions that should be treated as durable during future agentic work. Do not reverse, re-litigate, or work around these decisions without first confirming a revised decision with the user.

When adding new entries, place them under the relevant component heading. Keep decisions concise, testable where possible, and clear about operator/user-visible consequences.

## Whole-project decisions

### Meta Documentation Tests

- Do not add automated tests that load or assert on agent-facing meta documentation files such as `AGENTS.md`, `PROJECT_CONTEXT.md`, or `DECISIONS.md`. These files are guidance for humans and agents, not runtime contracts, and should be reviewed directly when changed.

## `commentary/livestats`: LiveStats Desktop App

### Starchips Player Updates

- LiveStats player tables include a `Starchips` column between `Opponent` and `Time`.
- `PlayerUpdate` events with source/type `starchips` update only that row's `Starchips` value and `Time` value in the player table.
- Starchips player updates must not overwrite the row's existing `Source`, `Card`, `Opponent`, or `Last Addition` values.

### Last Addition Player Column

- LiveStats player tables include a `Last Addition` column between `Starchips` and `Time`.
- `Last Addition` is populated from team-library `newAcquisitions`, the same criterion used to flash player rows.
- The value must be the acquired card name, not the card ID.

## `commentary/obs_ws`: OBS Controller

### Streamer Team Labels

- The shared per-player OBS label source is intentionally reused across `FM Hundo - All Streamers`, `FM Hundo - Team - {team name}`, and `FM Hundo - Player - {player name}` scenes.
- Player labels should show `Player Name - Team Name`.
- If a player's team ID is missing from the loaded team roster, use `Team {id}` as the fallback team label.
- Simulation mode follows the same label rule, using the generated `Simulation` team. Simulated stream labels should read `{MediaMTX path} - Simulation`.

### All Streamers Layout

- `FM Hundo - All Streamers` should group active streams by team ID.
- Within each team group, active streams should be ordered by player name.
- This grouping is part of the managed scene behavior, not a configurable option.

### Label Sizing

- Stream-tile labels should use OBS max-only bounds, specifically `OBS_BOUNDS_MAX_ONLY`, so long labels are constrained but short labels are not stretched to fill the bounds.
- Apply max-only bounds to stream-tile labels in All Streamers and Team scenes.
- Player scene labels should remain natural-size and unbounded.

### Team Scenes

- Keep the centered team title on `FM Hundo - Team - {team name}` scenes, even though per-player labels also include the team name.

### Cut-In Popup Images

- Player profile image (left) is served via `GET /profile/{player_id}` on the overlay server.
- Opponent duelist portrait (right) is served via `GET /duelist/{opponent_id}` on the overlay server.
- Images are sized to match the text content area height (72 px) inside the intro card, with `object-fit: contain` to preserve aspect ratio.
- The existing intro card CSS (background, padding, border-left, box-shadow) is kept exactly as-is; images bookend it outside the card background.
- The intro WebSocket payload was extended with `playerId`, `opponentId`, and `useTwitchProfile`.

### MediaMTX Stream Identity

- Production MediaMTX paths are lowercase Twitch main-account logins, not numeric Twitch IDs.
- The OBS controller resolves `/api/players` numeric `twitchId` values through Twitch Helix `Get Users` by ID and uses the returned `login` lowercased as the stream path.
- If Helix login resolution fails, the OBS controller logs a warning and falls back to the lowercase player display name rather than failing startup.
- Restream helper alternate-account mode is source-only: `ALT_CHANNEL` may be tried first, but FFmpeg still publishes into the lowercase `MAIN_CHANNEL` path.

### Twitch Profile Image Cache

- Profile images are fetched via the Twitch API `Get Users` endpoint using an App Access Token (`client_credentials` grant).
- Credentials (`twitch.client_id`, `twitch.client_secret`) are stored in `config.yml`.
- Images are fetched once per player at startup or when they first appear as streaming, and never refreshed during the app lifetime.
- Only currently-streaming players (from MediaMTX `StreamRegistry`) are pre-fetched — not the full roster.
- A periodic sync loop (every 10 seconds) checks for new streaming players and fetches their profiles.
- Batch requests (up to 100 numeric Twitch IDs per Twitch API call) are used at startup; on-demand fetches use `asyncio.Semaphore(1)` to prevent rate-limit bursts.

### Simulation Mode & Image Fallbacks

- In simulation mode (`--simulate-mediamtx`), no `TwitchProfileCache` is created; `useTwitchProfile: false` is sent in every intro payload.
- When `useTwitchProfile` is false, the browser loads `/profile/0`, which serves `duelist_000.png`.
- Opponent portraits are served from the `portraits.directory` path: `duelist_{opponent_id:03d}.png`.
- If the specific portrait file doesn't exist, `duelist_000.png` is served as a fallback.
- Both fallback behaviors are handled server-side; the browser overlay is unaware of fallback logic.

### Intro Popup Delay

- A configurable `timing.intro_delay_seconds` (default 0) delays the intro card + images after the scene switch and banner alert.
- The delay is applied server-side in `AcquisitionScheduler` via `await asyncio.sleep(delay)`.
- The scene switch and banner alert are not delayed — only the intro pop-up is.

### Portraits Directory Configuration

- `portraits.directory` in `config.yml` is required and has no default.
- The path is resolved as-is (relative paths are relative to the CWD when the app starts).
- At startup, the app validates that the directory exists and contains at least one `duelist_*.png` file, raising a clear error if not.
- The `ygofm_portraits/` directory is gitignored (listed in `obs_ws/.gitignore`).
- Twitch credentials (`twitch.client_id`, `twitch.client_secret`) are validated at startup in production mode and raise a clear error if missing. Not checked in simulation mode.

### Alert Audio

- The alert audio source type is `ffmpeg_source`, matching the existing Media Source kind used for RTSP streams. This provides native OBS mixer visibility so the production team can manually adjust volume.
- The audio source is placed in the overlay scene (`obs.overlay_scene`), which is nested into every generated managed scene via `_ensure_overlay_on_top()`. This guarantees the source is always audible regardless of the active scene.
- The audio file path is configured in `config.yml` via `obs.alert_audio_path`. The path is required — the app fails at startup with a `ValueError` if it's missing or the file doesn't exist.
- The alert sound fires simultaneously with the banner display (and scene switch, if applicable) — not before or after the intro animation.
- Playback uses an enable→sleep→disable lifecycle: the source is enabled (with `restart_on_activate: True` to play from start), the controller waits for `timing.alert_audio_duration_seconds`, then disables the source.
- The duration is a config value (`timing.alert_audio_duration_seconds`, default 3.0 seconds), not auto-detected via ffprobe.
- A single universal audio file plays for all acquisition types (drop, fusion, ritual).
- The audio source uses `close_when_inactive: False` to keep the file decoded in memory between alerts, avoiding disk re-open latency.
- A `features.alert_audio` toggle (default `true`) allows runtime enable/disable via the `alert on|off` console command.

### Banner Animation Timing

- Banner animation timing is backend-configured via `timing.banner_delay_seconds`, `timing.banner_enter_seconds`, `timing.banner_exit_seconds`, `timing.banner_end_buffer_seconds`, and optional `timing.banner_total_seconds`.
- Banner display always starts at the beginning of the acquisition window.
- Default end timing is the acquisition window end minus `timing.banner_end_buffer_seconds`, so exit animation completes shortly before the window closes.
- Optional `timing.banner_total_seconds` may end banners earlier than the acquisition window; it is never clamped upward/downward.
- Invalid banner timing configuration is a fatal startup error (fail loudly; operator must fix config and restart).
- Drop, fusion, and ritual banners share the same animation timing model.
- Alerts received while an acquisition window is active are ignored (no queue, no interrupt, no restart).

### API Documentation Source

- `api_docs/` is the canonical repository source for API contract documentation across emulator plugins, middleware, server REST endpoints, WebSocket firehoses, and commentary overlay APIs.
- API docs are maintained as Markdown plus an OpenAPI 3.2 YAML precursor rather than committed generated HTML. Rendered docs, if needed, should be generated by tooling such as MkDocs, Redoc, Swagger UI, or Scalar and published out-of-repo.
- Changes that alter request/response fields, headers, validation behavior, authentication, WebSocket payloads, local TCP plugin JSON, overlay routes/events, or compatibility semantics must update `api_docs/` in the same commit.
