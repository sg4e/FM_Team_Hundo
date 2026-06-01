package moe.maika.fmteamhundo.livestats.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TeamPanelViewTest {
    @Test
    void tableHeightMatchesVisibleRowsPlusHeaderAndBorder() {
        double rowHeight = TeamPanelView.tableHeightForVisibleRows(1) - TeamPanelView.tableHeightForVisibleRows(0);

        assertEquals(28.0, rowHeight, 0.0001);
        assertEquals(26.0, TeamPanelView.tableHeightForVisibleRows(0), 0.0001);
        assertEquals(82.0, TeamPanelView.tableHeightForVisibleRows(2), 0.0001);
        assertEquals(250.0, TeamPanelView.tableHeightForVisibleRows(8), 0.0001);
    }
}
