from __future__ import annotations

from dataclasses import dataclass, field
import os
from pathlib import Path
from typing import Any

import yaml


DEFAULT_API_BASE_URL = "https://hundo.maika.moe"


@dataclass(frozen=True)
class ApiConfig:
    base_url: str = DEFAULT_API_BASE_URL


@dataclass(frozen=True)
class ObsConfig:
    host: str = "127.0.0.1"
    port: int = 4455
    password: str | None = None
    overlay_scene: str = "FM Hundo Overlay"
    overlay_source: str = "FM Hundo Overlay Browser"
    managed_scene_prefix: str = "FM Hundo"
    media_source_kind: str = "ffmpeg_source"
    text_source_kind: str = "text_gdiplus_v3"
    browser_source_kind: str = "browser_source"
    stream_volume_mul: float = 0.0
    alert_audio_volume_mul: float = 1.0
    alert_audio_path: str | None = None
    alert_audio_source: str | None = None
    all_managed_master_scene: str | None = None
    stream_layout_master_scene: str | None = None
    credits_scene: str | None = None
    credits_source: str | None = None
    dry_run: bool = False

    @property
    def websocket_url(self) -> str:
        return f"ws://{self.host}:{self.port}"

    @property
    def credits_scene_name(self) -> str:
        return self.credits_scene or f"{self.managed_scene_prefix} - Credits"

    @property
    def credits_source_name(self) -> str:
        return self.credits_source or f"{self.managed_scene_prefix} Credits Browser"

    @property
    def alert_audio_source_name(self) -> str:
        return self.alert_audio_source or f"{self.managed_scene_prefix} Alert Audio"


@dataclass(frozen=True)
class OverlayConfig:
    host: str = "127.0.0.1"
    port: int = 8765
    connect_timeout_seconds: float = 10.0
    canvas_width: int = 1920
    canvas_height: int = 1080
    intro_bottom_px: int = 92

    @property
    def url(self) -> str:
        return f"http://{self.host}:{self.port}/overlay"

    @property
    def credits_url(self) -> str:
        return f"http://{self.host}:{self.port}/credits"


@dataclass(frozen=True)
class MediaMtxConfig:
    api_base_url: str = "http://127.0.0.1:9997"
    rtsp_base_url: str = "rtsp://127.0.0.1:8554"
    poll_seconds: float = 2.0


@dataclass(frozen=True)
class TimingConfig:
    acquisition_window_seconds: float = 30.0
    banner_enter_seconds: float = 0.3
    banner_exit_seconds: float = 0.3
    banner_end_buffer_seconds: float = 0.08
    banner_total_seconds: float | None = None
    intro_seconds: float = 3.0
    intro_delay_seconds: float = 0.0
    all_streamers_audio_seconds: float = 180.0
    team_showcase_seconds: float = 180.0
    alert_audio_duration_seconds: float = 3.0


@dataclass
class FeatureFlags:
    paused: bool = False
    scene_switching: bool = True
    intro_overlay: bool = True
    banner_overlay: bool = True
    audio_rotation: bool = True
    alert_audio: bool = True


@dataclass(frozen=True)
class GroupSceneConfig:
    scene: str
    audio_sources: tuple[str, ...]
    interval_seconds: float = 180.0


@dataclass(frozen=True)
class CreditsConfig:
    config_path: str | None = None


@dataclass(frozen=True)
class PortraitsConfig:
    directory: str = ""


@dataclass(frozen=True)
class TwitchConfig:
    client_id: str = ""
    client_secret: str = ""


@dataclass(frozen=True)
class AppConfig:
    api: ApiConfig = field(default_factory=ApiConfig)
    obs: ObsConfig = field(default_factory=ObsConfig)
    overlay: OverlayConfig = field(default_factory=OverlayConfig)
    mediamtx: MediaMtxConfig = field(default_factory=MediaMtxConfig)
    timing: TimingConfig = field(default_factory=TimingConfig)
    credits: CreditsConfig = field(default_factory=CreditsConfig)
    portraits: PortraitsConfig = field(default_factory=PortraitsConfig)
    twitch: TwitchConfig = field(default_factory=TwitchConfig)
    features: FeatureFlags = field(default_factory=FeatureFlags)
    player_scenes: dict[int, str] = field(default_factory=dict)
    group_scenes: tuple[GroupSceneConfig, ...] = ()


