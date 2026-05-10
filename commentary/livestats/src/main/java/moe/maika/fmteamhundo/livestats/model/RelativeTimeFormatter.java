package moe.maika.fmteamhundo.livestats.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class RelativeTimeFormatter {
    private RelativeTimeFormatter() {
    }

    public static String format(Instant instant, Clock clock) {
        if (instant == null) {
            return "";
        }

        Duration age = Duration.between(instant, Instant.now(clock));
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        long seconds = age.toSeconds();
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = age.toMinutes();
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = age.toHours();
        if (hours < 24) {
            return hours + "h ago";
        }
        return age.toDays() + "d ago";
    }
}
