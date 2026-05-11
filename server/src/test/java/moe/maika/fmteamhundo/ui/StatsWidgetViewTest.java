package moe.maika.fmteamhundo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.state.HundoConstants;

class StatsWidgetViewTest {

    @Test
    void parsesExcludedStatsFromFalseQueryParameters() {
        Map<String, List<String>> queryParameters = Map.of(
            "unbuyables", List.of("false"),
            "bewds", List.of("FALSE"),
            "cards", List.of("true"),
            "unknown", List.of("false")
        );

        assertThat(StatsWidgetView.excludedStats(queryParameters))
            .containsExactlyInAnyOrder("unbuyables", "bewds");
    }

    @Test
    void parsesDarkModeFromTrueQueryParameter() {
        assertThat(StatsWidgetView.isDarkMode(Map.of("dark_mode", List.of("true")))).isTrue();
        assertThat(StatsWidgetView.isDarkMode(Map.of("dark_mode", List.of("false")))).isFalse();
        assertThat(StatsWidgetView.isDarkMode(Map.of())).isFalse();
    }

    @Test
    void statKindsFormatValuesInWebsiteOrder() {
        HundoConstants hundoConstants = new HundoConstants("http://localhost:8080/api", 689, List.of(), false);
        LibraryUpdate snapshot = new LibraryUpdate(null, 1, Instant.parse("2026-05-10T12:00:00Z"),
                12345, 456, List.of(), 233, 12, 9876, true, false, null, 3);

        assertThat(StatKind.all())
            .extracting(stat -> stat.id() + "=" + stat.formatValue(snapshot, hundoConstants))
            .containsExactly(
                "cards=456/689",
                "starchips=12345",
                "cost_of_buyables=9876",
                "unbuyables=12",
                "bewds=3"
            );
    }
}
