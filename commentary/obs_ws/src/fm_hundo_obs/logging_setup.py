from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path

from rich.logging import RichHandler


def setup_logging(project_dir: Path) -> None:
    logs_dir = project_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()

    console = RichHandler(show_path=False, rich_tracebacks=True)
    console.setLevel(logging.INFO)
    console.setFormatter(logging.Formatter("%(message)s"))

    file_handler = RotatingFileHandler(
        logs_dir / "fm-hundo-obs.log",
        maxBytes=1_000_000,
        backupCount=5,
        encoding="utf-8",
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s [%(name)s] %(message)s"))

    root.addHandler(console)
    root.addHandler(file_handler)

