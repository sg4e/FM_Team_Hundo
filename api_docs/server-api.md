# Server REST API and WebSocket Firehoses

The Spring/Vaadin server exposes public JSON endpoints under `/api` and public WebSocket firehoses under `/firehose`. Browser UI routes such as `/`, `/players`, `/teams`, `/docs`, `/admin`, `/profile`, and `/widgets/stats/team` are Vaadin routes, not JSON API contracts, and are not described as machine APIs here.

All API responses are JSON. `/api/**`, `/firehose/**`, and `/widgets/**` are permitted without browser login by Spring Security. Mutating player updates still require an API key checked inside the controller.

See [`schemas/openapi.yaml`](schemas/openapi.yaml) for the machine-readable HTTP endpoint definitions.

## Common types

### `MessageType`

JSON values:

- `drop`
- `fuse`
- `ritual`
- `starchips`

### `EmuMessage`

Request DTO accepted by `POST /api/update`:

| Field | Type | Required | Default | Notes |
| --- | --- | --- | --- | --- |
| `type` | `MessageType` | yes | — | Unknown values make the update payload unreadable and return HTTP 400. |
| `value` | integer | yes | — | Card id for card events; starchip total for `starchips`. |
| `last_rng` | integer | no | `0` | Accepted snake_case field. |
| `now_rng` | integer | no | `0` | Accepted snake_case field. |
| `opp_id` | integer | no | `0` | Accepted snake_case field. |

### `PlayerUpdate`

Payload sent by `/firehose/player`:

| Field | Type | Notes |
| --- | --- | --- |
| `value` | integer | Card id or starchip value. |
| `source` | `MessageType` | Event source. |
| `participantId` | integer | Server database id of the player. |
| `time` | ISO-8601 instant string | Server ingestion time. |
| `lastRng` | integer | RNG before drop, or `0`. |
| `nowRng` | integer | RNG after drop, or `0`. |
| `opponentId` | integer | Opponent duelist id, or `0`. |

### `CardAcquisition`

Appears inside `LibraryUpdate.newAcquisitions`:

| Field | Type | Notes |
| --- | --- | --- |
| `cardId` | integer | Newly acquired card id. |
| `acquisitionTime` | ISO-8601 instant string | Acquisition/ingestion time. |
| `source` | `MessageType` | Drop, fuse, or ritual. |
| `playerId` | integer | Server database id of player credited for the acquisition. |
| `opponentId` | integer | Opponent duelist id, or `0`. |

### `LibraryUpdate`

Returned by `GET /api/library/{teamId}` and sent by `/firehose/team`:

| Field | Type | Notes |
| --- | --- | --- |
| `teamId` | integer | Team id. |
| `timestamp` | ISO-8601 instant string | Latest update time that produced this snapshot; empty team snapshots use Java `Instant.MIN`. |
| `totalStarchips` | integer | Sum of latest starchip totals per player on the team. |
| `uniqueCardCount` | integer | Number of unique obtained cards tracked for the team. |
| `newAcquisitions` | array of `CardAcquisition` | Cards newly added by this library update. Snapshot fetches may return an empty array. |
| `totalUnobtained` | integer | Obtainable cards not yet obtained. |
| `totalUnbuyables` | integer | Remaining unobtained cards that cannot be bought. |
| `totalCostOfBuyables` | integer | Starchip cost of remaining buyable cards. |
| `canAffordRemainingBuyables` | boolean | Whether team starchips cover `totalCostOfBuyables`. |
| `hasCompletedHundo` | boolean | Whether all unbuyables are obtained and remaining buyables are affordable. |
| `completionTime` | ISO-8601 instant string or null | First completion timestamp. |
| `bewdCount` | integer | Count of Blue-Eyes White Dragon acquisitions observed. |

## HTTP endpoints

### `GET /api/players`

Returns all users assigned to non-zero teams.

```json
[
  {"id": 101,"twitchId":"123456","name":"PlayerName","altAccount":null,"teamId":2}
]
```

### `GET /api/teams`

Returns all teams.

```json
[
  {"name":"Team Millennium","id":2}
]
```

### `GET /api/library/{teamId}`

Returns the latest `LibraryUpdate` snapshot for a team. If the team has no in-memory snapshot yet, the server creates an empty snapshot for that team id.

### `GET /api/credits`

Returns credits-scene data for commentary tooling.

