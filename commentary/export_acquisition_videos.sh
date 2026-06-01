#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: export_acquisition_videos.sh --host HOST --user USER [--database DATABASE] [--output FILE]

Exports resolved AcquisitionVideo rows from the MySQL backend as JSON Lines for
make_team_montage.py.

Required:
  --host HOST          MySQL host to connect to.
  --user USER          MySQL username. The script prompts for the password.

Optional:
  --database DATABASE  MySQL database name. Defaults to fm_team_hundo.
  --output FILE        Output JSONL path. Defaults to acquisition_videos.jsonl.
  --port PORT          MySQL port. Defaults to 3306.
  -h, --help           Show this help.

Example:
  ./commentary/export_acquisition_videos.sh \
    --host 10.0.0.196 \
    --user fm_user \
    --output acquisitions.jsonl
USAGE
}

HOST=""
USER=""
DATABASE="fm_team_hundo"
OUTPUT="acquisition_videos.jsonl"
PORT="3306"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host)
            [[ $# -ge 2 ]] || { echo "error: --host requires a value" >&2; exit 2; }
            HOST="$2"
            shift 2
            ;;
        --user)
            [[ $# -ge 2 ]] || { echo "error: --user requires a value" >&2; exit 2; }
            USER="$2"
            shift 2
            ;;
        --database)
            [[ $# -ge 2 ]] || { echo "error: --database requires a value" >&2; exit 2; }
            DATABASE="$2"
            shift 2
            ;;
        --output)
            [[ $# -ge 2 ]] || { echo "error: --output requires a value" >&2; exit 2; }
            OUTPUT="$2"
            shift 2
            ;;
        --port)
            [[ $# -ge 2 ]] || { echo "error: --port requires a value" >&2; exit 2; }
            PORT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -z "$HOST" || -z "$USER" ]]; then
    echo "error: --host and --user are required" >&2
    usage >&2
    exit 2
fi

if ! command -v mysql >/dev/null 2>&1; then
    echo "error: mysql command not found. Install the MySQL client and try again." >&2
    exit 1
fi

read -rsp "MySQL password: " MYSQL_PASSWORD
echo >&2

mysql_scalar() {
    MYSQL_PWD="$MYSQL_PASSWORD" mysql \
        --host="$HOST" \
        --port="$PORT" \
        --user="$USER" \
        --database="$DATABASE" \
        --batch \
        --raw \
        --skip-column-names \
        --execute="$1"
}

column_for() {
    local table="$1"
    shift
    local names_csv=""
    local field_args=""
    local name
    for name in "$@"; do
        local escaped="${name//\'/\'\'}"
        if [[ -n "$names_csv" ]]; then
            names_csv+=", "
            field_args+=", "
        fi
        names_csv+="'${escaped}'"
        field_args+="'${escaped}'"
    done

    local query="SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${table}' AND COLUMN_NAME IN (${names_csv}) ORDER BY FIELD(COLUMN_NAME, ${field_args}) LIMIT 1"
    local column
    column=$(mysql_scalar "$query")
    if [[ -z "$column" ]]; then
        echo "error: could not find any expected column on ${table}: $*" >&2
        exit 1
    fi
    printf '`%s`' "$column"
}

AV_TEAM_ID=$(column_for acquisition_videos teamId team_id)
AV_CARD_ID=$(column_for acquisition_videos cardId card_id)
AV_PLAYER_ID=$(column_for acquisition_videos playerId player_id)
AV_ACQUISITION_TIME=$(column_for acquisition_videos acquisitionTime acquisition_time)
AV_OPPONENT_ID=$(column_for acquisition_videos opponentId opponent_id)
AV_SOURCE=$(column_for acquisition_videos source)
AV_STATUS=$(column_for acquisition_videos status)
AV_TWITCH_VIDEO_ID=$(column_for acquisition_videos twitchVideoId twitch_video_id)
AV_OFFSET_SECONDS=$(column_for acquisition_videos offsetSeconds offset_seconds)
AV_TWITCH_CHANNEL_LOGIN=$(column_for acquisition_videos twitchChannelLogin twitch_channel_login)
AV_STREAM_STARTED_AT=$(column_for acquisition_videos streamStartedAt stream_started_at)
TEAM_TEAM_ID=$(column_for teams teamId team_id)
USER_DATABASE_ID=$(column_for users databaseId database_id)

TMP_OUTPUT="${OUTPUT}.tmp.$$"
cleanup() {
    rm -f "$TMP_OUTPUT"
}
trap cleanup EXIT

QUERY=$(cat <<SQL
SELECT JSON_OBJECT(
    'teamId', av.${AV_TEAM_ID},
    'teamName', COALESCE(t.name, CONCAT('Team ', av.${AV_TEAM_ID})),
    'cardId', av.${AV_CARD_ID},
    'playerId', av.${AV_PLAYER_ID},
    'playerName', COALESCE(u.name, CONCAT('Player ', av.${AV_PLAYER_ID})),
    'opponentId', av.${AV_OPPONENT_ID},
    'source', av.${AV_SOURCE},
    'acquisitionTime', DATE_FORMAT(av.${AV_ACQUISITION_TIME}, '%Y-%m-%dT%H:%i:%s.%fZ'),
    'twitchVideoId', av.${AV_TWITCH_VIDEO_ID},
    'offsetSeconds', av.${AV_OFFSET_SECONDS},
    'twitchChannelLogin', av.${AV_TWITCH_CHANNEL_LOGIN},
    'streamStartedAt', IF(av.${AV_STREAM_STARTED_AT} IS NULL, NULL, DATE_FORMAT(av.${AV_STREAM_STARTED_AT}, '%Y-%m-%dT%H:%i:%s.%fZ'))
)
FROM acquisition_videos av
LEFT JOIN teams t ON t.${TEAM_TEAM_ID} = av.${AV_TEAM_ID}
LEFT JOIN users u ON u.${USER_DATABASE_ID} = av.${AV_PLAYER_ID}
WHERE av.${AV_STATUS} = 'RESOLVED'
  AND av.${AV_TWITCH_VIDEO_ID} IS NOT NULL
  AND av.${AV_OFFSET_SECONDS} IS NOT NULL
ORDER BY av.${AV_TEAM_ID} ASC, av.${AV_CARD_ID} ASC;
SQL
)

MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    --host="$HOST" \
    --port="$PORT" \
    --user="$USER" \
    --database="$DATABASE" \
    --batch \
    --raw \
    --skip-column-names \
    --execute="$QUERY" > "$TMP_OUTPUT"

mv "$TMP_OUTPUT" "$OUTPUT"
trap - EXIT
cleanup

ROW_COUNT=$(wc -l < "$OUTPUT" | tr -d ' ')
echo "Exported ${ROW_COUNT} acquisition video rows to ${OUTPUT}" >&2
