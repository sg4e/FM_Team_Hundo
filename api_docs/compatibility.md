# Compatibility and Protocol Versioning

FM Team Hundo has two kinds of compatibility:

1. **Release wire compatibility** between emulator plugins, `FM_Sentinel`, and the server.
2. **Consumer compatibility** for public server endpoints and commentary firehoses.

## Release protocol stamp

CI stamps compatible release artifacts with `FM_HUNDO_PROTOCOL_VERSION` from `.github/workflows/build.yml`.

Current documented value: `1`.

Stamped components:

- Server: `fm-hundo-build.properties` contains `protocol.version=<value>` and `/api/protocol_version` exposes it.
- Middleware: `middleware/build.rs` copies `FM_HUNDO_PROTOCOL_VERSION` into the Rust build as `option_env!("FM_HUNDO_PROTOCOL_VERSION")`.
- BizHawk plugin: the C# project injects the value into `BuildProtocolVersion.Value`.
- DuckStation patch: the patch source currently carries a `PROTOCOL_VERSION` constant and sends it in the hello message; keep it aligned with `FM_HUNDO_PROTOCOL_VERSION` when packaging protocol-compatible releases.

## Enforcement rules

- Local development builds may be unstamped. Unstamped middleware skips protocol-version enforcement so local components can be tested together.
- A stamped middleware compares itself to the server protocol during startup validation.
- A stamped middleware compares the emulator plugin hello `protocol_version` to the server protocol when the plugin provides it.
- Exact string equality is required. Treat the value as opaque; do not parse it as semantic versioning.
- On a proven mismatch, middleware prints a mismatch diagnostic and exits.

## When to bump `FM_HUNDO_PROTOCOL_VERSION`

Bump the protocol version when a release artifact would no longer interoperate safely with another artifact from the previous release. Examples:

- Renaming, removing, or changing the meaning of plugin TCP fields.
- Adding a required plugin TCP field with no backward-compatible default.
- Changing server validation such that older middleware/plugin messages are rejected.
- Changing `/api/update` request semantics in a way that old middleware cannot satisfy.

Do **not** bump only for additive server read endpoints, new optional response fields that consumers can ignore, documentation-only changes, UI-only changes, or internal refactors.

## Documentation requirements

Every compatibility-affecting change must update:

- [`plugin-middleware-protocol.md`](plugin-middleware-protocol.md) for local TCP contract changes.
- [`middleware-server-api.md`](middleware-server-api.md) for middleware HTTP behavior changes.
- [`server-api.md`](server-api.md) and [`schemas/openapi.yaml`](schemas/openapi.yaml) for server HTTP/WebSocket contract changes.
- Root `DECISIONS.md` when the decision should remain durable for future agents.
