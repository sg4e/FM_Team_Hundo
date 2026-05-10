package moe.maika.fmteamhundo.livestats.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RelativeTimeFormatterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void formatsRelativeAge() {
        assertEquals("", RelativeTimeFormatter.format(null, clock));
        assertEquals("0s ago", RelativeTimeFormatter.format(Instant.parse("2026-05-09T12:00:05Z"), clock));
        assertEquals("12s ago", RelativeTimeFormatter.format(Instant.parse("2026-05-09T11:59:48Z"), clock));
        assertEquals("2m ago", RelativeTimeFormatter.format(Instant.parse("2026-05-09T11:58:00Z"), clock));
        assertEquals("3h ago", RelativeTimeFormatter.format(Instant.parse("2026-05-09T09:00:00Z"), clock));
        assertEquals("2d ago", RelativeTimeFormatter.format(Instant.parse("2026-05-07T12:00:00Z"), clock));
    }
}
