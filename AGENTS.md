# FM Team Hundo Agent Guide

This file is the repo-wide orientation and instruction sheet for agentic AI work in FM Team Hundo. It applies to the entire repository.

## Start every task here

1. Read this file first, then read [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md) for the system map and [`DECISIONS.md`](DECISIONS.md) for durable decisions.
2. Use `rg --files` / `rg` for discovery. Do not use recursive `ls -R` or `grep -R` in this repo.
3. Identify which subproject(s) your change touches and run the smallest relevant tests first; run broader checks before finishing when practical.
4. If your change alters architecture, protocol behavior, operator workflow, build commands, or durable design choices, update the root meta docs in the same commit:
   - `AGENTS.md` for agent instructions and workflow expectations.
   - `PROJECT_CONTEXT.md` for project map, component responsibilities, or commands.
   - `DECISIONS.md` for decisions that future agents must not reverse without user confirmation.
5. If your change alters any API layer (emulator plugin JSON, middleware HTTP behavior, server REST endpoints, WebSocket firehoses, commentary overlay routes/events, request/response DTOs, headers, auth rules, validation rules, or protocol-version compatibility), update `api_docs/` in the same commit. Update `api_docs/schemas/openapi.yaml` for server HTTP API changes and the relevant Markdown page for narrative/firehose/local protocol changes.
6. Keep generated artifacts, local credentials, virtualenvs, build outputs, and IDE state out of commits.

## Repository purpose in one paragraph

FM Team Hundo coordinates a team race/community challenge for *Yu-Gi-Oh! Forbidden Memories*. Emulator plugins detect drops/fusions/rituals and send local JSON events to the Rust `FM_Sentinel` middleware. The middleware authenticates with and posts to the Java/Vaadin/Spring server, which persists player updates, builds team library progress, exposes UI/API/WebSocket feeds, and stamps protocol-compatible release artifacts. Commentary tools consume those feeds for live production: a JavaFX LiveStats desktop app and a Python OBS/MediaMTX controller for restream layouts and acquisition alerts.

## Subproject quick map

| Path | What it is | Primary stack | Most relevant checks |
| --- | --- | --- | --- |
| `api_docs/` | Canonical source documentation for emulator/middleware/server/commentary API contracts plus OpenAPI 3.2 precursor. | Markdown, OpenAPI YAML, MkDocs. | `python -m mkdocs build --strict --config-file api_docs/mkdocs.yml`; CI also runs Schemathesis against the packaged server artifact. |
| `server/` | Main Spring Boot + Vaadin web app, API, persistence, security, team library state, docs pages, release JAR. | Java 25, Maven wrapper, Vaadin 25, Spring Boot, JPA, MySQL/H2 tests, Testcontainers. | `cd server && ./mvnw clean verify -Pintegration-test` |
| `middleware/` | `FM_Sentinel`, a local TCP listener for emulator plugins that forwards authenticated player updates to the server. | Rust 2024, Tokio, Reqwest, Clap, Serde. | `cd middleware && cargo test` and `cargo build` |
| `bizhawk_plugin/` | BizHawk external tool DLL that reads Forbidden Memories memory and emits JSON to `FM_Sentinel`. | C#/.NET Framework 4.8, BizHawk APIs. | `cd bizhawk_plugin/src && dotnet build -c Release` with BizHawk dependencies present |
| `duckstation_patch/` | Patch payload and script for injecting equivalent FM Team Hundo tracking into a pinned DuckStation source checkout. | Python patch script plus C++ source snippets. | `python duckstation_patch/patch_duckstation.py <duckstation checkout>` |
| `commentary/livestats/` | Desktop stats display that subscribes to server APIs/WebSockets and renders team/player progress. | Java 25, Gradle wrapper, JavaFX 25. | `cd commentary/livestats && ./gradlew build` |
| `commentary/obs_ws/` | OBS WebSocket + MediaMTX restream controller, overlays, credits scene, alert scheduling, simulation mode. | Python 3.12+, aiohttp, simpleobsws, pytest. | Use a venv, install with `python -m pip install -e '.[dev]'`, then run `python -m pytest` |
| `commentary/restream/` | Operator helper script for forwarding Twitch streams into MediaMTX. | Windows batch / ffmpeg workflows. | Manual/operator validation |
| `presentation/` | Non-technical Beamer presentation and speaker script. | LaTeX, PowerShell. | `./presentation/build.ps1` on Windows/PowerShell with TeX installed |
| `prototype/` | Small Python prototype client retained for reference. | Python. | Manual only |

