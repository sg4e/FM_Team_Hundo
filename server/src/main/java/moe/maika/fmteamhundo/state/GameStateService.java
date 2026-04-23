package moe.maika.fmteamhundo.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
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

@Slf4j
@Service
public class GameStateService {

    private static final int REPLAY_BATCH_SIZE = 1000;
    private static final int PLAYER_PAGE_UPDATE_LIMIT = 10;
    private static final int MAIN_PAGE_ACQUISITION_LIMIT = 5;

    private final Map<Integer, Library> teamLibraries;
    private final Map<Long, User> idToUser;
    private final Map<Integer, List<TeamMember>> teamMembers;
    private final Map<Long, Deque<PlayerUpdate>> recentUpdatesByPlayer;
    private final Map<Integer, Long> teamVersions;
    private final Map<Long, Long> playerVersions;
    private final Set<StateChangeListener> stateChangeListeners;

    private final UserRepository userRepo;
    private final PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    public GameStateService(UserRepository userRepo, PlayerUpdateRepository playerUpdateRepository) {
        teamLibraries = new HashMap<>();
        idToUser = new HashMap<>();
        teamMembers = new HashMap<>();
        recentUpdatesByPlayer = new HashMap<>();
        teamVersions = new HashMap<>();
        playerVersions = new HashMap<>();
        stateChangeListeners = new CopyOnWriteArraySet<>();
        this.userRepo = userRepo;
        this.playerUpdateRepository = playerUpdateRepository;
        loadKnownUsers();
        reloadFromDatabase();
    }

    public void update(Collection<PlayerUpdate> updates) {
        List<TeamPageSnapshot> changedTeamSnapshots;
        List<PlayerPageSnapshot> changedPlayerSnapshots;
        synchronized(this) {
            Map<Integer, List<PlayerUpdate>> updatesByTeam = new HashMap<>();
            Set<Long> changedPlayerIds = new TreeSet<>();
            for(PlayerUpdate update : updates) {
                User user = requireUser(update.getParticipantId());
                updatesByTeam.computeIfAbsent(user.getTeamId(), ignored -> new ArrayList<>()).add(update);
                changedPlayerIds.add(update.getParticipantId());
                if(update.getSource() != MessageType.STARCHIPS) {
                    Deque<PlayerUpdate> playerUpdates = recentUpdatesByPlayer.computeIfAbsent(update.getParticipantId(), ignored -> new ArrayDeque<>());
                    playerUpdates.addFirst(update);
                    while(playerUpdates.size() > PLAYER_PAGE_UPDATE_LIMIT) {
                        playerUpdates.removeLast();
                    }
                }
            }

            Set<Integer> changedTeamIds = new TreeSet<>();
            updatesByTeam.forEach((teamId, teamUpdates) -> {
                Library library = teamLibraries.computeIfAbsent(teamId, ignored -> new Library());
                if(library.update(teamUpdates)) {
                    incrementTeamVersion(teamId);
                    changedTeamIds.add(teamId);
                }
            });

            changedTeamSnapshots = changedTeamIds.stream().map(this::buildTeamPageSnapshot).toList();
            changedPlayerSnapshots = changedPlayerIds.stream().map(this::buildPlayerPageSnapshot).toList();
        }
        notifyListenersTeamChanged(changedTeamSnapshots);
        notifyListenersPlayerChanged(changedPlayerSnapshots);
    }

    public synchronized Library getLibrary(int teamId) {
        return teamLibraries.getOrDefault(teamId, new Library());
    }

    public synchronized List<Integer> getKnownTeamIds() {
        TreeSet<Integer> teamIds = new TreeSet<>(teamMembers.keySet());
        teamIds.addAll(teamLibraries.keySet());
        teamIds.remove(0);
        return List.copyOf(teamIds);
    }

    public synchronized TeamPageSnapshot getTeamPageSnapshot(int teamId) {
        return buildTeamPageSnapshot(teamId);
    }

    public synchronized PlayerPageSnapshot getPlayerPageSnapshot(long playerId) {
        return buildPlayerPageSnapshot(playerId);
    }

    public void recordKnownUser(User user) {
        List<TeamPageSnapshot> changedTeamSnapshots = List.of();
        List<PlayerPageSnapshot> changedPlayerSnapshots = List.of();
        synchronized(this) {
            User previous = idToUser.put(user.getDatabaseId(), user);
            rebuildTeamMembers();
            if(previous == null || previous.getTeamId() != user.getTeamId() || !previous.getName().equals(user.getName())) {
                Set<Integer> changedTeamIds = new LinkedHashSet<>();
                if(previous != null && previous.getTeamId() != user.getTeamId()) {
                    incrementTeamVersion(previous.getTeamId());
                    changedTeamIds.add(previous.getTeamId());
                }
                incrementTeamVersion(user.getTeamId());
                changedTeamIds.add(user.getTeamId());
                incrementPlayerVersion(user.getDatabaseId());

                changedTeamSnapshots = changedTeamIds.stream().map(this::buildTeamPageSnapshot).toList();
                changedPlayerSnapshots = List.of(buildPlayerPageSnapshot(user.getDatabaseId()));
            }
        }
        notifyListenersTeamChanged(changedTeamSnapshots);
        notifyListenersPlayerChanged(changedPlayerSnapshots);
    }

