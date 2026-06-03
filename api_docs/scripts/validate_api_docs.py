#!/usr/bin/env python3
"""Validate FM Team Hundo API documentation source files.

This script is intentionally kept outside GitHub Actions YAML so the same checks
can be run locally and in CI.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

API_DOCS_DIR = Path("api_docs")
OPENAPI_PATH = API_DOCS_DIR / "schemas" / "openapi.yaml"
EXPECTED_OPENAPI_VERSION = "3.2.0"
REQUIRED_HTTP_PATHS = ("/players", "/update")
REQUIRED_FIREHOSE_PATH = "/firehose/team"


def load_openapi_spec() -> dict[str, Any]:
    with OPENAPI_PATH.open(encoding="utf-8") as file:
        spec = yaml.safe_load(file)
    if not isinstance(spec, dict):
        raise AssertionError(f"{OPENAPI_PATH} must parse to a YAML mapping")
    return spec


def validate_openapi_version(spec: dict[str, Any]) -> None:
    actual_version = spec.get("openapi")
    if actual_version != EXPECTED_OPENAPI_VERSION:
        raise AssertionError(
            f"Expected {OPENAPI_PATH} openapi={EXPECTED_OPENAPI_VERSION!r}, got {actual_version!r}"
        )


def validate_required_paths(spec: dict[str, Any]) -> None:
    paths = spec.get("paths")
    if not isinstance(paths, dict):
        raise AssertionError(f"{OPENAPI_PATH} must contain a paths mapping")

    missing_paths = [path for path in REQUIRED_HTTP_PATHS if path not in paths]
    if missing_paths:
        raise AssertionError(f"{OPENAPI_PATH} is missing required paths: {', '.join(missing_paths)}")


def validate_firehose_extension(spec: dict[str, Any]) -> None:
    firehoses = spec.get("x-websocket-firehoses")
    if not isinstance(firehoses, list):
        raise AssertionError(f"{OPENAPI_PATH} must contain an x-websocket-firehoses list")

    has_required_firehose = any(
        isinstance(item, dict) and item.get("path") == REQUIRED_FIREHOSE_PATH
        for item in firehoses
    )
    if not has_required_firehose:
        raise AssertionError(
            f"{OPENAPI_PATH} is missing {REQUIRED_FIREHOSE_PATH} firehose metadata"
        )


def validate_utf8_docs() -> None:
    for path in API_DOCS_DIR.rglob("*"):
        if path.is_file():
            path.read_text(encoding="utf-8")


def main() -> None:
    spec = load_openapi_spec()
    validate_openapi_version(spec)
    validate_required_paths(spec)
    validate_firehose_extension(spec)
    validate_utf8_docs()
    print("API docs OpenAPI YAML and UTF-8 text checks passed")


if __name__ == "__main__":
    main()
