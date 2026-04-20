package moe.maika.fmteamhundo.state;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
    private boolean isCacheValid = false;
    private Map<Integer, CardAcquisition> cachedCards;

    void update(Collection<PlayerUpdate> teamUpdates) {
        Set<PlayerUpdate> starchipUpdates = teamUpdates.stream().filter(c -> c.getSource() == MessageType.STARCHIPS).collect(Collectors.toSet());
        // for each player, get only the most recent starchip update
        Map<Long, PlayerUpdate> mostRecentStarchipUpdateByPlayer = starchipUpdates.stream().collect(Collectors.toMap(PlayerUpdate::getParticipantId, c -> c, (c1, c2) -> c1.getTime().isAfter(c2.getTime()) ? c1 : c2));
        Set<PlayerUpdate> cardUpdates = teamUpdates.stream().filter(c -> c.getSource() != MessageType.STARCHIPS).collect(Collectors.toSet());
        synchronized(this) {
            for(PlayerUpdate card : cardUpdates) {
                // make sure library maps to first acquisition of the card
                if(!acquiredCards.containsKey(card.getValue()) || acquiredCards.get(card.getValue()).acquisitionTime().isAfter(card.getTime())) {
                    isCacheValid = false;
                    acquiredCards.put(card.getValue(), new CardAcquisition(card.getValue(), card.getTime(), card.getSource(), card.getParticipantId()));
                }
            }
            for(PlayerUpdate starchip : mostRecentStarchipUpdateByPlayer.values()) {
                starchips.put(starchip.getParticipantId(), (long) starchip.getValue());
            }
            totalStarchips = starchips.values().stream().mapToLong(Long::longValue).sum();
            // TODO propagate state to UI
        }
    }

    public synchronized long getStarchips(long participantId) {
        return starchips.getOrDefault(participantId, 0L);
    }

    public synchronized long getTotalTeamStarchips() {
        return totalStarchips;
    }

    public synchronized Map<Integer, CardAcquisition> getAcquiredCards() {
        if(!isCacheValid) {
            cachedCards = Collections.unmodifiableMap(new TreeMap<>(acquiredCards));
            isCacheValid = true;
        }
        return cachedCards;
    }
}
