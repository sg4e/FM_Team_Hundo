package moe.maika.fmteamhundo.state;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Service
public class GameStateService {

    private static final int REPLAY_BATCH_SIZE = 1000;

    private final Map<Integer, Library> teamLibraries;
    private final Map<Long, User> idToUser;

    private final UserRepository userRepo;
    private final PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    public GameStateService(UserRepository userRepo, PlayerUpdateRepository playerUpdateRepository) {
        teamLibraries = new HashMap<>();
        idToUser = new HashMap<>();
        this.userRepo = userRepo;
        this.playerUpdateRepository = playerUpdateRepository;
        reloadFromDatabase();
    }

    public void update(Collection<PlayerUpdate> updates) {
        // group updates by team
        // the API service already verifies valid users
        Map<Integer, List<PlayerUpdate>> updatesByTeam = updates.stream().collect(Collectors.groupingBy(
            update -> idToUser.computeIfAbsent(update.getParticipantId(), k -> userRepo.findById(k).get()).getTeamId())
        );
        // update each team's library
        updatesByTeam.forEach((teamId, teamUpdates) -> teamLibraries.computeIfAbsent(teamId, k -> new Library()).update(teamUpdates));
    }

    public Library getLibrary(int teamId) {
        return teamLibraries.getOrDefault(teamId, new Library());
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

    /**
     * Resets the service state. Used for testing purposes.
     */
    void reset() {
        teamLibraries.clear();
        idToUser.clear();
    }
}
