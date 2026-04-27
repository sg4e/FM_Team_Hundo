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
}
