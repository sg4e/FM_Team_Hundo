# FM_Sentinel → Server API

`FM_Sentinel` converts emulator newline-delimited JSON into authenticated HTTP requests to the collection server.

## Configuration file

The middleware reads `credentials_FM_Team_Hundo.json` from the executable/current working directory at startup.

```json
{
  "key": "player-api-key",
  "url": "https://example.org/api/",
  "username": "PlayerName"
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `key` | string | yes | Sent as `X-API-Key` to `/api/validate` and `/api/update`. Must never be committed. |
| `url` | string | yes | Base API URL. Middleware joins `validate` and `update` onto this URL, so both `https://host/api` and `https://host/api/` work. |
| `username` | string | yes | Compared with the server validation response for user-facing diagnostics. |

## Startup validation

Before listening for emulator connections, middleware calls:

```http
GET /api/validate
X-API-Key: <key>
```

Successful response:

```json
{"result":"ok","message":"PlayerName","protocol_version":"1"}
```

Invalid key response:

```json
{"result":"error","message":"Invalid API key"}
```

Behavior:

- Middleware disables HTTP redirects and treats redirects as operator-facing misconfiguration.
- Middleware requires a JSON response body. Non-JSON or unparsable responses are fatal during startup.
- If `result` is `ok`, middleware validates its stamped protocol version against `protocol_version` when both are available.
- If `result` is `error` or any unexpected value, middleware exits.

## Forwarding updates

Middleware batches received emulator event JSON objects and posts them as a JSON array:

```http
POST /api/update
X-API-Key: <key>
Content-Type: application/json
```

```json
[
  {"type":"drop","value":1,"last_rng":93051,"now_rng":142875,"opp_id":24},
  {"type":"starchips","value":1250}
]
```

Responses use the common result envelope:

```json
{"result":"ok","message":"PlayerName"}
```

or:

```json
{"result":"error","message":"Invalid card id"}
```

## Test mode

Running middleware with `--test` adds the HTTP header below to validation and update calls:

```http
test: true
```

Server behavior in test mode:

- `/api/update` validates the API key, non-empty payload, card ids, unobtainable cards, and starchip cap.
- The server returns success with `"test": true` but does not persist updates or update team library state.
- Team assignment is not required in test mode.

Any present `test` header whose trimmed value is not case-insensitive `false` enables test mode.

## Retry and failure behavior

- Startup validation failures are fatal.
- Update-post transport failures, redirects, non-JSON bodies, and invalid JSON API envelopes are logged; middleware waits 15 seconds and retries the next send loop.
- Server-side `result: "error"` update responses are logged and the current buffer is cleared after the HTTP response is processed.
- Raw emulator messages that middleware cannot parse for console diagnostics are still included in the forwarded batch; the server may reject the entire payload with `Invalid update payload`.
