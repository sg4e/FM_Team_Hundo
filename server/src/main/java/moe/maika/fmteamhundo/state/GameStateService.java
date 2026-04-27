package moe.maika.fmteamhundo.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.util.RingBuffer;

@Slf4j
@Service
public class GameStateService {

    private static final int REPLAY_BATCH_SIZE = 1000;
    private static final int PLAYER_PAGE_UPDATE_LIMIT = 10;
    private static final int TEAM_PAGE_UPDATE_LIMIT = 10;

    private final ConcurrentHashMap<Integer, Library> teamLibraries;
    private final ConcurrentHashMap<Long, RingBuffer<PlayerUpdate>> latestPlayerUpdates;
    private final ConcurrentHashMap<Integer, TeamPageSnapshot> latestTeamSnapshots;
    private final ConcurrentHashMap<Integer, RingBuffer<CardAcquisition>> latestTeamAcquisitions;
    private final Set<TeamUpdateListener> teamUpdateListeners;
    private final ConcurrentHashMap<Long, Set<PlayerUpdateListener>> playerUpdateListenerMap;

    private final UserRepository userRepo;
    private final PlayerUpdateRepository playerUpdateRepository;
    private final UserMappings userMappings;
    private final HundoConstants hundoConstants;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public GameStateService(UserRepository userRepo, PlayerUpdateRepository playerUpdateRepository, UserMappings userMappings,
            HundoConstants hundoConstants) {
        teamLibraries = new ConcurrentHashMap<>();
        latestPlayerUpdates = new ConcurrentHashMap<>();
        latestTeamSnapshots = new ConcurrentHashMap<>();
        latestTeamAcquisitions = new ConcurrentHashMap<>();
        teamUpdateListeners = ConcurrentHashMap.newKeySet();
        playerUpdateListenerMap = new ConcurrentHashMap<>();
        this.userRepo = userRepo;
        this.playerUpdateRepository = playerUpdateRepository;
        this.hundoConstants = hundoConstants;
        this.userMappings = userMappings;
        reloadFromDatabase();
    }

    public void update(Collection<PlayerUpdate> updates) {
        Map<Integer, List<PlayerUpdate>> updatesByTeam = new HashMap<>();
        Map<Library, List<PlayerUpdate>> updatesByLibrary = new HashMap<>();
        // dispatch updates to listeners
        executorService.submit(() -> {
            Map<Long, List<PlayerUpdate>> mappedUpdates = updates.stream().collect(Collectors.groupingBy(PlayerUpdate::getParticipantId));
            notifyPlayerUpdateListeners(mappedUpdates);
        });
        for(PlayerUpdate update : updates) {
            User user = userMappings.getUserById(update.getParticipantId());
            updatesByTeam.computeIfAbsent(user.getTeamId(), _ -> new ArrayList<>()).add(update);
        }
        updatesByTeam.forEach((teamId, teamUpdates) -> {
            Library library = teamLibraries.computeIfAbsent(teamId, _ -> new Library(teamId, hundoConstants, this::consumeLibraryUpdate));
            updatesByLibrary.put(library, teamUpdates);
        });
        updatesByLibrary.forEach((library, libraryUpdates) -> {
            executorService.submit(() -> library.update(libraryUpdates));
        });
    }

    // this may run on another thread!
    private void consumeLibraryUpdate(LibraryUpdate libraryUpdate) {
        RingBuffer<CardAcquisition> teamAcquisitions = latestTeamAcquisitions.computeIfAbsent(libraryUpdate.teamId(), _ -> new RingBuffer<>(TEAM_PAGE_UPDATE_LIMIT));
        for(CardAcquisition card : libraryUpdate.newAcquisitions()) {
            teamAcquisitions.addOrReplace(card, oldCard -> {
                if(card.cardId() != oldCard.cardId())
                    return Boolean.FALSE;
                if(card.acquisitionTime().isBefore(oldCard.acquisitionTime()))
                    return Boolean.TRUE;
                return null;
            });
        }
        TeamPageSnapshot updatedSnapshot = new TeamPageSnapshot(libraryUpdate.teamId(), libraryUpdate.timestamp(), libraryUpdate.totalStarchips(), libraryUpdate.uniqueCardCount());
        latestTeamSnapshots.merge(libraryUpdate.teamId(), updatedSnapshot, (existing, newVal) -> 
                existing.timestamp().isBefore(newVal.timestamp()) ? newVal : existing);
        notifyTeamUpdateListeners(List.of(updatedSnapshot));
    }

