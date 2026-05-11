from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class CommandType(StrEnum):
    STATUS = "status"
    HELP = "help"
    QUIT = "quit"
    PAUSE = "pause"
    RESUME = "resume"
    SCENE = "scene"
    INTRO = "intro"
    BANNER = "banner"
    AUDIO = "audio"
    TEST = "test"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class Command:
    type: CommandType
    args: tuple[str, ...] = ()
    force: bool = False


HELP_TEXT = """Commands:
  status
  pause | resume
  scene on|off
  intro on|off
  banner on|off
  audio on|off|next
  test <player_id> <drop|fusion|fuse|ritual> <opponent_id> [--force]
  quit
"""


def parse_command(line: str) -> Command:
    parts = tuple(part for part in line.strip().split() if part)
    if not parts:
        return Command(CommandType.UNKNOWN, ())
    head = parts[0].lower()
    force = "--force" in parts
    args = tuple(part for part in parts[1:] if part != "--force")
    if head in {"q", "quit", "exit"}:
        return Command(CommandType.QUIT, args, force)
    if head in {"h", "help", "?"}:
        return Command(CommandType.HELP, args, force)
    try:
        return Command(CommandType(head), args, force)
    except ValueError:
        return Command(CommandType.UNKNOWN, parts, force)


def parse_on_off(args: tuple[str, ...]) -> bool | None:
    if len(args) != 1:
        return None
    value = args[0].lower()
    if value == "on":
        return True
    if value == "off":
        return False
    return None

