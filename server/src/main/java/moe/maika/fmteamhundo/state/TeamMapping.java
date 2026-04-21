package moe.maika.fmteamhundo.state;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Component
public class TeamMapping {
    
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final Map<Long, Team> userToTeamCache = new HashMap<>();
    private final Map<Integer, Team> teamIdToTeamCache = new HashMap<>();

    @Autowired
    public TeamMapping(TeamRepository teamRepository, UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
    }

    public Team getTeamForUserId(long userId) {
        if(userToTeamCache.containsKey(userId)) {
            return userToTeamCache.get(userId);
        }
        int teamId = userRepository.getByDatabaseId(userId).map(u -> u.getTeamId()).get();
        if(teamId != 0) {
            userToTeamCache.put(userId, teamRepository.findById(teamId).get());
            return userToTeamCache.get(userId);
        }
        return null;
    }

    public String getTeamNameForTeamId(int teamId) {
        if(teamIdToTeamCache.containsKey(teamId)) {
            return teamIdToTeamCache.get(teamId).getName();
        }
        Team team = teamRepository.findById(teamId).orElse(null);
        if(team != null) {
            teamIdToTeamCache.put(teamId, team);
            return team.getName();
        }
        return "No Team";
    }
    
}
