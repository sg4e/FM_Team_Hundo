# Emulator Plugin → FM_Sentinel Protocol

This protocol connects supported emulator integrations to the local Rust middleware (`FM_Sentinel`). It is the first wire contract in FM Team Hundo.

## Transport

- **Protocol:** TCP stream on loopback.
- **Address:** `127.0.0.1:51155`.
- **Framing:** newline-delimited UTF-8 JSON. Every message is one JSON object followed by `\n`.
- **Connection model:** `FM_Sentinel` listens continuously but handles one emulator connection at a time. When the client disconnects, it returns to accepting another connection.
- **Ordering:** messages are processed in stream order. The middleware batches whatever it has received before forwarding to the server.
- **Backpressure:** middleware buffers up to 1000 messages per batch before posting to the server.

## Version handshake

The first plugin message SHOULD be a `hello` object:

```json
{"type":"hello","protocol_version":"1"}
```

Fields:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `type` | string | yes | Must be `hello` for the handshake. |
| `protocol_version` | string | release builds only | Opaque release protocol value stamped from `FM_HUNDO_PROTOCOL_VERSION`. Local/unstamped builds may omit it. |

Handshake behavior:

- `FM_Sentinel` consumes the first `hello` and does **not** forward it to the server.
- If the first message is not `hello`, malformed, or lacks `protocol_version`, middleware logs a warning and continues unless stamped compatibility enforcement has enough information to prove a mismatch.
- When server, middleware, and plugin are stamped, the plugin version must exactly match the server protocol version or middleware exits.

## Event messages

All non-handshake messages use this base shape:

```json
{"type":"drop","value":1,"last_rng":123456,"now_rng":123457,"opp_id":24}
```

| Field | Type | Required | Applies to | Notes |
| --- | --- | --- | --- | --- |
| `type` | string enum | yes | all events | One of `drop`, `fuse`, `ritual`, or `starchips`. |
| `value` | integer | yes | all events | Card id for card events; current starchip total for `starchips`. |
| `last_rng` | integer | no | `drop` | Previous RNG value observed by the plugin. Server defaults missing values to `0`. |
| `now_rng` | integer | no | `drop` | Current RNG value observed by the plugin. Server defaults missing values to `0`. |
| `opp_id` | integer | no | card events | Opponent duelist id. Server defaults missing values to `0`. |

### Event type semantics

| `type` | Meaning | `value` meaning | Producer notes |
| --- | --- | --- | --- |
| `drop` | A duel reward/drop changed. | Dropped card id. | BizHawk includes `last_rng`, `now_rng`, and `opp_id`; DuckStation includes RNG fields and `opp_id`. |
| `fuse` | Player fusion count increased. | Resulting card id from fusion/equip/ritual memory. | Serialized as `fuse` on the wire; some Java enum string rendering displays `fusion` internally, but JSON values accepted/emitted by consumers are `fuse`. |
| `ritual` | A ritual monster appeared in a player monster slot. | Ritual monster card id. | Plugins suppress repeat ritual ids until emulator/plugin state is reset. |
| `starchips` | Player starchip total changed. | Current total starchips. | No RNG/opponent fields are sent by current plugins. |

## Examples

### BizHawk startup then event batch over TCP

```jsonl
{"type":"hello","protocol_version":"1"}
{"type":"drop","value":1,"last_rng":93051,"now_rng":142875,"opp_id":24}
{"type":"starchips","value":1250}
```

### DuckStation fusion

```jsonl
{"type":"hello","protocol_version":"1"}
{"type":"fuse","value":466,"opp_id":0}
```

## Validation deferred to later layers

The local TCP layer is intentionally lightweight. Card-id range checks, unobtainable-card rejection, starchip caps, API-key authentication, and team membership checks happen when middleware posts to the server.
