# FM Team Hundo Project Context

This document gives agents and maintainers a project-wide map. Keep it current when code movement, build tooling, runtime topology, or component responsibilities change.

## Product context

FM Team Hundo supports a team-oriented *Yu-Gi-Oh! Forbidden Memories* completion event. Players run a supported emulator with an FM Team Hundo plugin. The plugin watches game memory for card drops, fusions, rituals, starchips, RNG, and related state, then reports events through a local middleware process to the central server. The server records player progress, computes team library completion, serves participant/admin/UI pages, and broadcasts live updates. Commentary tools consume those updates to produce live stats displays and OBS-managed restream scenes.

## Runtime architecture

```text
Forbidden Memories emulator
  ├─ BizHawk external tool DLL (`bizhawk_plugin/`)
  └─ patched DuckStation build (`duckstation_patch/`)
        │ newline-delimited local JSON on 127.0.0.1:51155
        ▼
FM_Sentinel middleware (`middleware/`)
        │ authenticated HTTP API calls using credentials_FM_Team_Hundo.json
        ▼
Spring/Vaadin server (`server/`)
        ├─ browser UI and docs
        ├─ REST API for player/team/library/credits data
        ├─ WebSocket firehose/team feeds
        └─ persistent player updates and acquisition videos
        │
        ├─ JavaFX LiveStats commentary app (`commentary/livestats/`)
        └─ Python OBS controller (`commentary/obs_ws/`) + MediaMTX/Twitch restream inputs
```

## Root-level files

- `.github/workflows/build.yml` builds/tests release artifacts for BizHawk plugin, Rust middleware, server JAR, LiveStats JAR, OBS controller tests, DuckStation patch package, and tag releases.
- `FM_Team_Hundo.sln` groups the C# BizHawk plugin solution.
- `FM_Team_Hundo.code-workspace` opens the root, Maven server, and Gradle LiveStats project together in VS Code.
- `LICENSE.md` is AGPL-3.0 text for this repository.
- `vscode-java-workspace.md` records Java workspace notes.
- `AGENTS.md`, `PROJECT_CONTEXT.md`, and `DECISIONS.md` are the meta files that must stay in sync with agent-facing repository reality.
- `api_docs/` is the canonical source documentation for API contracts. It contains Markdown per API layer plus `schemas/openapi.yaml` as the OpenAPI 3.2 server HTTP API precursor. Keep it in sync whenever emulator plugin messages, middleware behavior, server endpoints/firehoses, commentary overlay routes/events, or compatibility rules change.


## API documentation (`api_docs/`)

`api_docs/` documents every API layer in source form rather than committed generated HTML:

- `README.md` explains the documentation format, optional MkDocs/Redoc rendering, and maintenance checklist.
- `plugin-middleware-protocol.md` covers newline-delimited JSON over `127.0.0.1:51155`.
- `middleware-server-api.md` covers credentials, `/api/validate`, `/api/update`, test mode, and retry behavior.
- `server-api.md` covers public REST endpoints and `/firehose/player` plus `/firehose/team` WebSocket payloads.
- `commentary-overlay-api.md` covers LiveStats/OBS consumption and local overlay HTTP/WebSocket routes.
- `compatibility.md` covers protocol-version stamping and bump rules.
- `openapi.md` is the MkDocs page that links to the OpenAPI YAML precursor.
- `schemas/openapi.yaml` is the machine-readable OpenAPI 3.2 server HTTP API precursor.

Review these files directly for API-only documentation changes. Validate docs with `python api_docs/scripts/check_openapi_sync.py` and `python -m mkdocs build --strict --config-file api_docs/mkdocs.yml`. Optional local previews can be generated with MkDocs or Redoc, but generated HTML should be published out-of-repo and not committed.

## Main server (`server/`)

The server is a Spring Boot + Vaadin application packaged as `server.jar` for releases.

Important areas:

- `src/main/java/moe/maika/fmteamhundo/api/`: REST/API DTOs and controllers for emulator/middleware updates, teams, players, credits, and protocol metadata.
- `src/main/java/moe/maika/fmteamhundo/state/`: in-memory live game state, library aggregation, user mapping, and WebSocket broadcast handlers.
- `src/main/java/moe/maika/fmteamhundo/data/`: JPA entities and repositories.
- `src/main/java/moe/maika/fmteamhundo/service/`: domain services including API keys, teams, Twitch accounts/Helix/VOD resolution, acquisition video recording, and credits.
- `src/main/java/moe/maika/fmteamhundo/security/`: Twitch OAuth2 login and Spring Security configuration.
- `src/main/java/moe/maika/fmteamhundo/ui/`: Vaadin views for public/admin/player/team/stats/docs pages.
- `src/main/resources/docs/`: Markdown docs rendered by the server UI.
- `src/main/frontend/themes/default/`: Vaadin theme assets.
- `src/test/`: unit and integration tests; integration profile can use Testcontainers and H2 depending on test configuration.

Useful commands:

```bash
cd server && ./mvnw
cd server && ./mvnw clean verify -Pintegration-test
cd server && ./mvnw -Pproduction package -DskipTests
```

Notes:

