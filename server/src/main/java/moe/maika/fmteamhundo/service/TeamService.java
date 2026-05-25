package moe.maika.fmteamhundo.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.repos.TeamRepository;

/**
 * Facilitates queries about teams.
 */
@Service
@EnableScheduling
public class TeamService {
    private final TeamRepository teamRepository;
    private List<Team> teams;
    private Map<Integer, Team> teamIdMap;

    @Autowired
    public TeamService(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
        refreshTeams();
    }

    public List<Team> getTeams() {
        return Collections.unmodifiableList(teams);
    }

    public Team getTeamById(int teamId) {
        return teamIdMap.get(teamId);
    }

    // check every 5 minutes for new teams
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    private void refreshTeams() {
        teams = teamRepository.findAll();
        teamIdMap = teams.stream().collect(java.util.stream.Collectors.toMap(Team::getTeamId, Function.identity()));
    }

    public void renameTeam(int teamId, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("Team name cannot be blank");
        }

        String trimmedName = newName.trim();

        if (teamId == 0) {
            throw new IllegalArgumentException("Cannot rename the no-team sentinel");
        }

        teamRepository.findByNameIgnoreCase(trimmedName).ifPresent(existing -> {
            if (existing.getTeamId() != teamId) {
                throw new IllegalArgumentException("A team with that name already exists");
            }
        });

        Team team = teamRepository.findById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found"));

        team.setName(trimmedName);
        teamRepository.save(team);

        teams.stream()
            .filter(t -> t.getTeamId() == teamId)
            .findFirst()
            .ifPresentOrElse(
                t -> t.setName(trimmedName),
                () -> teams.add(team));
        teamIdMap.put(teamId, team);
    }
}
