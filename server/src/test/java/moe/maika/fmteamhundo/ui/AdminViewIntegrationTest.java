package moe.maika.fmteamhundo.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.TeamService;

@SpringBootTest
@ActiveProfiles("test")
class AdminViewIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamService teamService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        teamRepository.deleteAll();
    }

    @Test
    void testFindByTeamIdAndRegisteredForNextHundo_FiltersCorrectly() {
        // Create test users
        User registeredNoTeam = createUser("registered_no_team", 0, true);
        User unregisteredNoTeam = createUser("unregistered_no_team", 0, false);
        User registeredWithTeam = createUser("registered_with_team", 1, true);
        User unregisteredWithTeam = createUser("unregistered_with_team", 1, false);

        userRepository.saveAll(List.of(registeredNoTeam, unregisteredNoTeam, registeredWithTeam, unregisteredWithTeam));

        // Query for unassigned users who registered for next hundo
        List<User> results = userRepository.findByTeamIdAndRegisteredForNextHundo(0, true);

        // Should only include users with teamId 0 AND registeredForNextHundo = true
        assertThat(results).hasSize(1);
        assertThat(results).extracting(User::getName).contains("registered_no_team");
    }

    @Test
    void testFindByTeamIdAndRegisteredForNextHundo_EmptyWhenNoMatches() {
        // Create test users
        User unregisteredNoTeam = createUser("unregistered_no_team", 0, false);
        User registeredWithTeam = createUser("registered_with_team", 1, true);

        userRepository.saveAll(List.of(unregisteredNoTeam, registeredWithTeam));

        // Query for unassigned users who registered for next hundo
        List<User> results = userRepository.findByTeamIdAndRegisteredForNextHundo(0, true);

        // Should be empty
        assertThat(results).isEmpty();
    }

    @Test
    void testFindByTeamIdAndRegisteredForNextHundo_MultipleMatches() {
        // Create multiple users registered and without teams
        User user1 = createUser("user1", 0, true);
        User user2 = createUser("user2", 0, true);
        User user3 = createUser("user3", 0, true);
        User user4 = createUser("user4", 0, false); // This one is not registered

        userRepository.saveAll(List.of(user1, user2, user3, user4));

        // Query for unassigned users who registered for next hundo
        List<User> results = userRepository.findByTeamIdAndRegisteredForNextHundo(0, true);

        // Should only include the registered users
        assertThat(results).hasSize(3);
        assertThat(results).extracting(User::getName).containsExactlyInAnyOrder("user1", "user2", "user3");
    }

    @Test
    void renameTeamPersistsToDB() {
        Team team = new Team("Original Name");
        teamRepository.save(team);

        teamService.renameTeam(team.getTeamId(), "Renamed");

        Team fromDb = teamRepository.findById(team.getTeamId()).orElseThrow();
        assertThat(fromDb.getName()).isEqualTo("Renamed");
    }

    @Test
    void renameTeamUpdatesCacheImmediately() {
        Team team = new Team("Original");
        teamRepository.save(team);

        teamService.renameTeam(team.getTeamId(), "Renamed");

        assertThat(teamService.getTeamById(team.getTeamId()).getName()).isEqualTo("Renamed");
        assertThat(teamService.getTeams()).extracting(Team::getName).contains("Renamed");
    }

    @Test
    void renameTeamRejectsBlankName() {
        Team team = new Team("Original");
        teamRepository.save(team);

        assertThatThrownBy(() -> teamService.renameTeam(team.getTeamId(), "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void renameTeamRejectsDuplicateName() {
        Team teamA = new Team("Team A");
        teamRepository.save(teamA);
        Team teamB = new Team("Team B");
        teamRepository.save(teamB);

        assertThatThrownBy(() -> teamService.renameTeam(teamA.getTeamId(), "Team B"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void renameTeamRejectsDuplicateNameCaseInsensitive() {
        Team teamA = new Team("Team A");
        teamRepository.save(teamA);
        Team teamB = new Team("Team B");
        teamRepository.save(teamB);

        assertThatThrownBy(() -> teamService.renameTeam(teamA.getTeamId(), "team b"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void findByNameIgnoreCaseFindsMatch() {
        Team team = new Team("My Team");
        teamRepository.save(team);

        Team found = teamRepository.findByNameIgnoreCase("MY TEAM").orElseThrow();
        assertThat(found.getTeamId()).isEqualTo(team.getTeamId());
    }

    private User createUser(String name, int teamId, boolean registeredForNextHundo) {
        User user = new User();
        user.setTwitchId(name + "_twitch_id");
        user.setName(name);
        user.setTeamId(teamId);
        user.setRegisteredForNextHundo(registeredForNextHundo);
        user.setOauth("test_oauth_token");
        return user;
    }
}
