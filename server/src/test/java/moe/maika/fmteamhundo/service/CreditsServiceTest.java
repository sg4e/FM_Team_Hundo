package moe.maika.fmteamhundo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

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

class CreditsServiceTest {

    @Test
    void getCreditsAggregatesRawRowsForCurrentRealTeams() {
        TeamRepository teamRepository = mock(TeamRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PlayerUpdateRepository playerUpdateRepository = mock(PlayerUpdateRepository.class);
        GameStateService gameStateService = mock(GameStateService.class);
        CreditsService service = new CreditsService(teamRepository, userRepository, playerUpdateRepository, gameStateService);

        Team alpha = new Team(1, "Alpha");
        Team beta = new Team(2, "Beta");
        Team noTeam = new Team(0, "No Team");
        User alphaRunner = user(10L, "Alpha Runner", 1);
        User betaRunner = user(20L, "Beta Runner", 2);
        User unassignedRunner = user(30L, "No Team Runner", 0);
        User orphanRunner = user(40L, "Orphan Runner", 99);

        when(teamRepository.findAll()).thenReturn(List.of(noTeam, beta, alpha));
        when(userRepository.findAll()).thenReturn(List.of(betaRunner, alphaRunner, unassignedRunner, orphanRunner));
        when(playerUpdateRepository.findBySourceIn(any())).thenReturn(List.of(
            update(alphaRunner, MessageType.DROP, 100, 8),
            update(alphaRunner, MessageType.DROP, 100, 35),
            update(alphaRunner, MessageType.DROP, 200, 36),
            update(alphaRunner, MessageType.FUSE, 613, 0),
            update(alphaRunner, MessageType.FUSE, 500, 0),
            update(alphaRunner, MessageType.RITUAL, 667, 0),
            update(betaRunner, MessageType.DROP, 300, 36),
            update(unassignedRunner, MessageType.DROP, 400, 8),
            update(orphanRunner, MessageType.FUSE, 500, 0)
        ));
        Instant completion = Instant.parse("2026-05-01T13:00:00Z");
        when(gameStateService.getLatestLibraryUpdate(1)).thenReturn(libraryUpdate(1, true, completion));
        when(gameStateService.getLatestLibraryUpdate(2)).thenReturn(libraryUpdate(2, false, null));

        CreditsResponse response = service.getCredits();

        assertThat(response.teams()).extracting(CreditsResponse.TeamCredits::id).containsExactly(1, 2);
        assertThat(response.teams().get(0).players()).extracting(CreditsResponse.PlayerCredits::name)
            .containsExactly("Alpha Runner");
        assertThat(response.teams().get(0).completionTime()).isEqualTo(completion);
        assertThat(response.allTeams().totalDrops()).isEqualTo(4);
        assertThat(response.allTeams().totalFusions()).isEqualTo(2);
        assertThat(response.allTeams().totalRituals()).isEqualTo(1);
        assertThat(response.allTeams().twinHeadedThunderDragonFusions()).isEqualTo(1);

        CreditsResponse.TeamStats alphaStats = response.teamStats().stream()
            .filter(stats -> stats.teamId() == 1)
            .findFirst()
            .orElseThrow();
        assertThat(alphaStats.dropCardCounts()).containsExactly(
            new CreditsResponse.CountRow(100, 2),
            new CreditsResponse.CountRow(200, 1)
        );
        assertThat(alphaStats.fusionCardCounts()).containsExactly(new CreditsResponse.CountRow(500, 1));
        assertThat(alphaStats.heishinDrops()).isEqualTo(2);
        assertThat(alphaStats.seto3Drops()).isEqualTo(1);
        assertThat(alphaStats.duelistDropCounts()).containsExactly(
            new CreditsResponse.CountRow(8, 1),
            new CreditsResponse.CountRow(35, 1),
            new CreditsResponse.CountRow(36, 1)
        );
    }

    private static User user(long id, String name, int teamId) {
        User user = new User();
        user.setDatabaseId(id);
        user.setName(name);
        user.setTeamId(teamId);
        return user;
    }

    private static PlayerUpdate update(User user, MessageType source, int value, int opponentId) {
        PlayerUpdate update = new PlayerUpdate();
        update.setParticipantId(user.getDatabaseId());
        update.setSource(source);
        update.setValue(value);
        update.setOpponentId(opponentId);
        update.setTime(Instant.parse("2026-05-01T12:00:00Z"));
        return update;
    }

    private static LibraryUpdate libraryUpdate(int teamId, boolean completed, Instant completionTime) {
        return new LibraryUpdate(null, teamId, Instant.parse("2026-05-01T12:00:00Z"), 0, 0, List.of(), 0, 0, 0,
            true, completed, completionTime, 0);
    }
}