def load_config(path: Path | str) -> AppConfig:
    config_path = Path(path)
    data: dict[str, Any] = {}
    if config_path.exists():
        with config_path.open("r", encoding="utf-8") as handle:
            loaded = yaml.safe_load(handle) or {}
            if not isinstance(loaded, dict):
                raise ValueError(f"{config_path} must contain a YAML mapping")
            data = loaded

    obs_data = dict(data.get("obs") or {})
    if os.getenv("OBS_WS_PASSWORD"):
        obs_data["password"] = os.environ["OBS_WS_PASSWORD"]

    config = AppConfig(
        api=ApiConfig(**dict(data.get("api") or {})),
        obs=ObsConfig(**obs_data),
        overlay=OverlayConfig(**dict(data.get("overlay") or {})),
        mediamtx=MediaMtxConfig(**dict(data.get("mediamtx") or {})),
        timing=TimingConfig(**dict(data.get("timing") or {})),
        credits=CreditsConfig(**dict(data.get("credits") or {})),
        portraits=PortraitsConfig(**dict(data.get("portraits") or {})),
        twitch=TwitchConfig(**dict(data.get("twitch") or {})),
        features=FeatureFlags(**dict(data.get("features") or {})),
        player_scenes={int(key): str(value) for key, value in (data.get("player_scenes") or {}).items()},
        group_scenes=tuple(_group_scene(item) for item in data.get("group_scenes") or ()),
    )
    _validate_timing(config.timing)
    return config


def _group_scene(data: dict[str, Any]) -> GroupSceneConfig:
    return GroupSceneConfig(
        scene=str(data["scene"]),
        audio_sources=tuple(str(source) for source in data.get("audio_sources") or ()),
        interval_seconds=float(data.get("interval_seconds", 180.0)),
    )


def _validate_timing(timing: TimingConfig) -> None:
    non_negative = {
        "timing.acquisition_window_seconds": timing.acquisition_window_seconds,
        "timing.banner_enter_seconds": timing.banner_enter_seconds,
        "timing.banner_exit_seconds": timing.banner_exit_seconds,
        "timing.banner_end_buffer_seconds": timing.banner_end_buffer_seconds,
        "timing.intro_seconds": timing.intro_seconds,
        "timing.intro_delay_seconds": timing.intro_delay_seconds,
        "timing.all_streamers_audio_seconds": timing.all_streamers_audio_seconds,
        "timing.team_showcase_seconds": timing.team_showcase_seconds,
        "timing.alert_audio_duration_seconds": timing.alert_audio_duration_seconds,
    }
    for key, value in non_negative.items():
        if value < 0:
            raise ValueError(f"{key} must be >= 0")
    if timing.banner_total_seconds is not None and timing.banner_total_seconds < 0:
        raise ValueError("timing.banner_total_seconds must be >= 0 when provided")
    max_banner_total = timing.acquisition_window_seconds - timing.banner_end_buffer_seconds
    if max_banner_total < 0:
        raise ValueError("timing.banner_end_buffer_seconds cannot exceed timing.acquisition_window_seconds")
    if timing.banner_total_seconds is not None and timing.banner_total_seconds > max_banner_total:
        raise ValueError(
            "timing.banner_total_seconds exceeds timing.acquisition_window_seconds - timing.banner_end_buffer_seconds"
        )
    effective_total = timing.banner_total_seconds if timing.banner_total_seconds is not None else max_banner_total
    if effective_total < (timing.banner_enter_seconds + timing.banner_exit_seconds):
        raise ValueError("Banner timing invalid: total duration is shorter than enter+exit durations")
