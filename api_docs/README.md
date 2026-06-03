# FM Team Hundo API Documentation

This directory is the source of truth for FM Team Hundo API contracts. It is intentionally written as small Markdown source files plus an OpenAPI precursor so both humans and AI agents can update it without a generated-docs workflow.

## Documentation format

- **Markdown (`*.md`)** is the canonical narrative format. Keep each API layer in its own file so future changes have a small edit surface.
- **OpenAPI 3.2 YAML (`schemas/openapi.yaml`)** is the canonical machine-readable precursor for server HTTP endpoints. It can be rendered to HTML with Redoc, Swagger UI, Scalar, MkDocs plugins, or GitHub Pages later.
- **No generated HTML is committed.** If rendered docs are desired, publish them from CI or another out-of-repo artifact location.

Suggested build and rendering commands:

```bash
# Preview Markdown docs with MkDocs.
python -m pip install mkdocs PyYAML
python -m mkdocs serve --config-file api_docs/mkdocs.yml

# Render the OpenAPI precursor as a standalone HTML file.
npx @redocly/cli build-docs api_docs/schemas/openapi.yaml -o /tmp/fm-hundo-api.html
```

## Map of API layers

| Layer | Doc | Primary source files |
| --- | --- | --- |
| Emulator plugin → middleware | [`plugin-middleware-protocol.md`](plugin-middleware-protocol.md) | `bizhawk_plugin/src/Plugin.cs`, `duckstation_patch/src/core/fm_team_hundo.cpp`, `middleware/src/main.rs` |
| Middleware → server | [`middleware-server-api.md`](middleware-server-api.md) | `middleware/src/main.rs`, `server/src/main/java/moe/maika/fmteamhundo/api/ApiController.java` |
| Server REST and WebSocket firehoses | [`server-api.md`](server-api.md), [`openapi.md`](openapi.md), and [`schemas/openapi.yaml`](schemas/openapi.yaml) | `server/src/main/java/moe/maika/fmteamhundo/api/`, `server/src/main/java/moe/maika/fmteamhundo/state/`, `server/src/main/java/moe/maika/fmteamhundo/config/WsConfig.java` |
| Commentary/overlay local APIs | [`commentary-overlay-api.md`](commentary-overlay-api.md) | `commentary/obs_ws/src/fm_hundo_obs/overlay.py`, `commentary/obs_ws/src/fm_hundo_obs/api.py`, `commentary/livestats/src/main/java/moe/maika/fmteamhundo/livestats/client/` |
| Compatibility and release protocol | [`compatibility.md`](compatibility.md) | `.github/workflows/build.yml`, `server/src/main/resources/fm-hundo-build.properties`, `middleware/build.rs` |

## Maintenance checklist for API changes

When a change alters any request, response, emitted event, WebSocket payload, local TCP JSON message, header, authentication rule, route, status code, firehose semantics, or protocol-version compatibility rule:

1. Update the relevant Markdown file in this directory.
2. Update `schemas/openapi.yaml` when a server HTTP endpoint or DTO changes.
3. Update examples when field names, valid values, or error bodies change.
4. If the change is incompatible across emulator plugins, middleware, or server release artifacts, update the compatibility doc and bump `FM_HUNDO_PROTOCOL_VERSION` in `.github/workflows/build.yml`.
5. Update root meta docs (`AGENTS.md`, `PROJECT_CONTEXT.md`, and `DECISIONS.md`) when the API architecture or durable workflow expectations change.
6. Run the smallest relevant tests for touched components, then broader checks when practical. For docs-only changes, run `python api_docs/scripts/check_openapi_sync.py` and `python -m mkdocs build --strict --config-file api_docs/mkdocs.yml`.
