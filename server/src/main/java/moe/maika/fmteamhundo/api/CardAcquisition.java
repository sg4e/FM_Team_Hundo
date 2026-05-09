package moe.maika.fmteamhundo.api;

import java.time.Instant;

public record CardAcquisition(int cardId, Instant acquisitionTime, MessageType source, long playerId, int opponentId) implements Comparable<CardAcquisition> {
    @Override
    public int compareTo(CardAcquisition other) {
        return acquisitionTime().compareTo(other.acquisitionTime());
    }
}