- Java version is 25.
- Local private config should go in `application-private.yml` or environment variables.
- `FM_HUNDO_PROTOCOL_VERSION` is injected by CI for release compatibility checks.

## Middleware (`middleware/`)

`FM_Sentinel` is the player-local process between emulator plugins and the server.

Responsibilities:

- Read `credentials_FM_Team_Hundo.json` next to the executable.
- Listen on local TCP port `51155` for emulator/plugin JSON messages.
- Validate compatible protocol versions when components are stamped.
- Forward updates to the server API with the API key and optional test-mode header.
- Print actionable diagnostics for redirects, non-JSON responses, invalid credentials, and protocol mismatches.

Useful commands:

```bash
cd middleware && cargo test
cd middleware && cargo build
cd middleware && cargo build --release
```

## Emulator integrations

### BizHawk plugin (`bizhawk_plugin/`)

A .NET Framework 4.8 BizHawk external tool (`YGOFMPlugin.dll`). CI downloads BizHawk 2.10 on Windows before building because the project depends on BizHawk assemblies. Local builds require the same dependency layout or an equivalent BizHawk install.

### DuckStation patch (`duckstation_patch/`)

Contains `patch_duckstation.py` and source snippets under `src/core/`. The patch is intended for the pinned DuckStation commit documented in `duckstation_patch/README.md`. Because DuckStation licensing restricts distributing modified binaries/source as normal project code, this repo packages a patch workflow rather than a full fork.

## Commentary tools

### LiveStats (`commentary/livestats/`)

A JavaFX desktop app that reads the server API/WebSocket feeds and displays live team/player stats. It uses Gradle, Java 25, JavaFX 25, Jackson, ControlsFX, Log4j, and tests with JUnit/TestFX/Mockito.

Useful commands:

```bash
cd commentary/livestats && ./gradlew build
cd commentary/livestats && ./gradlew test
cd commentary/livestats && ./gradlew run
```

### OBS WebSocket controller (`commentary/obs_ws/`)

A Python 3.12+ package named `fm-hundo-obs`. It automates OBS scenes, MediaMTX stream discovery, browser overlays, acquisition scheduling, alert audio, Twitch profile image caching, credits scenes, and simulation-mode testing.

Important areas:

- `src/fm_hundo_obs/main.py`: CLI entry point and startup wiring.
- `config.py`, `config.example.yml`, `credits_scene.example.yml`: operator configuration model and examples.
- `obs.py`, `layout.py`, `managed_layout.py`, `audio.py`: OBS WebSocket abstractions and managed scene/source behavior.
- `api.py`, `models.py`, `mapping.py`: server API models and roster/team/card/duelist mapping.
- `overlay.py`, `static/overlay.html`, `static/credits.html`: local browser overlay server and assets.
- `scheduler.py`, `console.py`, `simulation.py`: alert scheduling, operator console, and simulated stream mode.
- `mediamtx.py`: MediaMTX Control API integration.
- `twitch_cache.py`: Twitch Get Users/App Access Token profile image cache.
- `tests/`: fake OBS/MediaMTX/API tests for managed layouts and behavior.

Useful commands (run the test suite from an activated virtual environment; install test dependencies with `python -m pip install -e '.[dev]'`):

```bash
cd commentary/obs_ws
python -m venv .venv
python -m pip install -e '.[dev]'
python -m pytest
fm-hundo-obs --config config.yml
```

Runtime files intentionally ignored by Git include `config.yml`, `credits_scene.yml`, `.venv/`, `logs/`, and `ygofm_portraits/`.

### Restream helper (`commentary/restream/`)

Contains Windows operator helper scripts for forwarding Twitch streams into MediaMTX paths expected by the OBS controller.

## Presentation (`presentation/`)

Contains a Beamer slide deck, speaker script, and PowerShell build script for a non-technical overview.

Useful command:

```powershell
.\presentation\build.ps1
```

## Prototype (`prototype/`)

A small Python prototype client retained as historical/reference material. Do not assume it is production-supported without checking with the user.

## CI and release flow

GitHub Actions currently:

1. Builds the BizHawk plugin DLL on Windows.
2. Builds Rust middleware on Windows/Linux across stable/beta/nightly and creates release artifacts from stable.
3. Runs server integration tests, builds a production server JAR, and verifies protocol stamp contents.
4. Builds/tests LiveStats and uploads its shaded JAR.
5. Installs/tests the OBS controller package.
6. Packages the DuckStation patch directory.
7. On `v*` tags, creates an attested GitHub release with all artifacts.

## Common change-impact checklist

- Protocol or JSON shape changes: update server DTOs/tests, middleware parsing/forwarding, plugin emitters, `FM_HUNDO_PROTOCOL_VERSION`, docs, and release notes/meta docs.
- Server API changes: update LiveStats and OBS controller clients/tests if they consume the endpoint.
- Team library/card logic changes: check server state tests, docs pages, and commentary display assumptions.
- OBS layout/scene/source changes: update `DECISIONS.md` when behavior is durable, fake OBS tests, README/config examples, and operator-facing names.
- Credentials/config changes: update examples and startup validation tests; do not commit secrets or local config.
- Build tooling changes: update this file, `AGENTS.md`, CI workflow, and README snippets.
