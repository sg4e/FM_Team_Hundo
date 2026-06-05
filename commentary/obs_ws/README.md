# FM Hundo OBS Controller

Python 3.12+ OBS controller for FM Team Hundo restream automation.

## Setup

```powershell
cd commentary\obs_ws
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install -e '.[dev]'
Copy-Item config.example.yml config.yml
Copy-Item credits_scene.example.yml credits_scene.yml
$env:OBS_WS_PASSWORD = "your OBS websocket password"
fm-hundo-obs --config config.yml
```

For tests, use an activated virtual environment and install the package in editable dev mode before running pytest:

```powershell
cd commentary\obs_ws
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install -e '.[dev]'
.\.venv\Scripts\python -m pytest
```

Enable the MediaMTX Control API on localhost and keep RTSP enabled. The
controller resolves each `/api/players` numeric Twitch ID through Twitch Helix,
uses the returned main-account login lowercased, and expects MediaMTX paths to
match that lowercase login:

```text
rtsp://127.0.0.1:8554/{lowercaseMainTwitchLogin}
```

If Twitch login resolution fails, the controller logs a startup warning and
falls back to the lowercase player display name. The restream helper publishes
both main and alternate Twitch sources into the lowercase main-account path, so
OBS does not need to know whether a player is currently live on main or alt.

OBS should contain one reusable overlay scene with a Browser Source pointed at:

```text
http://127.0.0.1:8765/overlay
```

Add that overlay scene as a source on every scene where alerts should be visible.
The controller manages scenes prefixed by `FM Hundo` and leaves manually
created production scenes alone.

The controller also creates a managed credits scene with a Browser Source pointed
at:

```text
http://127.0.0.1:8765/credits
```

## Console Commands

- `status`
- `help`
- `pause` / `resume`
- `scene on|off`
- `intro on|off`
- `banner on|off`
- `audio on|off|next`
- `alert on|off`
- `credits`
- `reconcile`
- production: `test <player_id> <drop|fusion|fuse|ritual> <opponent_id> [--force]`
- simulation: `test <mediamtx_path> <drop|fusion|fuse|ritual> <opponent_id> [--force]`
- `quit`

## Managed OBS Scenes

- `FM Hundo - All Streamers`
- `FM Hundo - Team - {team name}`
- `FM Hundo - Player - {player name}`
- `FM Hundo - Credits`

Media Sources are stable per player and use MediaMTX RTSP URLs based on the
lowercase main Twitch login resolved from each numeric Twitch ID. Inactive
streams are hidden and configured to close when inactive where OBS supports that
Media Source setting. Acquisition alerts cut to a player scene; inactive players
show a managed placeholder rather than a dead RTSP source.

The `credits` command reloads `credits_scene.yml`, fetches `/api/credits`, cuts
to `FM Hundo - Credits`, and starts the roll from the beginning. While that
scene is active, acquisition alerts do not take scene control.

Production-owned manual scenes can be configured in `config.yml`:

```yaml
obs:
  manual_background_scene: "Manual Background"
  all_managed_master_scene: "Production - Managed Global"
  stream_layout_master_scene: "Production - Stream Layout Chrome"
```

Python creates these scenes if missing and nests them into managed
layouts, but never edits their contents. `manual_background_scene` defaults to
`Manual Background` and is added to every generated managed scene as the bottom
source layer; set it to `null` to disable it. It is not added to the overlay or
credits scenes. Put commentary audio sources in `all_managed_master_scene`;
it is added to every managed scene directly under
the FM Hundo overlay scene, making it second-to-the-top in the managed scene
source order. Put LiveSplit, Discord streaming kit, and related labels in
`stream_layout_master_scene`; it is added only to All Streamers and Team scenes
above stream tiles but below `all_managed_master_scene` and alert overlays. OBS
source and scene names are global, so keep these names distinct from generated
`FM Hundo` scenes and other sources.

## Managed Stream Audio Filters

Every website/simulation player gets one stable OBS Media Source, and that source
is reused across the All Streamers, Team, and Player scenes. You can apply an
ordered OBS audio filter chain to each managed stream source from `config.yml`:

