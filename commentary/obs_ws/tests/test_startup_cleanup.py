from __future__ import annotations

import pytest

from fm_hundo_obs.config import AppConfig
from fm_hundo_obs.main import Application
from fm_hundo_obs.obs import ObsError
from fm_hundo_obs.overlay import OverlayClientTimeout, OverlayServer


class FakeSession:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None


class FakeOverlayServer:
    def __init__(self) -> None:
        self.started = False
        self.stopped = False
        self.credits_connected_clients = 1
        self.overlay_waits = 0
        self.credits_waits = 0

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def wait_for_client(self, timeout_seconds: float) -> None:
        self.overlay_waits += 1

    async def wait_for_credits_client(self, timeout_seconds: float) -> None:
        self.credits_waits += 1

    async def send_credits(self, payload: dict) -> bool:
        return True


class FailingObs:
    connected = False

    def __init__(self) -> None:
        self.disconnected = False

    async def connect(self) -> None:
        raise ObsError("OBS WebSocket authentication failed")

    async def disconnect(self) -> None:
        self.disconnected = True


class SetupObs:
    connected = True

    def __init__(self) -> None:
        self.scenes: list[str] = []
        self.inputs: list[tuple[str, str, str, dict, bool]] = []
        self.scene_items: list[tuple[str, str, bool]] = []
        self.mutes: list[tuple[str, bool]] = []
        self.current_scene = "Main"
        self.current_program_scene_callbacks = []
        self.disconnected = False
        self.refreshed_browser_sources: list[str] = []

    async def connect(self) -> None:
        pass

    async def disconnect(self) -> None:
        self.disconnected = True

    async def ensure_scene(self, scene_name: str) -> None:
        self.scenes.append(scene_name)

    async def ensure_input(self, scene_name: str, input_name: str, input_kind: str, settings: dict, *, enabled: bool = True) -> int:
        self.inputs.append((scene_name, input_name, input_kind, settings, enabled))
        return 1

    async def ensure_scene_item(self, scene_name: str, source_name: str, *, enabled: bool = True) -> int:
        self.scene_items.append((scene_name, source_name, enabled))
        return 1

    async def set_scene_item_enabled(self, *_args, **_kwargs) -> None:
        pass

    async def set_scene_item_transform(self, *_args, **_kwargs) -> None:
        pass

    async def set_input_settings(self, *_args, **_kwargs) -> None:
        pass

    async def set_scene_item_index(self, *_args, **_kwargs) -> None:
        pass

    async def move_scene_item_to_top(self, *_args, **_kwargs) -> None:
        pass

    async def move_scene_item_to_bottom(self, *_args, **_kwargs) -> None:
        pass

    async def validate(self, *_, **__) -> None:
        pass

    async def get_current_program_scene(self) -> str:
        return self.current_scene

    async def set_current_program_scene(self, scene_name: str) -> None:
        self.current_scene = scene_name

    async def register_current_program_scene_callback(self, callback) -> None:
        self.current_program_scene_callbacks.append(callback)

    async def set_input_mute(self, input_name: str, muted: bool) -> None:
        self.mutes.append((input_name, muted))

    async def refresh_browser_source(self, input_name: str) -> None:
        self.refreshed_browser_sources.append(input_name)


class CapturingLayoutManager:
    def __init__(self) -> None:
        self.calls: list[tuple[str | None, bool]] = []

    async def reconcile_current_scene_audio(self, scene_name: str | None = None, *, force: bool = False) -> bool:
        self.calls.append((scene_name, force))
        return True


@pytest.mark.asyncio
async def test_preflight_failure_stops_overlay_and_disconnects_obs(monkeypatch):
    overlay = FakeOverlayServer()
    obs = FailingObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.overlay_server = overlay  # type: ignore[assignment]
    app.obs = obs  # type: ignore[assignment]

    class FakeMediaMtxClient:
        def __init__(self, *_):
            pass

        async def active_paths(self) -> set[str]:
            return set()

        def rtsp_url(self, path: str) -> str:
            return f"rtsp://127.0.0.1:8554/{path}"

    monkeypatch.setattr("fm_hundo_obs.main.ClientSession", lambda: FakeSession())
    monkeypatch.setattr("fm_hundo_obs.main.MediaMtxClient", FakeMediaMtxClient)

    with pytest.raises(ObsError):
        await app.run()

    assert overlay.started is True
    assert overlay.stopped is True
    assert obs.disconnected is True


@pytest.mark.asyncio
async def test_overlay_timeout_stops_overlay_and_disconnects_obs(monkeypatch):
    class TimeoutOverlay(FakeOverlayServer):
        async def wait_for_client(self, timeout_seconds: float) -> None:
            raise OverlayClientTimeout("overlay did not connect")

    overlay = TimeoutOverlay()
    obs = SetupObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.overlay_server = overlay  # type: ignore[assignment]
    app.obs = obs  # type: ignore[assignment]

    class FakeMediaMtxClient:
        def __init__(self, *_):
            pass

        async def active_paths(self) -> set[str]:
            return set()

        def rtsp_url(self, path: str) -> str:
            return f"rtsp://127.0.0.1:8554/{path}"

    monkeypatch.setattr("fm_hundo_obs.main.ClientSession", lambda: FakeSession())
    monkeypatch.setattr("fm_hundo_obs.main.MediaMtxClient", FakeMediaMtxClient)

    with pytest.raises(OverlayClientTimeout):
        await app.run()

    assert overlay.stopped is True
    assert obs.disconnected is True


