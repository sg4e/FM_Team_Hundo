package moe.maika.fmteamhundo.state;

import java.time.Instant;
import java.util.List;

public record LibraryUpdate(
    Library library,
    int teamId,
    Instant timestamp,
    long totalStarchips,
    int uniqueCardCount,
    List<CardAcquisition> newAcquisitions,
    int totalUnobtained,
    int totalUnbuyables,
    int totalCostOfBuyables,
    boolean canAffordRemainingBuyables,
    boolean hasCompletedHundo,
    Instant completionTime
) { }
