package moe.maika.fmteamhundo.state;

import java.time.Instant;

import moe.maika.fmteamhundo.api.MessageType;

public record CardAcquisition(int cardId, Instant acquisitionTime, MessageType source, long playerId) implements Comparable<CardAcquisition> {
    @Override
    public int compareTo(CardAcquisition other) {
        return acquisitionTime().compareTo(other.acquisitionTime());
    }
}
