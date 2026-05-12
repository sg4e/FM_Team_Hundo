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
from .logging_setup import setup_logging
from .managed_layout import ObsLayoutManager
from .mapping import NameResolver, load_duelist_names
from .mediamtx import MediaMtxClient, StreamRegistry
from .models import CardAcquisition
from .obs import DryRunObsController, SimpleObsController
from .overlay import OverlayEvents, OverlayServer
from .scheduler import AcquisitionScheduler

LOGGER = logging.getLogger(__name__)
PROJECT_DIR = Path(__file__).resolve().parents[2]


class Application:
    def __init__(self, config: AppConfig, config_path: Path) -> None:
        self.config = config
        self.config_path = config_path
        self.console = Console()
        real_obs = SimpleObsController(config.obs)
        self.obs = DryRunObsController(real_obs) if config.obs.dry_run else real_obs
        self.overlay_server = OverlayServer(config.overlay, PROJECT_DIR / "src" / "fm_hundo_obs" / "static")
        self.firehose: TeamFirehose | None = None
        self.scheduler: AcquisitionScheduler | None = None
        self.layout_manager: ObsLayoutManager | None = None
        self.streams: StreamRegistry | None = None
        self.firehose_connected = False
        self._stop = asyncio.Event()

    async def run(self) -> None:
        async with ClientSession() as session:
            api = HundoApiClient(self.config.api.base_url, session)
            players = await api.get_players()
            teams = await api.get_teams()
            duelists = load_duelist_names(PROJECT_DIR / "duelistinfo.json")
            names = NameResolver(players, duelists)
            mediamtx = MediaMtxClient(self.config.mediamtx, session)
            self.streams = StreamRegistry(players, mediamtx)
            await self.streams.refresh()

            await self.overlay_server.start()
            await self.obs.connect()
            await self._validate_obs()
            self.layout_manager = ObsLayoutManager(self.obs, self.config, players, teams, self.streams)
            await self.layout_manager.setup()
            await self.overlay_server.wait_for_client(self.config.overlay.connect_timeout_seconds)

            overlay = OverlayEvents(self.overlay_server)
            self.scheduler = AcquisitionScheduler(
                self.obs,
                overlay,
                names,
                self.layout_manager,
                self.config.features,
                self.config.timing,
            )
            self.firehose = TeamFirehose(
                api.team_firehose_url(),
                session,
                on_connection=self._set_firehose_connected,
            )

            tasks = [
                asyncio.create_task(self._consume_firehose(), name="firehose"),
                asyncio.create_task(self.layout_manager.run(), name="mediamtx-layout"),
                asyncio.create_task(self._managed_cycle_loop(), name="managed-cycles"),
                asyncio.create_task(self._console_loop(), name="console"),
            ]
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

    async def _managed_cycle_loop(self) -> None:
        assert self.layout_manager is not None
        assert self.scheduler is not None
        while True:
            if self.config.features.audio_rotation and not self.scheduler.acquisition_active():
                await self.layout_manager.tick_all_streamers_audio()
                await self.layout_manager.tick_team_showcases()
            await asyncio.sleep(1)

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

    async def _handle_reconcile(self) -> None:
        if not self.layout_manager:
            self.console.print("Layout manager is not started")
            return
        await self.layout_manager.reconcile()
        report = getattr(self.obs, "report", lambda: "")()
        self.console.print(report or "Reconciled managed OBS scenes")

    async def _handle_test(self, args: tuple[str, ...], force: bool) -> None:
        if self.scheduler is None or len(args) != 3:
            self.console.print("Expected: test <player_id> <drop|fusion|fuse|ritual> <opponent_id> [--force]")
            return
        try:
            acquisition = CardAcquisition.test_event(int(args[0]), args[1].lower(), int(args[2]))
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
        table.add_row("OBS", "connected" if self.obs.connected else "disconnected")
        table.add_row("Firehose", "connected" if self.firehose_connected else "disconnected")
        table.add_row("Overlay clients", str(self.overlay_server.connected_clients))
        if self.streams is not None:
            table.add_row("Active streams", str(len(self.streams.active_player_ids())))
        table.add_row("Active acquisition", "yes" if self.scheduler.acquisition_active() else "no")
        table.add_row("Paused", str(self.config.features.paused))
        table.add_row("Scene switching", str(self.config.features.scene_switching))
        table.add_row("Intro overlay", str(self.config.features.intro_overlay))
        table.add_row("Banner overlay", str(self.config.features.banner_overlay))
        table.add_row("Audio rotation", str(self.config.features.audio_rotation))
        table.add_row("OBS dry-run", str(self.config.obs.dry_run))
        self.console.print(table)

    def _set_firehose_connected(self, connected: bool) -> None:
        self.firehose_connected = connected


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=PROJECT_DIR / "config.yml")
    return parser.parse_args()


async def async_main() -> None:
    args = parse_args()
    setup_logging(PROJECT_DIR)
    config = load_config(args.config)
    app = Application(config, args.config)
    await app.run()


def main() -> None:
    asyncio.run(async_main())


if __name__ == "__main__":
    main()
