from __future__ import annotations

import argparse
import asyncio
import logging
from pathlib import Path

from aiohttp import ClientSession
from rich.console import Console
from rich.table import Table

from .api import HundoApiClient, TeamFirehose
from .config import AppConfig, load_config
from .console import CommandType, HELP_TEXT, parse_command, parse_on_off
from .credits import CreditsConfigError, build_credits_payload, load_credits_scene_config, resolve_credits_config_path
from .logging_setup import setup_logging
from .managed_layout import ObsLayoutManager
from .mapping import NameResolver, load_card_names, load_duelist_names
from .mediamtx import MediaMtxClient, StreamRegistry
from .models import CardAcquisition
from .obs import DryRunObsController, ObsError, SimpleObsController
from .overlay import OverlayClientTimeout, OverlayEvents, OverlayServer
from .scheduler import AcquisitionScheduler
from .simulation import SimulationRoster, build_simulation_roster

LOGGER = logging.getLogger(__name__)
PROJECT_DIR = Path(__file__).resolve().parents[2]


class Application:
    def __init__(self, config: AppConfig, config_path: Path, *, simulate_mediamtx: bool = False) -> None:
        self.config = config
        self.config_path = config_path
        self.simulate_mediamtx = simulate_mediamtx
        self.console = Console()
        real_obs = SimpleObsController(config.obs)
        self.obs = DryRunObsController(real_obs) if config.obs.dry_run else real_obs
        self.overlay_server = OverlayServer(config.overlay, PROJECT_DIR / "src" / "fm_hundo_obs" / "static")
        self.api: HundoApiClient | None = None
        self.firehose: TeamFirehose | None = None
        self.scheduler: AcquisitionScheduler | None = None
        self.layout_manager: ObsLayoutManager | None = None
        self.streams: StreamRegistry | None = None
        self.simulation_roster: SimulationRoster | None = None
        self.names: NameResolver | None = None
        self.duelist_names: dict[int, str] = {}
        self.firehose_connected = False
        self._credits_started = False
        self._stop = asyncio.Event()

    async def run(self) -> None:
        async with ClientSession() as session:
            api = HundoApiClient(self.config.api.base_url, session)
            self.api = api
            duelists = load_duelist_names(PROJECT_DIR / "duelistinfo.json")
            self.duelist_names = duelists
            mediamtx = MediaMtxClient(self.config.mediamtx, session)
            if self.simulate_mediamtx:
                self.streams = StreamRegistry([], mediamtx)
                await self.streams.refresh()
                self.simulation_roster = build_simulation_roster(self.streams.active_paths_snapshot())
                players = self.simulation_roster.players
                teams = self.simulation_roster.teams
                self.streams.update_players(players)
            else:
                players = await api.get_players()
                teams = await api.get_teams()
                self.streams = StreamRegistry(players, mediamtx)
                await self.streams.refresh()
            names = NameResolver(players, duelists, teams)
            self.names = names

            startup_complete = False
            try:
                await self.overlay_server.start()
                await self.obs.connect()
                await self._ensure_overlay_obs_setup()
                await self._ensure_credits_obs_setup()
                await self._validate_obs()
                self.layout_manager = ObsLayoutManager(self.obs, self.config, players, teams, self.streams)
                await self.layout_manager.setup()
                await self.overlay_server.wait_for_client(self.config.overlay.connect_timeout_seconds)
                await self.overlay_server.wait_for_credits_client(self.config.overlay.connect_timeout_seconds)
                startup_complete = True
            finally:
                if not startup_complete:
                    await self.overlay_server.stop()
                    await self.obs.disconnect()

            overlay = OverlayEvents(self.overlay_server)
            self.scheduler = AcquisitionScheduler(
                self.obs,
                overlay,
                names,
                self.layout_manager,
                self.config.features,
                self.config.timing,
                scene_lock=self._credits_scene_active,
            )
            if not self.simulate_mediamtx:
                self.firehose = TeamFirehose(
                    api.team_firehose_url(),
                    session,
                    on_connection=self._set_firehose_connected,
                )

            tasks = [
                asyncio.create_task(self._simulation_layout_loop() if self.simulate_mediamtx else self.layout_manager.run(), name="mediamtx-layout"),
                asyncio.create_task(self._managed_cycle_loop(), name="managed-cycles"),
                asyncio.create_task(self._console_loop(), name="console"),
            ]
            if self.firehose is not None:
                tasks.append(asyncio.create_task(self._consume_firehose(), name="firehose"))
            try:
                await self._stop.wait()
            finally:
                if self.firehose:
                    self.firehose.close()
                if self.layout_manager:
                    self.layout_manager.close()
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                await self.overlay_server.stop()
                await self.obs.disconnect()

    async def _validate_obs(self) -> None:
        audio_sources = [
            source
            for group in self.config.group_scenes
            for source in group.audio_sources
        ]
        await self.obs.validate(
            player_scenes=(),
            group_scenes=(),
            overlay_scene=self.config.obs.overlay_scene,
            overlay_source=self.config.obs.overlay_source,
            audio_sources=audio_sources,
        )

    async def _ensure_overlay_obs_setup(self) -> None:
        await self.obs.ensure_scene(self.config.obs.overlay_scene)
        await self.obs.ensure_input(
            self.config.obs.overlay_scene,
            self.config.obs.overlay_source,
            self.config.obs.browser_source_kind,
            {
                "url": self.config.overlay.url,
                "width": self.config.overlay.canvas_width,
                "height": self.config.overlay.canvas_height,
                "reroute_audio": False,
                "shutdown": False,
            },
            enabled=True,
        )

    async def _ensure_credits_obs_setup(self) -> None:
        await self.obs.ensure_scene(self.config.obs.credits_scene_name)
        await self.obs.ensure_input(
            self.config.obs.credits_scene_name,
            self.config.obs.credits_source_name,
            self.config.obs.browser_source_kind,
            {
                "url": self.config.overlay.credits_url,
                "width": self.config.overlay.canvas_width,
                "height": self.config.overlay.canvas_height,
                "reroute_audio": False,
                "shutdown": False,
            },
            enabled=True,
        )

    async def _managed_cycle_loop(self) -> None:
        assert self.layout_manager is not None
        assert self.scheduler is not None
        while True:
            if self.config.features.audio_rotation and not self.scheduler.acquisition_active():
                await self.layout_manager.tick_all_streamers_audio()
                await self.layout_manager.tick_team_showcases()
            await asyncio.sleep(1)

    async def _simulation_layout_loop(self) -> None:
        assert self.streams is not None
        assert self.layout_manager is not None
        while True:
            changed = await self.streams.refresh()
            if changed:
                self.simulation_roster = build_simulation_roster(self.streams.active_paths_snapshot())
                self.streams.update_players(self.simulation_roster.players)
                if self.names is not None:
                    self.names.update_players(self.simulation_roster.players)
                    self.names.update_teams(self.simulation_roster.teams)
                await self.layout_manager.update_roster(self.simulation_roster.players, self.simulation_roster.teams)
            await asyncio.sleep(self.config.mediamtx.poll_seconds)

    async def _consume_firehose(self) -> None:
        assert self.firehose is not None
        assert self.scheduler is not None
        async for update in self.firehose.updates():
            result = await self.scheduler.handle_update(update)
            if result.accepted:
                LOGGER.info("Accepted acquisition: %s", result.context)
            else:
                LOGGER.debug("Ignored team update: %s", result.reason)

    async def _console_loop(self) -> None:
        self.console.print("FM Hundo OBS controller started. Type 'help' for commands.")
        while not self._stop.is_set():
            try:
                line = await asyncio.to_thread(input, "> ")
            except EOFError:
                self._stop.set()
                return
            await self._handle_command(line)

    async def _handle_command(self, line: str) -> None:
        command = parse_command(line)
        features = self.config.features
        if command.type == CommandType.QUIT:
            self._stop.set()
        elif command.type == CommandType.HELP:
            self.console.print(HELP_TEXT)
        elif command.type == CommandType.STATUS:
            await self._print_status()
        elif command.type == CommandType.PAUSE:
            features.paused = True
            self.console.print("Paused")
        elif command.type == CommandType.RESUME:
            features.paused = False
            self.console.print("Resumed")
        elif command.type in {CommandType.SCENE, CommandType.INTRO, CommandType.BANNER}:
            value = parse_on_off(command.args)
            if value is None:
                self.console.print("Expected: on|off")
                return
            if command.type == CommandType.SCENE:
                features.scene_switching = value
            elif command.type == CommandType.INTRO:
                features.intro_overlay = value
            else:
                features.banner_overlay = value
            self.console.print(f"{command.type.value}: {'on' if value else 'off'}")
        elif command.type == CommandType.AUDIO:
            await self._handle_audio(command.args)
        elif command.type == CommandType.CREDITS:
            await self._handle_credits()
        elif command.type == CommandType.RECONCILE:
            await self._handle_reconcile()
        elif command.type == CommandType.TEST:
            await self._handle_test(command.args, command.force)
        elif command.type == CommandType.UNKNOWN:
            self.console.print("Unknown command. Type 'help'.")

    async def _handle_audio(self, args: tuple[str, ...]) -> None:
        if args == ("next",):
            advanced = False
            if self.layout_manager:
                advanced = await self.layout_manager.tick_all_streamers_audio(force=True)
                advanced = await self.layout_manager.tick_team_showcases(force=True) or advanced
            if advanced:
                self.console.print("Advanced audio rotation")
            else:
                self.console.print("No active managed scene to advance")
            return
        value = parse_on_off(args)
        if value is None:
            self.console.print("Expected: audio on|off|next")
            return
        self.config.features.audio_rotation = value
        self.console.print(f"audio: {'on' if value else 'off'}")

    async def _handle_credits(self) -> None:
        if self.api is None:
            self.console.print("Credits unavailable: API client is not ready")
            return
        credits_config_path = resolve_credits_config_path(self.config, self.config_path)
        try:
            scene_config = load_credits_scene_config(credits_config_path)
            credits_data = await self.api.get_credits()
            card_names = load_card_names(PROJECT_DIR / "cardinfo.json")
            payload = build_credits_payload(credits_data, scene_config, card_names, self.duelist_names)
        except CreditsConfigError as ex:
            self.console.print(f"Credits config error: {ex}")
            return
        except Exception as ex:
            LOGGER.exception("Unable to prepare credits")
            self.console.print(f"Credits failed: {ex}")
            return

        if self.overlay_server.credits_connected_clients <= 0:
            self.console.print("Credits browser is not connected; leaving current scene unchanged")
            return
        if self.scheduler is not None:
            self.scheduler.cancel_active_window()
        await self.obs.set_current_program_scene(self.config.obs.credits_scene_name)
        self._credits_started = True
        sent = await self.overlay_server.send_credits(payload)
        await self.obs.refresh_browser_source(self.config.obs.credits_source_name)
        if sent:
            self.console.print("Credits started")
        else:
            self.console.print("Credits scene is active, but the credits browser did not receive the payload")

    async def _handle_reconcile(self) -> None:
        if not self.layout_manager:
            self.console.print("Layout manager is not started")
            return
        await self.layout_manager.reconcile()
        report = getattr(self.obs, "report", lambda: "")()
        self.console.print(report or "Reconciled managed OBS scenes")

    async def _handle_test(self, args: tuple[str, ...], force: bool) -> None:
        if self.scheduler is None or len(args) != 3:
            expected = "test <mediamtx_path> <drop|fusion|fuse|ritual> <opponent_id> [--force]" if self.simulate_mediamtx else "test <player_id> <drop|fusion|fuse|ritual> <opponent_id> [--force]"
            self.console.print(f"Expected: {expected}")
            return
        try:
            player_id = self._test_player_id(args[0])
            acquisition = CardAcquisition.test_event(player_id, args[1].lower(), int(args[2]))
        except Exception as ex:
            self.console.print(f"Invalid test acquisition: {ex}")
            return
        result = await self.scheduler.handle_acquisition(acquisition, force=force)
        self.console.print(f"test: {result.reason}")

    async def _print_status(self) -> None:
        assert self.scheduler is not None
        table = Table(title="FM Hundo OBS")
        table.add_column("Item")
        table.add_column("State")
        table.add_row("Config", str(self.config_path))
        table.add_row("Simulation", str(self.simulate_mediamtx))
        table.add_row("OBS", "connected" if self.obs.connected else "disconnected")
        table.add_row("Firehose", "connected" if self.firehose_connected else "disconnected")
        table.add_row("Overlay clients", str(self.overlay_server.connected_clients))
        if self.streams is not None:
            table.add_row("Active streams", str(len(self.streams.active_player_ids())))
            if self.simulate_mediamtx:
                table.add_row("MediaMTX paths", ", ".join(sorted(self.streams.active_paths_snapshot())) or "(none)")
        if self.simulation_roster is not None:
            table.add_row("Simulated players", str(len(self.simulation_roster.players)))
        table.add_row("Active acquisition", "yes" if self.scheduler.acquisition_active() else "no")
        table.add_row("Credits scene", self.config.obs.credits_scene_name)
        table.add_row("Credits browser clients", str(self.overlay_server.credits_connected_clients))
        table.add_row("Credits active", "yes" if await self._credits_scene_active() else "no")
        table.add_row("Paused", str(self.config.features.paused))
        table.add_row("Scene switching", str(self.config.features.scene_switching))
        table.add_row("Intro overlay", str(self.config.features.intro_overlay))
        table.add_row("Banner overlay", str(self.config.features.banner_overlay))
        table.add_row("Audio rotation", str(self.config.features.audio_rotation))
        table.add_row("OBS dry-run", str(self.config.obs.dry_run))
        self.console.print(table)

    def _set_firehose_connected(self, connected: bool) -> None:
        self.firehose_connected = connected

    async def _credits_scene_active(self) -> bool:
        if not self._credits_started or not self.obs.connected:
            return False
        try:
            return await self.obs.get_current_program_scene() == self.config.obs.credits_scene_name
        except Exception:
            LOGGER.exception("Unable to check credits scene state")
            return False

    def _test_player_id(self, raw: str) -> int:
        if not self.simulate_mediamtx:
            return int(raw)
        if self.simulation_roster is None:
            raise ValueError("simulation roster is not loaded")
        player_id = self.simulation_roster.player_id_for_path(raw)
        if player_id is None:
            raise ValueError(f"unknown MediaMTX path {raw!r}; use status to see active paths")
        return player_id


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=PROJECT_DIR / "config.yml")
    parser.add_argument("--simulate-mediamtx", action="store_true", help="Bypass website/firehose and use active MediaMTX paths as simulated players")
    return parser.parse_args()


async def async_main() -> int:
    args = parse_args()
    setup_logging(PROJECT_DIR)
    config = load_config(args.config)
    app = Application(config, args.config, simulate_mediamtx=args.simulate_mediamtx)
    try:
        await app.run()
    except ObsError as ex:
        Console(stderr=True).print(f"[bold red]OBS startup error:[/bold red] {ex}")
        return 1
    except OverlayClientTimeout as ex:
        Console(stderr=True).print(f"[bold red]Overlay startup error:[/bold red] {ex}")
        return 1
    return 0


def main() -> None:
    raise SystemExit(asyncio.run(async_main()))


if __name__ == "__main__":
    main()
