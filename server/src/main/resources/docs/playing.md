# Connecting your emulator to the FM Team Hundo server

```
┌─────────────────────┐     ┌──────────────┐     ┌───────────────────────┐
│                     │     │              │     │                       │
│    Emulator         │     │  FM_Sentinel │     │   FM Team Hundo       │
│   (with plugin)     │ ──> │              │ <──>│   collection server   │
│                     │     │              │     │                       │
└─────────────────────┘     └──────────────┘     └───────────────────────┘
```

This page assumes that you've already read and completed [Getting set up for FM Team Hundo](/docs/setup).

Perform the following steps **in order** each time you want to continue your progress in FM Team Hundo:

1. Double-click `run_FM_Sentinel.bat` (Windows) or `run_FM_Sentinel.sh` (Linux) to launch the FM_Sentinel middleware.

2. Launch your emulator and load the *Forbidden Memories* ROM (in BizHawk, make sure you're loading the `.cue` file, not the `.bin`).

3. Connect your emulator to FM_Sentinel:
   
   - BizHawk: `Tools > External Tool > FM Team Hundo` and click "Yes" to trust the tool if asked. You'll need to keep the small "Connected" window open.
   
   - DuckStation: DuckStation connects to FM_Sentinel automatically, but you need to build your own patched copy from source. See [Getting set up for FM Team Hundo](/docs/setup).

4. Verify in the FM_Sentinel command prompt/terminal window that the connection to the emulator and server is successful. FM_Sentinel will attempt to detect any connection problems while you play and print an error message, but you may also want to monitor [your player page](/players/me) on the FM Team Hundo website to make sure all your actions are posting to the server.

5. Load your save file (or New Game if you haven't started yet). You're ready to play!

## Limitations

1. Once you ritual-summon a monster, that monster's ritual summon will not be re-sent to the collection server until you relaunch the emulator. This has no effect on the team's Library and can be ignored.

2. If you receive the same drop in two consecutive fights, the second copy will not be sent to the collection server. This is an issue for the Blue-Eyes White Dragon farm. If you intend to keep farming for Blue-Eyes White Dragons after receiving one, either relaunch your emulator or win at least one duel against some other duelist before returning to Seto 3.
