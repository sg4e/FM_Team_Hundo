package moe.maika.fmteamhundo.livestats.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TeamPanelViewTest {
    @Test
    void tableHeightMatchesActualPlayerRowsPlusHeader() {
        double rowHeight = TeamPanelView.tableHeightForPlayerCount(1) - TeamPanelView.tableHeightForPlayerCount(0);

        assertEquals(28.0, rowHeight, 0.0001);
        assertEquals(39.2, TeamPanelView.tableHeightForPlayerCount(0), 0.0001);
        assertEquals(95.2, TeamPanelView.tableHeightForPlayerCount(2), 0.0001);
        assertEquals(263.2, TeamPanelView.tableHeightForPlayerCount(8), 0.0001);
    }
}
