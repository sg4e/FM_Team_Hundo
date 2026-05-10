package moe.maika.fmteamhundo.livestats.api;

import java.time.Instant;

public record CardAcquisition(
    int cardId,
    Instant acquisitionTime,
    MessageType source,
    long playerId,
    int opponentId
) { }
