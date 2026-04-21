package moe.maika.fmteamhundo.state;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Service
public class GameStateService {

    private static final int REPLAY_BATCH_SIZE = 1000;
    private static final int PLAYER_PAGE_UPDATE_LIMIT = 10;
    private static final int MAIN_PAGE_ACQUISITION_LIMIT = 5;

    private final Map<Integer, Library> teamLibraries;
    private final Map<Long, User> idToUser;
    private final Map<Integer, List<TeamMember>> teamMembers;
    private final Map<Long, Deque<PlayerUpdate>> recentUpdatesByPlayer;
    private final Map<Integer, TeamPageSnapshot> teamSnapshotCache;
    private final Map<Long, PlayerPageSnapshot> playerSnapshotCache;
    private final Map<Integer, Long> teamVersions;
    private final Map<Long, Long> playerVersions;
    private long overallTeamVersion;

    private final UserRepository userRepo;
    private final PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    public GameStateService(UserRepository userRepo, PlayerUpdateRepository playerUpdateRepository) {
        teamLibraries = new HashMap<>();
        idToUser = new HashMap<>();
        teamMembers = new HashMap<>();
        recentUpdatesByPlayer = new HashMap<>();
        teamSnapshotCache = new HashMap<>();
        playerSnapshotCache = new HashMap<>();
        teamVersions = new HashMap<>();
        playerVersions = new HashMap<>();
        this.userRepo = userRepo;
        this.playerUpdateRepository = playerUpdateRepository;
        loadKnownUsers();
        reloadFromDatabase();
    }

    public synchronized void update(Collection<PlayerUpdate> updates) {
        // group updates by team
        // the API service already verifies valid users
        Map<Integer, List<PlayerUpdate>> updatesByTeam = new HashMap<>();
        Set<Long> playersWithNewFeedEntries = new TreeSet<>();
        for(PlayerUpdate update : updates) {
            User user = requireUser(update.getParticipantId());
            updatesByTeam.computeIfAbsent(user.getTeamId(), ignored -> new ArrayList<>()).add(update);
            if(update.getSource() != MessageType.STARCHIPS) {
                Deque<PlayerUpdate> playerUpdates = recentUpdatesByPlayer.computeIfAbsent(update.getParticipantId(), ignored -> new ArrayDeque<>());
                playerUpdates.addFirst(update);
                while(playerUpdates.size() > PLAYER_PAGE_UPDATE_LIMIT) {
                    playerUpdates.removeLast();
                }
                playersWithNewFeedEntries.add(update.getParticipantId());
            }
        }
        // update each team's library
        updatesByTeam.forEach((teamId, teamUpdates) -> {
            Library library = teamLibraries.computeIfAbsent(teamId, ignored -> new Library());
            if(library.update(teamUpdates)) {
                bumpTeamVersion(teamId);
            }
        });
        playersWithNewFeedEntries.forEach(this::bumpPlayerVersion);
    }

    public synchronized Library getLibrary(int teamId) {
        return teamLibraries.getOrDefault(teamId, new Library());
    }

    public synchronized List<Integer> getKnownTeamIds() {
        TreeSet<Integer> teamIds = new TreeSet<>(teamMembers.keySet());
        teamIds.addAll(teamLibraries.keySet());
        teamIds.remove(0);  // Exclude team 0 (no team)
        return List.copyOf(teamIds);
    }

    public synchronized long getOverallTeamVersion() {
        return overallTeamVersion;
    }

    public synchronized TeamPageSnapshot getTeamPageSnapshot(int teamId) {
        TeamPageSnapshot cachedSnapshot = teamSnapshotCache.get(teamId);
        long currentVersion = teamVersions.getOrDefault(teamId, 0L);
        if(cachedSnapshot != null && cachedSnapshot.version() == currentVersion) {
            return cachedSnapshot;
        }

        Library library = teamLibraries.computeIfAbsent(teamId, ignored -> new Library());
        Map<Integer, CardAcquisitionView> acquiredCards = library.getAcquiredCards().values().stream()
            .map(this::toCardAcquisitionView)
            .collect(Collectors.toUnmodifiableMap(CardAcquisitionView::cardId, card -> card));

        TeamPageSnapshot snapshot = new TeamPageSnapshot(
            teamId,
            currentVersion,
            library.getTotalTeamStarchips(),
            library.getUniqueCardCount(),
            List.copyOf(teamMembers.getOrDefault(teamId, List.of())),
            library.getRecentCardAcquisitions(MAIN_PAGE_ACQUISITION_LIMIT).stream().map(this::toCardAcquisitionView).toList(),
            acquiredCards
        );
        teamSnapshotCache.put(teamId, snapshot);
        return snapshot;
    }

    public synchronized PlayerPageSnapshot getPlayerPageSnapshot(long playerId) {
        PlayerPageSnapshot cachedSnapshot = playerSnapshotCache.get(playerId);
        long currentVersion = playerVersions.getOrDefault(playerId, 0L);
        if(cachedSnapshot != null && cachedSnapshot.version() == currentVersion) {
            return cachedSnapshot;
        }

        User user = idToUser.get(playerId);
        String playerName = user != null ? user.getName() : "Unknown Player";
        int teamId = user != null ? user.getTeamId() : 0;
        List<PlayerUpdate> latestUpdates = List.copyOf(recentUpdatesByPlayer.getOrDefault(playerId, new ArrayDeque<>()));

        PlayerPageSnapshot snapshot = new PlayerPageSnapshot(playerId, currentVersion, playerName, teamId, latestUpdates);
        playerSnapshotCache.put(playerId, snapshot);
        return snapshot;
    }

    public synchronized void recordKnownUser(User user) {
        User previous = idToUser.put(user.getDatabaseId(), user);
        rebuildTeamMembers();
        if(previous == null || previous.getTeamId() != user.getTeamId() || !previous.getName().equals(user.getName())) {
            if(previous != null && previous.getTeamId() != user.getTeamId()) {
                bumpTeamVersion(previous.getTeamId());
            }
            bumpTeamVersion(user.getTeamId());
            bumpPlayerVersion(user.getDatabaseId());
        }
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

    /**
     * Resets the service state. Used for testing purposes.
     */
    synchronized void reset() {
        teamLibraries.clear();
        idToUser.clear();
        teamMembers.clear();
        recentUpdatesByPlayer.clear();
        teamSnapshotCache.clear();
        playerSnapshotCache.clear();
        teamVersions.clear();
        playerVersions.clear();
        overallTeamVersion = 0;
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

    private void bumpTeamVersion(int teamId) {
        teamVersions.merge(teamId, 1L, Long::sum);
        overallTeamVersion++;
        teamSnapshotCache.remove(teamId);
    }

    private void bumpPlayerVersion(long playerId) {
        playerVersions.merge(playerId, 1L, Long::sum);
        playerSnapshotCache.remove(playerId);
    }
}
