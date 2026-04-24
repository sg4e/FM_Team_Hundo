package moe.maika.fmteamhundo.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;

public class Library {

    private final int teamId;
    private final Consumer<LibraryUpdate> onLibraryUpdate;
    private final Map<Integer, CardAcquisition> acquiredCards = new TreeMap<>();
    private final ConcurrentHashMap<Long, Long> starchips = new ConcurrentHashMap<>();
    private long totalStarchips = 0;

    Library(int teamId, Consumer<LibraryUpdate> onLibraryUpdate) {
        this.teamId = teamId;
        this.onLibraryUpdate = onLibraryUpdate;
    }

    boolean update(Collection<PlayerUpdate> teamUpdates) {
        if(teamUpdates.isEmpty()) {
            return false;
        }
        Set<PlayerUpdate> starchipUpdates = teamUpdates.stream().filter(c -> c.getSource() == MessageType.STARCHIPS).collect(Collectors.toSet());
        // for each player, get only the most recent starchip update
        Map<Long, PlayerUpdate> mostRecentStarchipUpdateByPlayer = starchipUpdates.stream().collect(Collectors.toMap(PlayerUpdate::getParticipantId, c -> c, (c1, c2) -> c1.getTime().isAfter(c2.getTime()) ? c1 : c2));
        Set<PlayerUpdate> cardUpdates = teamUpdates.stream().filter(c -> c.getSource() != MessageType.STARCHIPS).collect(Collectors.toSet());
        Instant latestTimestamp = teamUpdates.stream().map(c -> c.getTime()).max(Instant::compareTo).get();
        boolean changed = false;
        LibraryUpdate libraryUpdate = null;
        List<CardAcquisition> newAcquisitions = new ArrayList<>();
        synchronized(this) {
            for(PlayerUpdate card : cardUpdates) {
                // make sure library maps to first acquisition of the card
                if(!acquiredCards.containsKey(card.getValue()) || acquiredCards.get(card.getValue()).acquisitionTime().isAfter(card.getTime())) {
                    CardAcquisition acquisition = new CardAcquisition(card.getValue(), card.getTime(), card.getSource(), card.getParticipantId());
                    newAcquisitions.add(acquisition);
                    acquiredCards.put(card.getValue(), acquisition);
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
                libraryUpdate = new LibraryUpdate(this, teamId, latestTimestamp, totalStarchips, acquiredCards.size(), newAcquisitions);
            }
        }
        if(libraryUpdate != null) {
            onLibraryUpdate.accept(libraryUpdate);
        }
        return changed;
    }

    // not synchronized so PlayerView calls don't block
    public long getStarchips(long participantId) {
        return starchips.getOrDefault(participantId, 0L);
    }

    public synchronized long getTotalTeamStarchips() {
        return totalStarchips;
    }

    public synchronized Map<Integer, CardAcquisition> getAcquiredCards() {
        return Collections.unmodifiableMap(acquiredCards);
    }

    public synchronized int getUniqueCardCount() {
        return acquiredCards.size();
    }

    public synchronized List<Integer> getAcquiredCardIds() {
        return new ArrayList<>(acquiredCards.keySet());
    }
}