```json
{
  "teams": [
    {"id":2,"name":"Team Millennium","completed":true,"completionTime":"2026-06-02T01:23:45Z","players":[{"id":101,"name":"PlayerName"}]}
  ],
  "allTeams": {
    "totalDrops": 1234,
    "totalFusions": 567,
    "totalRituals": 89,
    "twinHeadedThunderDragonFusions": 42
  },
  "teamStats": [
    {
      "teamId": 2,
      "dropCardCounts": [{"id":1,"count":3}],
      "fusionCardCounts": [{"id":466,"count":12}],
      "heishinDrops": 100,
      "seto3Drops": 55,
      "duelistDropCounts": [{"id":24,"count":10}]
    }
  ]
}
```

### `GET /api/protocol_version`

Returns the server release protocol version when stamped, otherwise `{}`.

```json
{"protocol_version":"1"}
```

### `GET /api/validate`

Validates an API key supplied in `X-API-Key`.

| Header | Required | Notes |
| --- | --- | --- |
| `X-API-Key` | yes | Player API key. Missing or invalid keys get HTTP 401. |

Success (`200`):

```json
{"result":"ok","message":"PlayerName","protocol_version":"1"}
```

Failure (`401`):

```json
{"result":"error","message":"Invalid API key"}
```

### `POST /api/update`

Accepts a batch of `EmuMessage` objects from middleware.

| Header | Required | Notes |
| --- | --- | --- |
| `X-API-Key` | yes | Player API key. Missing or invalid keys get HTTP 401. |
| `Content-Type: application/json` | yes | Body must be a JSON array. |
| `test` | no | Any present value other than trimmed case-insensitive `false` enables test mode. |

Validation rules:

- Body must be a readable JSON array of `EmuMessage` objects.
- Array must be non-empty.
- Card events (`drop`, `fuse`, `ritual`) must not include unobtainable cards.
- Card ids must be in `1..=722`.
- `starchips` values must be less than `1,000,000`.
- Non-test updates require the API-key user to be assigned to a non-zero team.

Responses:

| Status | Body | Cause |
| --- | --- | --- |
| `200` | `{"result":"ok","message":"PlayerName"}` | Accepted and persisted/broadcast. |
| `200` | `{"result":"ok","message":"PlayerName","test":true}` | Test mode validation passed; no persistence/broadcast. |
| `400` | `{"result":"error","message":"Invalid update payload"}` | Unreadable JSON/body/enum. |
| `400` | `{"result":"error","message":"No updates provided"}` | Empty JSON array. |
| `400` | `{"result":"error","message":"Update contains unobtainable cards"}` | At least one card event references an unobtainable card. |
| `400` | `{"result":"error","message":"Invalid card id"}` | Card id is `<= 0` or `> 722`. |
| `400` | `{"result":"error","message":"Total starchips cannot be equal to or exceed 1000000"}` | Starchip value is at least `1,000,000`. |
| `401` | `{"result":"error","message":"Invalid API key"}` | Missing/invalid API key. |
| `403` | `{"result":"error","message":"User is not assigned to a team"}` | Non-test update from a user on team `0`. |

## WebSocket firehoses

### `/firehose/player`

Broadcasts each persisted `PlayerUpdate` as an individual text frame containing one JSON object.

Properties:

- Public WebSocket endpoint; no API key required.
- No snapshot is sent on connect.
- Client text messages are echoed back to that same session by the current handler. Production consumers should treat this endpoint as server-to-client and not send messages.
- Starchip updates are broadcast here as `source: "starchips"` because every persisted update is broadcast.

Example frame:

```json
{"value":1,"source":"drop","participantId":101,"time":"2026-06-02T01:23:45.123Z","lastRng":93051,"nowRng":142875,"opponentId":24}
```

### `/firehose/team`

Broadcasts `LibraryUpdate` snapshots whenever a team library changes.

Properties:

- Public WebSocket endpoint; no API key required.
- No snapshot is sent on connect; clients should call `GET /api/library/{teamId}` for initial state.
- One text frame contains one complete `LibraryUpdate` JSON object.
- `newAcquisitions` contains only cards newly credited by that update. Starchip-only changes may update totals and send an empty `newAcquisitions` list.

Example frame:

```json
{
  "teamId": 2,
  "timestamp": "2026-06-02T01:23:45.123Z",
  "totalStarchips": 1250,
  "uniqueCardCount": 17,
  "newAcquisitions": [
    {"cardId":1,"acquisitionTime":"2026-06-02T01:23:45.123Z","source":"drop","playerId":101,"opponentId":24}
  ],
  "totalUnobtained": 704,
  "totalUnbuyables": 321,
  "totalCostOfBuyables": 500000,
  "canAffordRemainingBuyables": false,
  "hasCompletedHundo": false,
  "completionTime": null,
  "bewdCount": 1
}
```
