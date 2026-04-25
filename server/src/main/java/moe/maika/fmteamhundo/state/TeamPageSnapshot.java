package moe.maika.fmteamhundo.state;

import java.time.Instant;

public record TeamPageSnapshot(
    int teamId,
    Instant timestamp,
    long totalStarchips,
    int uniqueCardCount
) { }
