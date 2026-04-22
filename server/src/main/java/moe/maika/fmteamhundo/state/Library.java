package moe.maika.fmteamhundo.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;

public class Library {

    private final Map<Integer, CardAcquisition> acquiredCards = new TreeMap<>();
    private final Map<Long, Long> starchips = new HashMap<>();
    private long totalStarchips = 0;
    private long version = 0;
    private boolean isCardCacheValid = false;
    private Map<Integer, CardAcquisition> cachedCards;
    private List<CardAcquisition> cachedRecentAcquisitions;

    boolean update(Collection<PlayerUpdate> teamUpdates) {
        Set<PlayerUpdate> starchipUpdates = teamUpdates.stream().filter(c -> c.getSource() == MessageType.STARCHIPS).collect(Collectors.toSet());
        // for each player, get only the most recent starchip update
        Map<Long, PlayerUpdate> mostRecentStarchipUpdateByPlayer = starchipUpdates.stream().collect(Collectors.toMap(PlayerUpdate::getParticipantId, c -> c, (c1, c2) -> c1.getTime().isAfter(c2.getTime()) ? c1 : c2));
        Set<PlayerUpdate> cardUpdates = teamUpdates.stream().filter(c -> c.getSource() != MessageType.STARCHIPS).collect(Collectors.toSet());
        boolean changed = false;
        synchronized(this) {
            for(PlayerUpdate card : cardUpdates) {
                // make sure library maps to first acquisition of the card
                if(!acquiredCards.containsKey(card.getValue()) || acquiredCards.get(card.getValue()).acquisitionTime().isAfter(card.getTime())) {
                    isCardCacheValid = false;
                    acquiredCards.put(card.getValue(), new CardAcquisition(card.getValue(), card.getTime(), card.getSource(), card.getParticipantId()));
                    changed = true;
                }
            }
            for(PlayerUpdate starchip : mostRecentStarchipUpdateByPlayer.values()) {
                long newValue = starchip.getValue();
                Long previousValue = starchips.put(starchip.getParticipantId(), newValue);
                if(previousValue == null) {
                    totalStarchips += newValue;
                    changed = true;
                }
                else if(previousValue.longValue() != newValue) {
                    totalStarchips += newValue - previousValue.longValue();
                    changed = true;
                }
            }
            if(changed) {
                version++;
            }
        }
        return changed;
    }

    public synchronized long getStarchips(long participantId) {
        return starchips.getOrDefault(participantId, 0L);
    }

    public synchronized long getTotalTeamStarchips() {
        return totalStarchips;
    }

    public synchronized Map<Integer, CardAcquisition> getAcquiredCards() {
        if(!isCardCacheValid) {
            cachedCards = Collections.unmodifiableMap(new TreeMap<>(acquiredCards));
            cachedRecentAcquisitions = acquiredCards.values().stream()
                .sorted((left, right) -> right.acquisitionTime().compareTo(left.acquisitionTime()))
                .toList();
            isCardCacheValid = true;
        }
        return cachedCards;
    }

    public synchronized int getUniqueCardCount() {
        return acquiredCards.size();
    }

    public synchronized List<CardAcquisition> getRecentCardAcquisitions(int limit) {
        getAcquiredCards();
        return cachedRecentAcquisitions.stream().limit(limit).toList();
    }

    public synchronized long getVersion() {
        return version;
    }

    public synchronized List<Integer> getAcquiredCardIds() {
        return new ArrayList<>(acquiredCards.keySet());
    }
}
