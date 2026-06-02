package moe.maika.fmteamhundo.livestats.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.api.MessageType;
import moe.maika.fmteamhundo.livestats.api.PlayerJson;
import moe.maika.fmteamhundo.livestats.api.PlayerUpdate;
import moe.maika.fmteamhundo.livestats.api.TeamJson;
import moe.maika.ygofm.gamedata.FMDB;

class LiveStatsStateTest {
    private static final Instant NOW = Instant.parse("2026-05-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void assemblesRealTeamsInTeamIdOrderWithAlphabeticalRosterRows() {
        LiveStatsState state = state();

        assertEquals(2, state.teams().size());
        assertEquals(1, state.teams().get(0).team().id());
        assertEquals(2, state.teams().get(1).team().id());
        assertEquals("Amy", state.teams().get(0).players().get(0).playerNameProperty().get());
        assertEquals("Zed", state.teams().get(0).players().get(1).playerNameProperty().get());
    }

    @Test
    void routesPlayerUpdatesAndIgnoresUnknownPlayers() {
        LiveStatsState state = state();
        PlayerUpdate update = new PlayerUpdate(122, MessageType.DROP, 10, NOW.minusSeconds(5), 1, 2, 3);

        assertTrue(state.applyPlayerUpdate(update, CLOCK));
        assertEquals("drop", state.getPlayerRow(10).orElseThrow().sourceTextProperty().get());
        assertEquals("122", state.getPlayerRow(10).orElseThrow().valueTextProperty().get());
        assertEquals(String.valueOf(FMDB.getInstance().getDuelist(3).getName()), state.getPlayerRow(10).orElseThrow().opponentTextProperty().get());
        assertEquals("5s ago", state.getPlayerRow(10).orElseThrow().relativeTimeTextProperty().get());

        assertFalse(state.applyPlayerUpdate(new PlayerUpdate(1, MessageType.DROP, 999, NOW, 0, 0, 0), CLOCK));
    }

    @Test
    void starchipPlayerUpdatesOnlyChangeStarchipsAndTimeColumns() {
        LiveStatsState state = state();
        PlayerUpdate drop = new PlayerUpdate(122, MessageType.DROP, 10, NOW.minusSeconds(30), 1, 2, 3);
        PlayerUpdate starchips = new PlayerUpdate(987, MessageType.STARCHIPS, 10, NOW.minusSeconds(4), 4, 5, 6);

        assertTrue(state.applyPlayerUpdate(drop, CLOCK));
        assertTrue(state.applyPlayerUpdate(starchips, CLOCK));

        PlayerRowState row = state.getPlayerRow(10).orElseThrow();
        assertEquals("drop", row.sourceTextProperty().get());
        assertEquals("122", row.valueTextProperty().get());
        assertEquals(String.valueOf(FMDB.getInstance().getDuelist(3).getName()), row.opponentTextProperty().get());
        assertEquals("987", row.starchipsTextProperty().get());
        assertEquals("4s ago", row.relativeTimeTextProperty().get());
    }

    @Test
    void filtersTeamUpdatesByTeamId() {
        LiveStatsState state = state();
        LibraryUpdate update = library(2, 99);

        assertTrue(state.applyLibraryUpdate(update));
        assertEquals(99, state.teams().get(1).getLibraryUpdate().uniqueCardCount());
        assertFalse(state.applyLibraryUpdate(library(99, 1)));
    }

    private static LiveStatsState state() {
        return new LiveStatsState(
            List.of(new TeamJson("No Team", 0), new TeamJson("Blue", 2), new TeamJson("Red", 1)),
            List.of(
                new PlayerJson(11, "zed", "Zed", "", 1),
                new PlayerJson(10, "amy", "Amy", "", 1),
                new PlayerJson(20, "ben", "Ben", "", 2)),
            Map.of(1, library(1, 0), 2, library(2, 0)));
    }

    private static LibraryUpdate library(int teamId, int uniqueCardCount) {
        return new LibraryUpdate(teamId, NOW, 0, uniqueCardCount, List.of(), 0, 0, 0, false, false, null, 0);
    }
}