    void reloadFromDatabase() {
        loadKnownUsers();
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
        idToUser.clear();
        teamMembers.clear();
        recentUpdatesByPlayer.clear();
        teamVersions.clear();
        playerVersions.clear();
    }

    public void addStateChangeListener(StateChangeListener listener) {
        stateChangeListeners.add(listener);
    }

    public void removeStateChangeListener(StateChangeListener listener) {
        stateChangeListeners.remove(listener);
    }

    private void notifyListenersTeamChanged(Collection<TeamPageSnapshot> snapshots) {
        for(TeamPageSnapshot snapshot : snapshots) {
            for(StateChangeListener listener : stateChangeListeners) {
                try {
                    listener.onTeamStateChanged(snapshot);
                } catch(Exception e) {
                    log.error("Error notifying listener of team state change for team {}: {}", snapshot.teamId(), e.getMessage(), e);
                }
            }
        }
    }

    private void notifyListenersPlayerChanged(Collection<PlayerPageSnapshot> snapshots) {
        for(PlayerPageSnapshot snapshot : snapshots) {
            for(StateChangeListener listener : stateChangeListeners) {
                try {
                    listener.onPlayerStateChanged(snapshot);
                } catch(Exception e) {
                    log.error("Error notifying listener of player state change for player {}: {}", snapshot.playerId(), e.getMessage(), e);
                }
            }
        }
    }

    private void loadKnownUsers() {
        userRepo.findAll().forEach(this::recordKnownUser);
    }

    private User requireUser(long playerId) {
        User knownUser = idToUser.get(playerId);
        if(knownUser != null) {
            return knownUser;
        }
        User loadedUser = userRepo.findById(playerId).orElseThrow();
        recordKnownUser(loadedUser);
        return loadedUser;
    }

    private void rebuildTeamMembers() {
        Map<Integer, List<TeamMember>> rebuilt = idToUser.values().stream()
            .sorted(Comparator.comparingInt(User::getTeamId).thenComparing(User::getName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.groupingBy(
                User::getTeamId,
                Collectors.mapping(user -> new TeamMember(user.getDatabaseId(), user.getName()), Collectors.toList())
            ));
        teamMembers.clear();
        rebuilt.forEach((teamId, members) -> teamMembers.put(teamId, Collections.unmodifiableList(members)));
    }

    private CardAcquisitionView toCardAcquisitionView(CardAcquisition acquisition) {
        User user = idToUser.get(acquisition.playerId());
        String playerName = user != null ? user.getName() : "Unknown Player";
        return new CardAcquisitionView(acquisition.cardId(), acquisition.acquisitionTime(), acquisition.source(), acquisition.playerId(), playerName);
    }

    private TeamPageSnapshot buildTeamPageSnapshot(int teamId) {
        Library library = teamLibraries.computeIfAbsent(teamId, ignored -> new Library());
        Map<Integer, CardAcquisitionView> acquiredCards = library.getAcquiredCards().values().stream()
            .map(this::toCardAcquisitionView)
            .collect(Collectors.toUnmodifiableMap(CardAcquisitionView::cardId, card -> card));

        return new TeamPageSnapshot(
            teamId,
            teamVersions.getOrDefault(teamId, 0L),
            library.getTotalTeamStarchips(),
            library.getUniqueCardCount(),
            List.copyOf(teamMembers.getOrDefault(teamId, List.of())),
            library.getRecentCardAcquisitions(MAIN_PAGE_ACQUISITION_LIMIT).stream().map(this::toCardAcquisitionView).toList(),
            acquiredCards
        );
    }

    private PlayerPageSnapshot buildPlayerPageSnapshot(long playerId) {
        User user = idToUser.get(playerId);
        String playerName = user != null ? user.getName() : "Unknown Player";
        int teamId = user != null ? user.getTeamId() : 0;
        Library library = teamLibraries.getOrDefault(teamId, new Library());
        int starchips = (int) library.getStarchips(playerId);
        List<PlayerUpdate> latestUpdates = List.copyOf(recentUpdatesByPlayer.getOrDefault(playerId, new ArrayDeque<>()));

        return new PlayerPageSnapshot(
            playerId,
            playerVersions.getOrDefault(playerId, 0L),
            playerName,
            teamId,
            starchips,
            latestUpdates
        );
    }

    private void incrementTeamVersion(int teamId) {
        teamVersions.merge(teamId, 1L, Long::sum);
    }

    private void incrementPlayerVersion(long playerId) {
        playerVersions.merge(playerId, 1L, Long::sum);
    }
}
