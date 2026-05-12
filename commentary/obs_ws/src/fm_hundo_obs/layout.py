from __future__ import annotations

from dataclasses import dataclass
from math import ceil, sqrt


@dataclass(frozen=True)
class Rect:
    x: float
    y: float
    width: float
    height: float


@dataclass(frozen=True)
class Fit:
    x: float
    y: float
    width: float
    height: float


def fit_inside(source_width: float, source_height: float, box: Rect) -> Fit:
    if source_width <= 0 or source_height <= 0:
        source_width = 16
        source_height = 9
    scale = min(box.width / source_width, box.height / source_height)
    width = source_width * scale
    height = source_height * scale
    return Fit(
        x=box.x + (box.width - width) / 2,
        y=box.y + (box.height - height) / 2,
        width=width,
        height=height,
    )


def grid_layout(count: int, canvas_width: int, canvas_height: int, gap: int = 16) -> list[Rect]:
    if count <= 0:
        return []
    columns = ceil(sqrt(count))
    rows = ceil(count / columns)
    cell_width = (canvas_width - gap * (columns + 1)) / columns
    cell_height = (canvas_height - gap * (rows + 1)) / rows
    rects: list[Rect] = []
    for index in range(count):
        row = index // columns
        column = index % columns
        rects.append(
            Rect(
                x=gap + column * (cell_width + gap),
                y=gap + row * (cell_height + gap),
                width=cell_width,
                height=cell_height,
            )
        )
    return rects


def team_showcase_layout(count: int, canvas_width: int, canvas_height: int, gap: int = 16) -> list[Rect]:
    if count <= 0:
        return []
    if count == 1:
        return [Rect(gap, gap, canvas_width - gap * 2, canvas_height - gap * 2)]

    showcase_width = canvas_width * 0.68
    side_width = canvas_width - showcase_width - gap * 3
    showcase = Rect(gap, gap, showcase_width, canvas_height - gap * 2)
    side_count = count - 1
    side_height = (canvas_height - gap * (side_count + 1)) / side_count
    side_rects = [
        Rect(
            x=showcase.x + showcase.width + gap,
            y=gap + index * (side_height + gap),
            width=side_width,
            height=side_height,
        )
        for index in range(side_count)
    ]
    return [showcase, *side_rects]

