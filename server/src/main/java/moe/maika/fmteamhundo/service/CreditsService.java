package moe.maika.fmteamhundo.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import moe.maika.fmteamhundo.api.CreditsResponse;
import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.state.GameStateService;

@Service
public class CreditsService {

    private static final int TWIN_HEADED_THUNDER_DRAGON_CARD_ID = 613;

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final PlayerUpdateRepository playerUpdateRepository;
    private final GameStateService gameStateService;

    public CreditsService(TeamRepository teamRepository, UserRepository userRepository,
            PlayerUpdateRepository playerUpdateRepository, GameStateService gameStateService) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.playerUpdateRepository = playerUpdateRepository;
        this.gameStateService = gameStateService;
    }

    public CreditsResponse getCredits() {
        Map<Integer, Team> teamsById = teamRepository.findAll().stream()
            .filter(team -> team.getTeamId() != null && team.getTeamId() != 0)
            .collect(Collectors.toMap(Team::getTeamId, team -> team));

        Map<Long, User> usersById = userRepository.findAll().stream()
            .filter(user -> teamsById.containsKey(user.getTeamId()))
            .collect(Collectors.toMap(User::getDatabaseId, user -> user));

        Map<Integer, List<User>> usersByTeam = usersById.values().stream()
            .collect(Collectors.groupingBy(User::getTeamId));

        List<PlayerUpdate> updates = playerUpdateRepository.findBySourceIn(
            List.of(MessageType.DROP, MessageType.FUSE, MessageType.RITUAL)
        );
        Map<Integer, List<PlayerUpdate>> updatesByTeam = updatesByTeam(updates, usersById);
        List<PlayerUpdate> creditedUpdates = updatesByTeam.values().stream()
            .flatMap(Collection::stream)
            .toList();

        List<CreditsResponse.TeamCredits> teams = teamsById.values().stream()
            .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
            .map(team -> teamCredits(team, usersByTeam.getOrDefault(team.getTeamId(), List.of())))
            .toList();

        List<CreditsResponse.TeamStats> teamStats = teamsById.values().stream()
            .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
            .map(team -> teamStats(team.getTeamId(), updatesByTeam.getOrDefault(team.getTeamId(), List.of())))
            .toList();

        return new CreditsResponse(teams, allTeamStats(creditedUpdates), teamStats);
    }

    private CreditsResponse.TeamCredits teamCredits(Team team, List<User> players) {
        LibraryUpdate libraryUpdate = gameStateService.getLatestLibraryUpdate(team.getTeamId());
        List<CreditsResponse.PlayerCredits> playerCredits = players.stream()
            .sorted(Comparator.comparing(user -> displayName(user).toLowerCase()))
            .map(user -> new CreditsResponse.PlayerCredits(user.getDatabaseId(), displayName(user)))
            .toList();
        return new CreditsResponse.TeamCredits(
            team.getTeamId(),
            team.getName(),
            libraryUpdate.hasCompletedHundo(),
            libraryUpdate.completionTime(),
            playerCredits
        );
    }

    private CreditsResponse.AllTeamStats allTeamStats(List<PlayerUpdate> updates) {
        return new CreditsResponse.AllTeamStats(
            countSource(updates, MessageType.DROP),
            countSource(updates, MessageType.FUSE),
            countSource(updates, MessageType.RITUAL),
            updates.stream()
                .filter(update -> update.getSource() == MessageType.FUSE)
                .filter(update -> update.getValue() == TWIN_HEADED_THUNDER_DRAGON_CARD_ID)
                .count()
        );
    }

    private CreditsResponse.TeamStats teamStats(int teamId, List<PlayerUpdate> updates) {
        List<PlayerUpdate> drops = updates.stream()
            .filter(update -> update.getSource() == MessageType.DROP)
            .toList();
        List<PlayerUpdate> nonThtdFusions = updates.stream()
            .filter(update -> update.getSource() == MessageType.FUSE)
            .filter(update -> update.getValue() != TWIN_HEADED_THUNDER_DRAGON_CARD_ID)
            .toList();
        long heishinDrops = drops.stream()
            .filter(update -> update.getOpponentId() == 8 || update.getOpponentId() == 35)
            .count();
        long seto3Drops = drops.stream()
            .filter(update -> update.getOpponentId() == 36)
            .count();

        return new CreditsResponse.TeamStats(
            teamId,
            countRows(drops, PlayerUpdate::getValue),
            countRows(nonThtdFusions, PlayerUpdate::getValue),
            heishinDrops,
            seto3Drops,
            countRows(drops, PlayerUpdate::getOpponentId)
        );
    }

    private Map<Integer, List<PlayerUpdate>> updatesByTeam(List<PlayerUpdate> updates, Map<Long, User> usersById) {
        Map<Integer, List<PlayerUpdate>> byTeam = new HashMap<>();
        for(PlayerUpdate update : updates) {
            User user = usersById.get(update.getParticipantId());
            if(user != null) {
                byTeam.computeIfAbsent(user.getTeamId(), _ -> new java.util.ArrayList<>()).add(update);
            }
        }
        return byTeam;
    }

    private static long countSource(List<PlayerUpdate> updates, MessageType source) {
        return updates.stream()
            .filter(update -> update.getSource() == source)
            .count();
    }

    private static List<CreditsResponse.CountRow> countRows(
            List<PlayerUpdate> updates,
            ToIntFunction<PlayerUpdate> classifier) {
        return updates.stream()
            .collect(Collectors.groupingBy(update -> classifier.applyAsInt(update), Collectors.counting()))
            .entrySet()
            .stream()
            .map(entry -> new CreditsResponse.CountRow(entry.getKey(), entry.getValue()))
            .sorted(Comparator
                .comparingLong(CreditsResponse.CountRow::count).reversed()
                .thenComparingInt(CreditsResponse.CountRow::id))
            .toList();
    }

    private static String displayName(User user) {
        String name = user.getName();
        if(name == null || name.isBlank()) {
            return "Player " + user.getDatabaseId();
        }
        return name;
    }
}
