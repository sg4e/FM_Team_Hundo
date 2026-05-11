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

OBS should contain one reusable overlay scene with a Browser Source pointed at:

```text
http://127.0.0.1:8765/overlay
```

Add that overlay scene as a source on every scene where alerts should be visible.

## Console Commands

- `status`
- `help`
- `pause` / `resume`
- `scene on|off`
- `intro on|off`
- `banner on|off`
- `audio on|off|next`
- `test <player_id> <drop|fusion|fuse|ritual> <opponent_id> [--force]`
- `quit`

