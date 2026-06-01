#!/usr/bin/env python3
"""Build a per-team acquisition montage from exported AcquisitionVideo data.

The companion export_acquisition_videos.sh script writes JSON Lines that include
resolved Twitch VOD IDs and offsets. This script downloads each needed VOD once
with yt-dlp, builds a seek-friendly short-GOP intermediate cache for each VOD,
cuts one ffmpeg highlight per acquired card, overlays a readable label, and
concatenates those highlights in cardId order by default, or in acquisition-time
order with --timeline. Rendering uses 60 fps NVENC output and can run multiple
independent clip jobs concurrently.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CARDINFO = SCRIPT_DIR / "obs_ws" / "cardinfo.json"
DEFAULT_DUELISTINFO = SCRIPT_DIR / "obs_ws" / "duelistinfo.json"
DEFAULT_CACHE_DIR = SCRIPT_DIR / "montage_cache"
DEFAULT_OUTPUT_DIR = SCRIPT_DIR / "montage_output"

SOURCE_LABELS = {
    "DROP": "Drop",
    "drop": "Drop",
    "FUSE": "Fusion",
    "fusion": "Fusion",
    "RITUAL": "Ritual",
    "ritual": "Ritual",
}
OUTPUT_FRAME_RATE = 60
INTERMEDIATE_TAG = "1080p60_gop2"


@dataclass(frozen=True)
class ClipJob:
    index: int
    total: int
    card_id: int
    input_path: Path
    output_path: Path
    label_path: Path
    count_label_path: Path | None
    label: str
    offset_seconds: float
    shift_seconds: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a team acquisition montage from exported AcquisitionVideo JSONL data."
    )
    parser.add_argument("data", type=Path, help="JSONL data file from export_acquisition_videos.sh")
    parser.add_argument("team", help="Team name whose montage video should be created")
    parser.add_argument("--yt-dlp", default="yt-dlp", help="Path to yt-dlp executable (default: yt-dlp)")
    parser.add_argument(
        "--yt-dlp-jobs",
        type=int,
        help="Number of download fragments yt-dlp should use via -N/--concurrent-fragments.",
    )
    parser.add_argument("--ffmpeg", default="ffmpeg", help="Path to ffmpeg executable (default: ffmpeg)")
    parser.add_argument(
        "--shift-times",
        type=float,
        default=0.0,
        help="Default seconds to shift acquisition timestamps later or earlier before clipping (default: 0.0)",
    )
    parser.add_argument(
        "--vod-shift",
        action="append",
        default=[],
        metavar="VOD_ID=SECONDS",
        help=(
            "Override --shift-times for one Twitch VOD. May be repeated, e.g. "
            "--vod-shift 2784610972=-4.5. VOD IDs may include or omit the leading 'v'."
        ),
    )
    parser.add_argument(
        "--per-card",
        type=float,
        default=5.0,
        help="Seconds per card acquisition highlight (default: 5.0)",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=1,
        help="Number of ffmpeg clip renders to run concurrently (default: 1)",
    )
    parser.add_argument(
        "--timeline",
        action="store_true",
        help="Order clips by acquisition time instead of cardId.",
    )
    parser.add_argument(
        "--show-count",
        action="store_true",
        help="Show the clip number in the montage's top-right corner.",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=DEFAULT_CACHE_DIR,
        help=f"Directory for downloaded VODs and temporary clips (default: {DEFAULT_CACHE_DIR})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Directory for rendered montage videos (default: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument(
        "--cardinfo",
        type=Path,
        default=DEFAULT_CARDINFO,
        help=f"Path to cardinfo.json (default: {DEFAULT_CARDINFO})",
    )
    parser.add_argument(
        "--duelistinfo",
        type=Path,
        default=DEFAULT_DUELISTINFO,
        help=f"Path to duelistinfo.json (default: {DEFAULT_DUELISTINFO})",
    )
    return parser.parse_args()


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def resolve_executable(path: str, label: str) -> str:
    if "/" in path:
        candidate = Path(path)
        if candidate.exists() and candidate.is_file():
            return str(candidate)
        fail(f"{label} executable not found at {path}")

    resolved = shutil.which(path)
    if resolved:
        return resolved
    fail(f"{label} executable '{path}' not found on PATH")


def load_json(path: Path) -> Any:
    if not path.exists():
        fail(f"required file not found: {path}")
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def load_card_names(path: Path) -> dict[int, str]:
    data = load_json(path)
    try:
        return {int(item["cardId"]): str(item["cardName"]) for item in data}
    except (TypeError, KeyError, ValueError) as exc:
        fail(f"could not parse card names from {path}: {exc}")


def load_duelist_names(path: Path) -> dict[int, str]:
    data = load_json(path)
    try:
        return {int(item["duelistId"]): str(item["duelist"]) for item in data}
    except (TypeError, KeyError, ValueError) as exc:
        fail(f"could not parse duelist names from {path}: {exc}")


def load_rows(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        fail(f"data file not found: {path}")

    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                row = json.loads(stripped)
            except json.JSONDecodeError as exc:
                fail(f"invalid JSON on {path}:{line_number}: {exc}")
            if not isinstance(row, dict):
                fail(f"expected JSON object on {path}:{line_number}")
            rows.append(row)
    return rows


def select_team_rows(rows: list[dict[str, Any]], requested_team: str) -> tuple[str, list[dict[str, Any]]]:
    exact = [row for row in rows if str(row.get("teamName", "")) == requested_team]
    if exact:
        return requested_team, exact

    matching_names = sorted({str(row.get("teamName", "")) for row in rows if str(row.get("teamName", "")).casefold() == requested_team.casefold()})
    if len(matching_names) == 1:
        team_name = matching_names[0]
        return team_name, [row for row in rows if str(row.get("teamName", "")) == team_name]
    if len(matching_names) > 1:
        fail(f"team name {requested_team!r} is ambiguous; matches: {', '.join(matching_names)}")

    available = sorted({str(row.get("teamName", "")) for row in rows if row.get("teamName")})
    preview = ", ".join(available[:20])
    suffix = " ..." if len(available) > 20 else ""
    fail(f"no rows found for team {requested_team!r}. Available teams: {preview}{suffix}")



def parse_acquisition_time(row: dict[str, Any]) -> datetime:
    value = row.get("acquisitionTime")
    if not value:
        fail(f"row for cardId {row.get('cardId', 'unknown')} is missing required field 'acquisitionTime'")
    text = str(value)
    if text.endswith("Z"):
        text = f"{text[:-1]}+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exc:
        fail(f"row for cardId {row.get('cardId', 'unknown')} has invalid acquisitionTime {value!r}: {exc}")
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def card_sort_key(row: dict[str, Any]) -> tuple[int, datetime]:
    try:
        card_id = int(row.get("cardId", 0))
    except (TypeError, ValueError):
        card_id = 0
    return (card_id, parse_acquisition_time(row))


def timeline_sort_key(row: dict[str, Any]) -> tuple[datetime, int]:
    try:
        card_id = int(row.get("cardId", 0))
    except (TypeError, ValueError):
        card_id = 0
    return (parse_acquisition_time(row), card_id)

def source_label(value: Any) -> str:
    text = str(value or "").strip()
    return SOURCE_LABELS.get(text, text.title() if text else "Unknown")


def safe_filename(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", value.strip())
    cleaned = cleaned.strip("._-")
    return cleaned or "team"


def canonical_vod_stem(video_id: str) -> str:
    if video_id.startswith("v") and video_id[1:].isdigit():
        return video_id
    if video_id.isdigit():
        return f"v{video_id}"
    return safe_filename(video_id)


def vod_candidate_stems(video_id: str) -> list[str]:
    stems = [video_id]
    if video_id.startswith("v") and video_id[1:].isdigit():
        stems.append(video_id[1:])
    elif video_id.isdigit():
        stems.append(f"v{video_id}")
    return list(dict.fromkeys(stems))


def normalize_vod_id(video_id: str) -> str:
    text = str(video_id).strip()
    if text.lower().startswith("v") and text[1:].isdigit():
        return text[1:]
    return text


def parse_vod_shift_overrides(values: list[str]) -> dict[str, float]:
    overrides: dict[str, float] = {}
    for value in values:
        if "=" not in value:
            fail(f"--vod-shift must be in VOD_ID=SECONDS format, got {value!r}")
        vod_id, shift_text = value.split("=", 1)
        vod_id = normalize_vod_id(vod_id)
        if not vod_id:
            fail(f"--vod-shift has an empty VOD_ID in {value!r}")
        try:
            overrides[vod_id] = float(shift_text)
        except ValueError:
            fail(f"--vod-shift seconds must be numeric in {value!r}")
    return overrides


def shift_for_vod(video_id: str, default_shift: float, overrides: dict[str, float]) -> float:
    return overrides.get(normalize_vod_id(video_id), default_shift)


def vod_candidates(vod_dir: Path, video_id: str) -> list[Path]:
    candidates: list[Path] = []
    for stem in vod_candidate_stems(video_id):
        candidates.extend(
            path
            for path in vod_dir.glob(f"{stem}.*")
            if path.is_file() and not path.name.endswith(".part")
        )
    return sorted(set(candidates))


def ensure_vod(yt_dlp: str, yt_dlp_jobs: int | None, vod_dir: Path, video_id: str) -> Path:
    existing = vod_candidates(vod_dir, video_id)
    if existing:
        print(f"Reusing VOD {video_id}: {existing[0]}")
        return existing[0]

    url = f"https://www.twitch.tv/videos/{video_id}"
    print(f"Downloading VOD {video_id} from {url}")
    vod_dir.mkdir(parents=True, exist_ok=True)
    output_template = str(vod_dir / "%(id)s.%(ext)s")
    command = [yt_dlp, "--no-playlist", "--continue", "--output", output_template]
    if yt_dlp_jobs is not None:
        command.extend(["-N", str(yt_dlp_jobs)])
    command.append(url)
    subprocess.run(command, check=True)

    downloaded = vod_candidates(vod_dir, video_id)
    if not downloaded:
        fail(f"yt-dlp completed but no downloaded file was found for Twitch VOD {video_id} in {vod_dir}")
    return downloaded[0]


def intermediate_path_for(intermediate_dir: Path, video_id: str) -> Path:
    return intermediate_dir / f"{canonical_vod_stem(video_id)}_{INTERMEDIATE_TAG}.mkv"


def ensure_intermediate(ffmpeg: str, intermediate_dir: Path, video_id: str, vod_path: Path) -> Path:
    intermediate_dir.mkdir(parents=True, exist_ok=True)
    output_path = intermediate_path_for(intermediate_dir, video_id)
    if output_path.exists() and output_path.stat().st_size > 0:
        print(f"Reusing intermediate for VOD {video_id}: {output_path}")
        return output_path
    if output_path.exists():
        print(f"Discarding empty intermediate for VOD {video_id}: {output_path}")
        output_path.unlink()

    temp_output = output_path.with_name(f"{output_path.stem}.tmp{output_path.suffix}")
    temp_output.unlink(missing_ok=True)
    print(f"Building seek-friendly intermediate for VOD {video_id}: {output_path}")

    common_input_args = [
        ffmpeg,
        "-y",
        "-i",
        str(vod_path),
        "-map",
        "0:v:0",
        "-map",
        "0:a?",
        "-vf",
        (
            f"fps={OUTPUT_FRAME_RATE},"
            "scale=1920:1080:force_original_aspect_ratio=decrease,"
            "pad=1920:1080:(ow-iw)/2:(oh-ih)/2"
        ),
        "-r",
        str(OUTPUT_FRAME_RATE),
    ]
    common_output_args = [
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "pcm_s16le",
        "-ar",
        "48000",
        "-ac",
        "2",
        str(temp_output),
    ]
    attempts = [
        (
            "h264_nvenc short-GOP",
            [
                "-c:v",
                "h264_nvenc",
                "-preset",
                "p4",
                "-rc",
                "constqp",
                "-qp",
                "18",
                "-bf",
                "0",
                "-g",
                "2",
                "-forced-idr",
                "1",
            ],
        ),
        (
            "libx264 all-intra fallback",
            [
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "16",
                "-g",
                "1",
                "-bf",
                "0",
            ],
        ),
    ]

    failures: list[str] = []
    for label, video_args in attempts:
        temp_output.unlink(missing_ok=True)
        print(f"Building intermediate for VOD {video_id} with {label}")
        command = [*common_input_args, *video_args, *common_output_args]
        try:
            subprocess.run(command, check=True)
        except subprocess.CalledProcessError as exc:
            failures.append(f"{label} exited {exc.returncode}")
            print(f"Intermediate build attempt failed for VOD {video_id}: {failures[-1]}", file=sys.stderr)
            continue
        if temp_output.exists() and temp_output.stat().st_size > 0:
            temp_output.replace(output_path)
            return output_path
        failures.append(f"{label} produced no output")
        print(f"Intermediate build attempt failed for VOD {video_id}: {failures[-1]}", file=sys.stderr)

    temp_output.unlink(missing_ok=True)
    fail(f"ffmpeg failed while building intermediate for Twitch VOD {video_id}: {'; '.join(failures)}")


def textfile_filter_arg(path: Path) -> str:
    # FFmpeg filter values use ':' as a separator and '\'' quoting rules. Keep
    # temporary paths simple, but still escape the characters that can break the
    # textfile value.
    escaped = str(path).replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
    return f"textfile='{escaped}'"


def render_clip(
    ffmpeg: str,
    job: ClipJob,
    per_card_seconds: float,
) -> None:
    clip_start = max(0.0, job.offset_seconds + job.shift_seconds - (per_card_seconds / 2.0))
    label_y = "156" if job.count_label_path is not None else "48"
    drawtext_options = ":".join(
        [
            textfile_filter_arg(job.label_path),
            "expansion=none",
            "fontcolor=white",
            "fontsize=max(24\\,h/30)",
            "box=1",
            "boxcolor=black@0.70",
            "boxborderw=18",
            "x=(w-text_w)/2",
            f"y={label_y}",
        ]
    )
    drawtext_filters = [f"drawtext={drawtext_options}"]
    if job.count_label_path is not None:
        count_options = ":".join(
            [
                textfile_filter_arg(job.count_label_path),
                "expansion=none",
                "fontcolor=white",
                "fontsize=max(36\\,h/18)",
                "box=1",
                "boxcolor=black@0.70",
                "boxborderw=14",
                "x=w-text_w-48",
                "y=48",
            ]
        )
        drawtext_filters.append(f"drawtext={count_options}")

    video_filter = f"fps={OUTPUT_FRAME_RATE},{','.join(drawtext_filters)}"

    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-ss",
            f"{clip_start:.3f}",
            "-i",
            str(job.input_path),
            "-t",
            f"{per_card_seconds:.3f}",
            "-vf",
            video_filter,
            "-r",
            str(OUTPUT_FRAME_RATE),
            "-c:v",
            "h264_nvenc",
            "-preset",
            "p4",
            "-rc",
            "vbr",
            "-cq",
            "20",
            "-b:v",
            "0",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-ar",
            "48000",
            "-ac",
            "2",
            "-b:a",
            "160k",
            "-movflags",
            "+faststart",
            str(job.output_path),
        ],
        check=True,
    )


def concat_clips(ffmpeg: str, clips: list[Path], output_path: Path, list_path: Path) -> None:
    with list_path.open("w", encoding="utf-8") as handle:
        for clip in clips:
            clip_value = str(clip.resolve()).replace("'", "'\\''")
            handle.write(f"file '{clip_value}'\n")

    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(list_path),
            "-c",
            "copy",
            str(output_path),
        ],
        check=True,
    )


def require_row_value(row: dict[str, Any], key: str, card_id: int) -> Any:
    value = row.get(key)
    if value is None or value == "":
        fail(f"row for cardId {card_id} is missing required field {key!r}")
    return value


def main() -> None:
    args = parse_args()
    if args.per_card <= 0:
        fail("--per-card must be greater than 0")
    if args.jobs <= 0:
        fail("--jobs must be greater than 0")
    if args.yt_dlp_jobs is not None and args.yt_dlp_jobs <= 0:
        fail("--yt-dlp-jobs must be greater than 0")
    vod_shift_overrides = parse_vod_shift_overrides(args.vod_shift)

    yt_dlp = resolve_executable(args.yt_dlp, "yt-dlp")
    ffmpeg = resolve_executable(args.ffmpeg, "ffmpeg")
    card_names = load_card_names(args.cardinfo)
    duelist_names = load_duelist_names(args.duelistinfo)
    rows = load_rows(args.data)
    print(f"Loaded {len(rows)} acquisition rows from {args.data}")

    team_name, team_rows = select_team_rows(rows, args.team)
    order_description = "acquisition time" if args.timeline else "cardId"
    team_rows.sort(key=timeline_sort_key if args.timeline else card_sort_key)
    print(f"Selected team: {team_name}")
    print(f"Rendering {len(team_rows)} acquisition highlights ordered by {order_description}")
    if vod_shift_overrides:
        formatted_overrides = ", ".join(
            f"{vod_id}={shift:g}s" for vod_id, shift in sorted(vod_shift_overrides.items())
        )
        print(f"Using per-VOD shift overrides: {formatted_overrides}")

    args.cache_dir.mkdir(parents=True, exist_ok=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    vod_dir = args.cache_dir / "vods"
    intermediate_dir = args.cache_dir / "intermediate"
    clip_root = args.cache_dir / "clips"
    vod_dir.mkdir(parents=True, exist_ok=True)
    intermediate_dir.mkdir(parents=True, exist_ok=True)
    clip_root.mkdir(parents=True, exist_ok=True)

    output_path = args.output_dir / f"{safe_filename(team_name)}_drops_montage.mp4"

    with tempfile.TemporaryDirectory(prefix="team_montage_", dir=clip_root) as temp_dir_name:
        temp_dir = Path(temp_dir_name)
        jobs: list[ClipJob] = []
        vod_cache: dict[str, Path] = {}
        intermediate_cache: dict[str, Path] = {}

        for index, row in enumerate(team_rows, start=1):
            try:
                card_id = int(require_row_value(row, "cardId", 0))
            except (TypeError, ValueError):
                fail(f"row {index} has invalid cardId: {row.get('cardId')!r}")

            video_id = str(require_row_value(row, "twitchVideoId", card_id))
            try:
                offset_seconds = float(require_row_value(row, "offsetSeconds", card_id))
            except (TypeError, ValueError):
                fail(f"row for cardId {card_id} has invalid offsetSeconds: {row.get('offsetSeconds')!r}")

            if video_id not in vod_cache:
                vod_cache[video_id] = ensure_vod(yt_dlp, args.yt_dlp_jobs, vod_dir, video_id)
            vod_path = vod_cache[video_id]
            if video_id not in intermediate_cache:
                intermediate_cache[video_id] = ensure_intermediate(ffmpeg, intermediate_dir, video_id, vod_path)
            clip_input_path = intermediate_cache[video_id]

            card_name = card_names.get(card_id, f"Card {card_id}")
            player_name = str(row.get("playerName") or f"Player {row.get('playerId', 'unknown')}")
            try:
                opponent_id = int(row.get("opponentId", 0) or 0)
            except (TypeError, ValueError):
                opponent_id = 0
            opponent_name = duelist_names.get(opponent_id, f"Opponent {opponent_id}")
            label = f"{card_name}: {source_label(row.get('source'))} by {player_name} vs. {opponent_name}"
            shift_seconds = shift_for_vod(video_id, args.shift_times, vod_shift_overrides)

            label_path = temp_dir / f"label_{index:04d}.txt"
            label_path.write_text(label, encoding="utf-8")
            count_label_path = None
            if args.show_count:
                count_label_path = temp_dir / f"count_{index:04d}.txt"
                count_label_path.write_text(str(index), encoding="utf-8")
            clip_path = temp_dir / f"clip_{index:04d}_card_{card_id:03d}.mp4"
            jobs.append(
                ClipJob(
                    index=index,
                    total=len(team_rows),
                    card_id=card_id,
                    input_path=clip_input_path,
                    output_path=clip_path,
                    label_path=label_path,
                    count_label_path=count_label_path,
                    label=label,
                    offset_seconds=offset_seconds,
                    shift_seconds=shift_seconds,
                )
            )

        if not jobs:
            fail(f"no clips generated for team {team_name!r}")

        worker_count = min(args.jobs, len(jobs))
        print(
            f"Rendering clips at {OUTPUT_FRAME_RATE} fps with h264_nvenc "
            f"using {worker_count} ffmpeg worker(s)"
        )
        if worker_count == 1:
            for job in jobs:
                print(f"[{job.index}/{job.total}] Card {job.card_id}: {job.label}")
                render_clip(ffmpeg, job, args.per_card)
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count) as executor:
                futures = {
                    executor.submit(render_clip, ffmpeg, job, args.per_card): job
                    for job in jobs
                }
                for future in concurrent.futures.as_completed(futures):
                    job = futures[future]
                    try:
                        future.result()
                    except subprocess.CalledProcessError as exc:
                        fail(f"ffmpeg failed for cardId {job.card_id} with exit code {exc.returncode}")
                    print(f"[{job.index}/{job.total}] Rendered card {job.card_id}: {job.label}")

        clips = [job.output_path for job in jobs]
        concat_list_path = temp_dir / "clips.txt"
        concat_clips(ffmpeg, clips, output_path, concat_list_path)

    print(f"Created montage: {output_path}")


if __name__ == "__main__":
    main()
