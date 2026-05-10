package moe.maika.fmteamhundo.livestats.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class HighlightTrackerTest {
    @Test
    void restartExtendsHighlightWindowFromNewestAcquisition() {
        HighlightTracker tracker = new HighlightTracker(Duration.ofSeconds(10));
        Instant first = Instant.parse("2026-05-09T12:00:00Z");
        Instant second = first.plusSeconds(6);

        assertEquals(first.plusSeconds(10), tracker.restart(42, first));
        assertTrue(tracker.isHighlighted(42, first.plusSeconds(9)));

        assertEquals(second.plusSeconds(10), tracker.restart(42, second));
        assertTrue(tracker.isHighlighted(42, first.plusSeconds(15)));
        assertFalse(tracker.isHighlighted(42, second.plusSeconds(10)));
        assertTrue(tracker.expiresAt(42).isEmpty());
    }
}