    public Library getLibrary(int teamId) {
        return teamLibraries.getOrDefault(teamId, new Library(teamId, hundoConstants, this::consumeLibraryUpdate));
    }

    public List<CardAcquisition> getLatestCardAcquisitions(int teamId) {
        RingBuffer<CardAcquisition> cards = latestTeamAcquisitions.get(teamId);
        if(cards == null) {
            return List.of();
        }
        return cards.toList();
    }

    public TeamPageSnapshot getLatestTeamPageSnapshot(int teamId) {
        TeamPageSnapshot snap = latestTeamSnapshots.get(teamId);
        return snap != null ? snap : new TeamPageSnapshot(teamId, Instant.MIN, 0, 0);
    }

    public List<PlayerUpdate> getLatestPlayerUpdates(long playerId) {
        RingBuffer<PlayerUpdate> latest = latestPlayerUpdates.get(playerId);
        return latest != null ? latest.toList() : List.of();
    }

    void reloadFromDatabase() {
        Pageable pageable = PageRequest.of(0, REPLAY_BATCH_SIZE);
        Slice<PlayerUpdate> currentBatch;
        do {
            currentBatch = playerUpdateRepository.findAllByOrderByDatabaseIdAsc(pageable);
            if (!currentBatch.isEmpty()) {
                update(currentBatch.getContent());
            }
            pageable = currentBatch.nextPageable();
        } while (currentBatch.hasNext());
    }

    synchronized void reset() {
        teamLibraries.clear();
        latestPlayerUpdates.clear();
        latestTeamSnapshots.clear();
        latestTeamAcquisitions.clear();
        teamUpdateListeners.clear();
        playerUpdateListenerMap.clear();
    }

    public void addTeamUpdateListener(TeamUpdateListener listener) {
        teamUpdateListeners.add(listener);
    }

    public void removeTeamUpdateListener(TeamUpdateListener listener) {
        teamUpdateListeners.remove(listener);
    }

    public void addPlayerUpdateListener(long playerId, PlayerUpdateListener listener) {
        playerUpdateListenerMap.computeIfAbsent(playerId, _ -> ConcurrentHashMap.newKeySet()).add(listener);
    }

    public void removePlayerUpdateListener(long playerId, PlayerUpdateListener listener) {
        playerUpdateListenerMap.computeIfAbsent(playerId, _ -> ConcurrentHashMap.newKeySet()).remove(listener);
    }

    private void notifyTeamUpdateListeners(Collection<TeamPageSnapshot> snapshots) {
        for(TeamPageSnapshot snapshot : snapshots) {
            for(TeamUpdateListener listener : teamUpdateListeners) {
                executorService.submit(() -> {
                    try {
                        listener.onTeamUpdate(snapshot);
                    } catch(Exception e) {
                        log.error("Error notifying listener of team state change for team {}: {}", snapshot.teamId(), e.getMessage(), e);
                    }
                });
            }
        }
    }

    private void notifyPlayerUpdateListeners(Map<Long, List<PlayerUpdate>> updates) {
        for(Map.Entry<Long, List<PlayerUpdate>> entry : updates.entrySet()) {
            long playerId = entry.getKey();
            List<PlayerUpdate> updateList = entry.getValue();
            // add to our own memory, filtering out STARCHIPS updates
            List<PlayerUpdate> nonStarchipsUpdates = updateList.stream()
                .filter(u -> u.getSource() != MessageType.STARCHIPS)
                .toList();
            if(!nonStarchipsUpdates.isEmpty()) {
                latestPlayerUpdates.computeIfAbsent(playerId, _ -> new RingBuffer<>(PLAYER_PAGE_UPDATE_LIMIT)).addAll(nonStarchipsUpdates);
            }
            Set<PlayerUpdateListener> listeners = playerUpdateListenerMap.get(playerId);
            if(listeners != null) {
                List<PlayerUpdate> lockedList = Collections.unmodifiableList(updateList);
                for(PlayerUpdateListener listener : listeners) {
                    executorService.submit(() -> {
                        try {
                            listener.onPlayerUpdate(lockedList);
                        } catch(Exception e) {
                            log.error("Error notifying listener of player state change for player {}: {}", playerId, e.getMessage(), e);
                        }
                    });
                }
            }
        }
    }
}
