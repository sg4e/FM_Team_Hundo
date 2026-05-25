package moe.maika.fmteamhundo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.repos.TeamRepository;

class TeamServiceTest {

    private TeamRepository teamRepository;
    private TeamService teamService;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        when(teamRepository.findAll()).thenReturn(List.of(team(1, "Before")));
        teamService = new TeamService(teamRepository);
    }

    @Test
    void renameTeamUpdatesNameAndCache() {
        Team team = team(1, "Old Name");
        when(teamRepository.findById(1)).thenReturn(Optional.of(team));
        when(teamRepository.findByNameIgnoreCase("New Name")).thenReturn(Optional.empty());
        when(teamRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        teamService.renameTeam(1, "New Name");

        assertThat(team.getName()).isEqualTo("New Name");
        verify(teamRepository).save(team);
        assertThat(teamService.getTeamById(1).getName()).isEqualTo("New Name");
    }

    @Test
    void renameTeamRejectsBlankName() {
        assertThatThrownBy(() -> teamService.renameTeam(1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");

        assertThatThrownBy(() -> teamService.renameTeam(1, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");

        assertThatThrownBy(() -> teamService.renameTeam(1, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void renameTeamRejectsNoTeamSentinel() {
        assertThatThrownBy(() -> teamService.renameTeam(0, "Some Name"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no-team");
    }

    @Test
    void renameTeamRejectsDuplicateNameCaseInsensitive() {
        Team team = team(1, "Old Name");
        Team other = team(2, "NEW NAME");
        when(teamRepository.findById(1)).thenReturn(Optional.of(team));
        when(teamRepository.findByNameIgnoreCase("New Name")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> teamService.renameTeam(1, "New Name"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void renameTeamAllowsSelfRenameToSameName() {
        Team team = team(1, "Team A");
        when(teamRepository.findById(1)).thenReturn(Optional.of(team));
        when(teamRepository.findByNameIgnoreCase("Team A")).thenReturn(Optional.of(team));
        when(teamRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        teamService.renameTeam(1, "Team A");

        assertThat(team.getName()).isEqualTo("Team A");
        verify(teamRepository).save(team);
    }

    @Test
    void renameTeamAllowsSelfRenameDifferentCase() {
        Team team = team(1, "Team A");
        when(teamRepository.findById(1)).thenReturn(Optional.of(team));
        when(teamRepository.findByNameIgnoreCase("team a")).thenReturn(Optional.of(team));
        when(teamRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        teamService.renameTeam(1, "team a");

        assertThat(team.getName()).isEqualTo("team a");
        verify(teamRepository).save(team);
    }

    @Test
    void renameTeamTrimsWhitespace() {
        Team team = team(1, "Old Name");
        when(teamRepository.findById(1)).thenReturn(Optional.of(team));
        when(teamRepository.findByNameIgnoreCase("New Name")).thenReturn(Optional.empty());
        when(teamRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        teamService.renameTeam(1, "  New Name  ");

        assertThat(team.getName()).isEqualTo("New Name");
    }

    @Test
    void renameTeamThrowsWhenTeamNotFound() {
        when(teamRepository.findById(99)).thenReturn(Optional.empty());
        when(teamRepository.findByNameIgnoreCase("New Name")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.renameTeam(99, "New Name"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void renameTeamUpdatesCacheImmediately() {
        Team team = team(1, "Before");
        when(teamRepository.findById(1)).thenReturn(Optional.of(team));
        when(teamRepository.findByNameIgnoreCase("After")).thenReturn(Optional.empty());
        when(teamRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(teamService.getTeamById(1).getName()).isEqualTo("Before");

        teamService.renameTeam(1, "After");

        assertThat(teamService.getTeamById(1).getName()).isEqualTo("After");
        assertThat(teamService.getTeams()).extracting(Team::getName).contains("After");
    }

    private static Team team(int id, String name) {
        Team team = new Team(id, name);
        return team;
    }
}
