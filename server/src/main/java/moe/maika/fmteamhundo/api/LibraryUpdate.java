package moe.maika.fmteamhundo.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import moe.maika.fmteamhundo.state.Library;

public record LibraryUpdate(
    @JsonIgnore Library library,
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
) { }