```yaml
obs:
  stream_audio_filters:
    enabled: true
    sync_settings: true  # true = YAML is authoritative; false = keep existing OBS filter settings
    filters:
      - name: "FM Hundo Stream Compressor"
        kind: "compressor_filter"
        enabled: true
        settings:
          ratio: 6.0
          threshold: -18.0
          attack_time: 6
          release_time: 60
          output_gain: 0.0

      - name: "FM Hundo Commentary Duck"
        kind: "compressor_filter"
        enabled: true
        settings:
          ratio: 10.0
          threshold: -24.0
          attack_time: 6
          release_time: 250
          output_gain: 0.0
          sidechain_source: "Production Commentary Bus"

      - name: "FM Hundo Safety Limiter"
        kind: "limiter_filter"
        enabled: true
        settings: {}
```

Filter `kind` and `settings` are passed through to OBS WebSocket, so this can
manage compressors, limiters, and future OBS/plugin audio filters without adding
filter-specific Python code. The configured order is enforced on every managed
stream source.

For OBS compressor sidechain ducking, set `settings.sidechain_source` to the
exact OBS audio source name. This may be a manually managed production source
from a master scene, such as a commentary bus. Startup OBS validation checks that
configured sidechain source names exist as OBS inputs.

With `sync_settings: true`, `config.yml` overwrites existing OBS filter settings
on setup/reconcile. With `sync_settings: false`, missing filters are created and
the configured order/enabled state is maintained, but existing OBS filter
settings are left available for manual live tweaking.

## Alert Audio

An optional alert sound plays when an acquisition fires. Configure it in `config.yml`:

```yaml
obs:
  alert_audio_path: "C:/path/to/alert.wav"  # required to enable; absolute or relative to config.yml
  alert_audio_source: null                   # defaults to "{managed_scene_prefix} Alert Audio"
timing:
  alert_audio_duration_seconds: 3.0
  banner_delay_seconds: 0.0
  banner_enter_seconds: 0.3
  banner_exit_seconds: 0.3
  banner_end_buffer_seconds: 0.08
  banner_total_seconds: null
features:
  alert_audio: true
```

- The audio file must exist at startup; a `ValueError` is raised immediately if it's missing.
- The controller creates a managed `ffmpeg_source` in the overlay scene with `restart_on_activate: True` and `close_when_inactive: False` so the file stays loaded in memory between alerts.
- The source appears in OBS's audio mixer, allowing the production team to tweak volume manually.
- Use `alert on|off` in the console to toggle at runtime.

## Banner Timing

Banner timing is backend-configured under `timing`:

- `banner_delay_seconds`: delay before fly-in starts.
- `banner_enter_seconds`: fly-in duration.
- `banner_exit_seconds`: fly-out duration.
- `banner_end_buffer_seconds`: default gap between banner exit completion and acquisition window end.
- `banner_total_seconds` (optional): explicitly end banner earlier than the acquisition window.

The banner always starts at the beginning of the acquisition window. If
`banner_total_seconds` is set, it must be less than or equal to
`acquisition_window_seconds - banner_end_buffer_seconds`; invalid values are
treated as startup configuration errors.

## MediaMTX Simulation Mode

For pre-event OBS rehearsal with streams that are not registered website
players:

```powershell
fm-hundo-obs --config config.yml --simulate-mediamtx
```

Simulation mode bypasses the website API and firehose. It discovers active
MediaMTX paths, creates simulated players from those paths, puts them all on a
single `Simulation` team, and keeps the normal managed OBS scenes updated as
paths appear or disappear. Implemented Twitch features remain enabled: active
MediaMTX paths are queried as Twitch logins, and simulated alerts use the cached
Twitch avatar when the path is a valid Twitch login. Twitch credentials are
therefore required in simulation mode as well as production mode.

Use `status` to see active paths, then trigger rehearsal alerts with:

```text
test <mediamtx_path> <drop|fusion|fuse|ritual> <opponent_id> [--force]
```
