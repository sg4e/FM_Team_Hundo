package moe.maika.fmteamhundo.livestats.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class HighlightTracker {
    private final Duration duration;
    private final Map<Long, Instant> expiresAtByPlayerId = new HashMap<>();

    public HighlightTracker(Duration duration) {
        this.duration = duration;
    }

    public Instant restart(long playerId, Instant now) {
        Instant expiresAt = now.plus(duration);
        expiresAtByPlayerId.put(playerId, expiresAt);
        return expiresAt;
    }

    public boolean isHighlighted(long playerId, Instant now) {
        Instant expiresAt = expiresAtByPlayerId.get(playerId);
        if (expiresAt == null) {
            return false;
        }
        if (!now.isBefore(expiresAt)) {
            expiresAtByPlayerId.remove(playerId);
            return false;
        }
        return true;
    }

    public Optional<Instant> expiresAt(long playerId) {
        return Optional.ofNullable(expiresAtByPlayerId.get(playerId));
    }
}
