package moe.maika.fmteamhundo.api;

import moe.maika.fmteamhundo.data.entities.Team;

public record TeamJson(String name, int id) { 
    public static TeamJson fromTeam(Team team) {
        return new TeamJson(team.getName(), team.getTeamId());
    }
}