@pytest.mark.asyncio
async def test_startup_refreshes_overlay_browser_source_after_first_connection_timeout():
    class TimeoutThenSuccessOverlay(FakeOverlayServer):
        async def wait_for_client(self, timeout_seconds: float) -> None:
            self.overlay_waits += 1
            if self.overlay_waits == 1:
                raise OverlayClientTimeout("overlay did not connect")

    overlay = TimeoutThenSuccessOverlay()
    obs = SetupObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.overlay_server = overlay  # type: ignore[assignment]
    app.obs = obs  # type: ignore[assignment]

    await app._wait_for_managed_browser_sources()

    assert overlay.overlay_waits == 2
    assert overlay.credits_waits == 1
    assert obs.refreshed_browser_sources == ["FM Hundo Overlay Browser"]


@pytest.mark.asyncio
async def test_startup_refreshes_credits_browser_source_after_first_connection_timeout():
    class TimeoutThenSuccessCredits(FakeOverlayServer):
        async def wait_for_credits_client(self, timeout_seconds: float) -> None:
            self.credits_waits += 1
            if self.credits_waits == 1:
                raise OverlayClientTimeout("credits did not connect")

    overlay = TimeoutThenSuccessCredits()
    obs = SetupObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.overlay_server = overlay  # type: ignore[assignment]
    app.obs = obs  # type: ignore[assignment]

    await app._wait_for_managed_browser_sources()

    assert overlay.overlay_waits == 1
    assert overlay.credits_waits == 2
    assert obs.refreshed_browser_sources == ["FM Hundo Credits Browser"]


@pytest.mark.asyncio
async def test_startup_browser_source_timeout_still_fails_after_refresh():
    class AlwaysTimeoutOverlay(FakeOverlayServer):
        async def wait_for_client(self, timeout_seconds: float) -> None:
            self.overlay_waits += 1
            raise OverlayClientTimeout("overlay did not connect")

    overlay = AlwaysTimeoutOverlay()
    obs = SetupObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.overlay_server = overlay  # type: ignore[assignment]
    app.obs = obs  # type: ignore[assignment]

    with pytest.raises(OverlayClientTimeout):
        await app._wait_for_managed_browser_sources()

    assert overlay.overlay_waits == 2
    assert overlay.credits_waits == 0
    assert obs.refreshed_browser_sources == ["FM Hundo Overlay Browser"]


@pytest.mark.asyncio
async def test_ensure_overlay_obs_setup_creates_browser_source():
    obs = SetupObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.obs = obs  # type: ignore[assignment]

    await app._ensure_overlay_obs_setup()

    assert "FM Hundo Overlay" in obs.scenes
    assert obs.inputs == [
        (
            "FM Hundo Overlay",
            "FM Hundo Overlay Browser",
            "browser_source",
            {
                "url": "http://127.0.0.1:8765/overlay",
                "width": 1920,
                "height": 1080,
                "reroute_audio": False,
                "shutdown": False,
            },
            True,
        )
    ]


@pytest.mark.asyncio
async def test_ensure_credits_obs_setup_creates_browser_source():
    obs = SetupObs()
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    app.obs = obs  # type: ignore[assignment]

    await app._ensure_credits_obs_setup()

    assert "FM Hundo - Credits" in obs.scenes
    assert obs.inputs == [
        (
            "FM Hundo - Credits",
            "FM Hundo Credits Browser",
            "browser_source",
            {
                "url": "http://127.0.0.1:8765/credits",
                "width": 1920,
                "height": 1080,
                "reroute_audio": False,
                "shutdown": False,
            },
            True,
        )
    ]


@pytest.mark.asyncio
async def test_overlay_wait_timeout_message_is_operator_friendly(tmp_path):
    server = OverlayServer(AppConfig().overlay, tmp_path)

    with pytest.raises(OverlayClientTimeout) as error:
        await server.wait_for_client(0.01)

    message = str(error.value)
    assert "http://127.0.0.1:8765/overlay" in message
    assert "Browser Source" in message


@pytest.mark.asyncio
async def test_credits_wait_timeout_message_is_operator_friendly(tmp_path):
    server = OverlayServer(AppConfig().overlay, tmp_path)

    with pytest.raises(OverlayClientTimeout) as error:
        await server.wait_for_credits_client(0.01)

    message = str(error.value)
    assert "http://127.0.0.1:8765/credits" in message
    assert "Credits Browser Source" in message


@pytest.mark.asyncio
async def test_scene_change_event_reconciles_layout_audio_focus():
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    layout = CapturingLayoutManager()
    app.layout_manager = layout  # type: ignore[assignment]

    await app._handle_obs_scene_changed("FM Hundo - Player - Runner Ten")

    assert layout.calls == [("FM Hundo - Player - Runner Ten", True)]


@pytest.mark.asyncio
async def test_managed_cycle_fallback_reconcile_uses_current_scene_lookup():
    app = Application(AppConfig(), config_path=None, simulate_mediamtx=True)  # type: ignore[arg-type]
    layout = CapturingLayoutManager()
    app.layout_manager = layout  # type: ignore[assignment]

    assert await app._reconcile_current_scene_audio() is True

    assert layout.calls == [(None, False)]
