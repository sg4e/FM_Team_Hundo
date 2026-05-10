package moe.maika.fmteamhundo.livestats.api;

import java.time.Instant;

public record PlayerUpdate(
    int value,
    MessageType source,
    long participantId,
    Instant time,
    int lastRng,
    int nowRng,
    int opponentId
) { }
