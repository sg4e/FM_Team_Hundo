package moe.maika.fmteamhundo.livestats.api;

import java.time.Instant;
import java.util.List;

public record LibraryUpdate(
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
    Instant completionTime,
    int bewdCount
) {
    public LibraryUpdate {
        newAcquisitions = newAcquisitions == null ? List.of() : List.copyOf(newAcquisitions);
    }
}
