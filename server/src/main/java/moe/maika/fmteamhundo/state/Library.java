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
import moe.maika.ygofm.gamedata.Card;
import moe.maika.ygofm.gamedata.FMDB;

public class Library {

    private final int teamId;
    private final Consumer<LibraryUpdate> onLibraryUpdate;
    private final Map<Integer, CardAcquisition> acquiredCards = new TreeMap<>();
    private final ConcurrentHashMap<Long, StarchipSnapshot> starchips = new ConcurrentHashMap<>();
    private volatile long totalStarchips = 0;
    private volatile int bewdCount = 0;
    private final List<Instant> bewdInstants = new ArrayList<>();
    private final HundoConstants hundoConstants;
    private final FMDB fmdb;

    private volatile boolean hasCompletedHundo = false;
    private volatile Instant completionTime = null;

    private static final List<Integer> GG_PIECES = List.of(
        371,  // Sanga
        372,  // Kazejin
        373,  // Suijin
        667   // Gate Guardian Ritual
    );

    private record StarchipSnapshot(long value, Instant time) { }

    Library(int teamId, HundoConstants hundoConstants, Consumer<LibraryUpdate> onLibraryUpdate) {
        this.teamId = teamId;
        this.hundoConstants = hundoConstants;
        this.onLibraryUpdate = onLibraryUpdate;
        this.fmdb = FMDB.getInstance();
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
                // handle BEUD and GG special cases
                // BEWD count
                if(card.getValue() == 1) {
                    // keeping track of the Instants helps protect against accidental re-adds, which might happen on resumes
                    // this may or may not be necessary in the end
                    // if FM Team Hundo every supports card-mod, this will have to be removed
                    if(!bewdInstants.contains(card.getTime())) {
                        bewdInstants.add(card.getTime());
                        bewdCount++;
                        changed = true; // changed every time because UI updates the BEWD count
                    }
                }
                // Regular card parsing
                // make sure library maps to first acquisition of the card
                if(!acquiredCards.containsKey(card.getValue()) || acquiredCards.get(card.getValue()).acquisitionTime().isAfter(card.getTime())) {
                    CardAcquisition acquisition = new CardAcquisition(card.getValue(), card.getTime(), card.getSource(), card.getParticipantId(), card.getOpponentId());
                    newAcquisitions.add(acquisition);
                    acquiredCards.put(card.getValue(), acquisition);
                    changed = true;
                }
                // BEUD
                if(!acquiredCards.containsKey(380) && bewdCount >= 3 && acquiredCards.containsKey(675)) {
                    CardAcquisition acquisition = new CardAcquisition(380, card.getTime(), MessageType.RITUAL, card.getParticipantId(), card.getOpponentId());
                    newAcquisitions.add(acquisition);
                    acquiredCards.put(380, acquisition);
                    changed = true;
                }
                // GG
                if(!acquiredCards.containsKey(374) && GG_PIECES.stream().allMatch(id -> acquiredCards.containsKey(id))) {
                    CardAcquisition acquisition = new CardAcquisition(374, card.getTime(), MessageType.RITUAL, card.getParticipantId(), card.getOpponentId());
                    newAcquisitions.add(acquisition);
                    acquiredCards.put(374, acquisition);
                    changed = true;
                }
            }
            for(PlayerUpdate starchip : mostRecentStarchipUpdateByPlayer.values()) {
                long newValue = starchip.getValue();
                StarchipSnapshot previous = starchips.get(starchip.getParticipantId());
                if(previous != null && !starchip.getTime().isAfter(previous.time())) {
                    continue;
                }
                starchips.put(starchip.getParticipantId(), new StarchipSnapshot(newValue, starchip.getTime()));
                if(previous == null) {
                    totalStarchips += newValue;
                    changed = true;
                }
                else if(previous.value() != newValue) {
                    totalStarchips += newValue - previous.value();
                    changed = true;
                }
            }
            if(changed) {
                libraryUpdate = updateHundoProgress(latestTimestamp, newAcquisitions);
            }
        }
        if(libraryUpdate != null) {
            onLibraryUpdate.accept(libraryUpdate);
        }
        return changed;
    }

    private LibraryUpdate updateHundoProgress(Instant latestTimestamp, List<CardAcquisition> newAcquisitions) {
        Set<Integer> obtained = acquiredCards.keySet();
        Set<Integer> unobtainable = hundoConstants.getUnobtainableCards();
        Set<Card> unobtainedCards = fmdb.getAllCards().stream()
                .filter(card -> !unobtainable.contains(card.getId()))
                .filter(card -> !obtained.contains(card.getId()))
                .collect(Collectors.toSet());
        int totalUnobtained = unobtainedCards.size();
        Set<Card> unbuyables = unobtainedCards.stream().filter(card -> card.getStarchips() == 999_999 || card.getStarchips() == 0).collect(Collectors.toSet());
        int totalUnbuyables = unbuyables.size();
        int totalCostOfBuyables = unobtainedCards.stream().filter(card -> !unbuyables.contains(card)).mapToInt(Card::getStarchips).sum();
        boolean canAffordRemainingBuyables = totalStarchips >= totalCostOfBuyables;
        boolean completed = canAffordRemainingBuyables && totalUnbuyables == 0;
        if(completed && !hasCompletedHundo) {
            hasCompletedHundo = true;
            // using latestTimestamp for this isn't perfectly accurate since it's not the timestamp of precisely the update that completely the hundo
            // but the difference should be negligible
            completionTime = latestTimestamp;
        }
        return new LibraryUpdate(this, teamId, latestTimestamp, totalStarchips, acquiredCards.size(), newAcquisitions, totalUnobtained, totalUnbuyables,
                totalCostOfBuyables, canAffordRemainingBuyables, completed, completionTime, bewdCount);
    }

    LibraryUpdate generateEmptyLibraryUpdate() {
        return updateHundoProgress(Instant.MIN, List.of());
    }

    // not synchronized so PlayerView calls don't block
    public long getStarchips(long participantId) {
        StarchipSnapshot snapshot = starchips.get(participantId);
        return snapshot != null ? snapshot.value() : 0L;
    }

    public long getTotalTeamStarchips() {
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