## Cross-component contracts

- The emulator plugins and middleware speak newline-delimited JSON over local TCP port `51155`.
- The middleware posts authenticated updates to the server URL found in `credentials_FM_Team_Hundo.json`; that credentials file is user-local and must never be committed.
- Release builds stamp compatible server, middleware, and plugin artifacts with `FM_HUNDO_PROTOCOL_VERSION` from `.github/workflows/build.yml`. Bump it only when the wire protocol between those components becomes incompatible, and update `api_docs/compatibility.md` plus any affected API-layer docs in the same change.
- Unstamped local development builds intentionally skip protocol enforcement so local components can be tested together.
- `server/src/main/resources/application.yml` imports optional `application-private.yml`; private Twitch/OAuth/database configuration belongs there or in environment variables, not in Git.
- The root license is AGPL-3.0-or-later for this project unless a file or third-party dependency states otherwise. The DuckStation patch payload carries its own SPDX header and upstream license constraints.

## Build and test expectations

Prefer the wrapper provided by each subproject. For `commentary/obs_ws`, create/activate a virtual environment before installing test dependencies and running pytest:

```bash
cd server && ./mvnw clean verify -Pintegration-test
cd middleware && cargo test
cd middleware && cargo build
cd commentary/livestats && ./gradlew build
cd commentary/obs_ws && python -m venv .venv
cd commentary/obs_ws && python -m pip install -e '.[dev]'
cd commentary/obs_ws && python -m pytest
python -m mkdocs build --strict --config-file api_docs/mkdocs.yml
```

Additional targeted checks:

```bash
cd server && ./mvnw -Pproduction package -DskipTests
cd bizhawk_plugin/src && dotnet build -c Release
```

Document any skipped checks and the reason, especially when a check requires Windows, OBS, BizHawk, DuckStation source, Docker/Testcontainers, Java 25, Python 3.12, a TeX distribution, or external credentials.

## Coding and documentation conventions

- Keep Java code compatible with Java 25. Both Maven and Gradle projects configure Java 25 toolchains; do not silently downgrade language features.
- Keep Python OBS code compatible with Python 3.12+. Prefer async-friendly code; tests use `pytest` and `pytest-asyncio` with `asyncio_mode = auto`.
- Keep Rust middleware compatible with the Rust 2024 edition specified in `middleware/Cargo.toml`.
- Do not add broad `try`/`catch` blocks around imports.
- Prefer explicit failure for operator misconfiguration over silent fallback when startup validation already expects fail-fast behavior.
- Preserve stable OBS source/scene names unless intentionally migrating operator setups; OBS names are external operator-facing state.
- Do not add automated tests that load or assert on agent-facing meta files (`AGENTS.md`, `PROJECT_CONTEXT.md`, or `DECISIONS.md`); review those files directly when changed.
- Treat `api_docs/` as the canonical project API reference. Keep it in sync with code changes to plugin/middleware/server/commentary APIs; do not commit rendered HTML output.
- Do not commit `server/node_modules/`, `target/`, `build/`, `bin/`, `.venv/`, OBS local configs, credentials, logs, generated PDFs, or downloaded emulator/source trees.

## Updating durable decisions

Use [`DECISIONS.md`](DECISIONS.md) for design choices that future agents should treat as settled. Add a decision when a user explicitly chooses behavior, when tests encode a non-obvious policy, or when reversing the choice would break operator workflow or cross-component compatibility. Include the affected path/component in the heading.
