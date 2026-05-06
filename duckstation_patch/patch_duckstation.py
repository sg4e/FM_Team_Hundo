#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path


PREVIOUS_COMMIT = "9b0a4ec559ae83bfdb14685932b036ad7c1701be"
LATEST_COMMIT = "e663b72326bc6008db4797f0ea7ba9e6e25ede10"
SCRIPT_ROOT = Path(__file__).resolve().parent

CHECKSUMS = {
    "src/common/log_channels.h": "2e69905ecbafe829c91760897a7dd51973c321bc208e5245b797463bc09dccf4",
    "src/core/CMakeLists.txt": "51de75ae5170c8a43567630f3e00a5b46b92c537273900712d48b98417d5fe85",
    "src/core/core.vcxproj": "5f1aee6ab9d6d9def6c55cf0c065996a06ffb5f9e309980a5886f5060b2b2c8b",
    "src/core/core.vcxproj.filters": "4ccba84ce4ae2b22fab9f544474f1087a0ea9652664ba5dd252ba49ba24407c3",
    "src/core/system.cpp": "1bc06258c6f9c4e820ba1ee4a78099a70256a5a01db6900f883f0baffb514d86",
}

INSERTIONS = {
    "src/common/log_channels.h": [
        (24, '  X(FMTeamHundo)                                                                                                       \\'),
    ],
    "src/core/CMakeLists.txt": [
        (51, "  fm_team_hundo.cpp"),
        (51, "  fm_team_hundo.h"),
    ],
    "src/core/core.vcxproj": [
        (35, '    <ClCompile Include="fm_team_hundo.cpp" />'),
        (125, '    <ClInclude Include="fm_team_hundo.h" />'),
    ],
    "src/core/core.vcxproj.filters": [
        (48, '    <ClCompile Include="fm_team_hundo.cpp" />'),
        (129, '    <ClInclude Include="fm_team_hundo.h" />'),
    ],
    "src/core/system.cpp": [
        (17, '#include "fm_team_hundo.h"'),
        (2175, "  FMTeamHundo::Shutdown();"),
        (2215, "  FMTeamHundo::Reset();"),
        (2279, ""),
        (2279, "    FMTeamHundo::OnFrameDone();"),
        (2965, "  FMTeamHundo::Reset();"),
    ],
}

COPIED_FILES = [
    "src/core/fm_team_hundo.cpp",
    "src/core/fm_team_hundo.h",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def detect_newline(text: str) -> str:
    crlf = text.find("\r\n")
    if crlf != -1:
        return "\r\n"
    if "\n" in text:
        return "\n"
    return "\n"


def verify_checksum(root: Path, relative_path: str) -> None:
    expected = CHECKSUMS[relative_path]
    file_path = root / relative_path
    if not file_path.is_file():
        raise FileNotFoundError(f"Missing expected file: {file_path}")

    actual = sha256(file_path)
    if actual != expected:
        raise RuntimeError(
            f"Checksum mismatch for {relative_path}.\n"
            f"Expected: {expected}\n"
            f"Actual:   {actual}\n"
            f"This script expects a fresh checkout of commit {PREVIOUS_COMMIT}."
        )


def apply_insertions(root: Path, relative_path: str) -> None:
    file_path = root / relative_path
    text = file_path.read_text(encoding="utf-8")
    newline = detect_newline(text)
    lines = text.splitlines(keepends=True)

    offset = 0
    for line_number, inserted_text in INSERTIONS[relative_path]:
        index = line_number - 1 + offset
        if index < 0 or index > len(lines):
            raise RuntimeError(
                f"Cannot insert into {relative_path} at 1-based line {line_number}; "
                f"file currently has {len(lines)} lines."
            )
        lines.insert(index, inserted_text + newline)
        offset += 1

    file_path.write_text("".join(lines), encoding="utf-8", newline="")


def copy_added_file(root: Path, relative_path: str) -> None:
    source = SCRIPT_ROOT / relative_path
    destination = root / relative_path

    if not source.is_file():
        raise FileNotFoundError(f"Missing bundled file: {source}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Replicate git commit "
            f"{LATEST_COMMIT} onto a fresh checkout of {PREVIOUS_COMMIT}."
        )
    )
    parser.add_argument("codebase_root", type=Path, help="Path to the DuckStation checkout to patch.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.codebase_root.resolve()

    if not root.is_dir():
        raise NotADirectoryError(f"Not a directory: {root}")

    for relative_path in CHECKSUMS:
        verify_checksum(root, relative_path)

    for relative_path in INSERTIONS:
        apply_insertions(root, relative_path)

    for relative_path in COPIED_FILES:
        copy_added_file(root, relative_path)

    print(f"Applied {LATEST_COMMIT} on top of {PREVIOUS_COMMIT} in {root}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
