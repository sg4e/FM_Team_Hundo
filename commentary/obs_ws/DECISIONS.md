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
