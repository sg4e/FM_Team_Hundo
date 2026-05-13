# FM Hundo OBS Controller

Python 3.12+ OBS controller for FM Team Hundo restream automation.

## Setup

```powershell
cd commentary\obs_ws
py -3.12 -m venv .venv
.\.venv\Scripts\pip install -e .[dev]
Copy-Item config.example.yml config.yml
$env:OBS_WS_PASSWORD = "your OBS websocket password"
fm-hundo-obs --config config.yml
```

Enable the MediaMTX Control API on localhost and keep RTSP enabled. The
controller expects player paths to match `/api/players` Twitch IDs:

```text
rtsp://127.0.0.1:8554/{twitchId}
```

OBS should contain one reusable overlay scene with a Browser Source pointed at:

```text
http://127.0.0.1:8765/overlay
```

Add that overlay scene as a source on every scene where alerts should be visible.
The controller manages scenes prefixed by `FM Hundo` and leaves manually
created production scenes alone.

## Console Commands

- `status`
- `help`
- `pause` / `resume`
- `scene on|off`
- `intro on|off`
- `banner on|off`
- `audio on|off|next`
- `reconcile`
- production: `test <player_id> <drop|fusion|fuse|ritual> <opponent_id> [--force]`
- simulation: `test <mediamtx_path> <drop|fusion|fuse|ritual> <opponent_id> [--force]`
- `quit`

## Managed OBS Scenes

- `FM Hundo - All Streamers`
- `FM Hundo - Team - {team name}`
- `FM Hundo - Player - {player name}`

Media Sources are stable per player and use MediaMTX RTSP URLs. Inactive streams
are hidden and configured to close when inactive where OBS supports that Media
Source setting. Acquisition alerts cut to a player scene; inactive players show a
managed placeholder rather than a dead RTSP source.

Optional production-owned master scenes can be configured in `config.yml`:

```yaml
obs:
  all_managed_master_scene: "Production - Managed Global"
  stream_layout_master_scene: "Production - Stream Layout Chrome"
```

If configured, Python creates these scenes if missing and nests them into managed
layouts, but never edits their contents. Put commentary audio sources in
`all_managed_master_scene`; it is added to every managed scene at the bottom.
Put LiveSplit, Discord streaming kit, and related labels in
`stream_layout_master_scene`; it is added only to All Streamers and Team scenes
above stream tiles but below alert overlays. OBS source and scene names are
global, so keep these names distinct from generated `FM Hundo` scenes and other
sources.

## MediaMTX Simulation Mode

For pre-event OBS rehearsal with streams that are not registered website
players:

```powershell
fm-hundo-obs --config config.yml --simulate-mediamtx
```

Simulation mode bypasses the website API and firehose. It discovers active
MediaMTX paths, creates simulated players from those paths, puts them all on a
single `Simulation` team, and keeps the normal managed OBS scenes updated as
paths appear or disappear.

Use `status` to see active paths, then trigger rehearsal alerts with:

```text
test <mediamtx_path> <drop|fusion|fuse|ritual> <opponent_id> [--force]
```
