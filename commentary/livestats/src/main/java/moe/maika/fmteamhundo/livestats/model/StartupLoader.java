package moe.maika.fmteamhundo.livestats.model;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.api.PlayerJson;
import moe.maika.fmteamhundo.livestats.api.TeamJson;
import moe.maika.fmteamhundo.livestats.client.LiveStatsApiClient;

public class StartupLoader {
    private final LiveStatsApiClient apiClient;

    public StartupLoader(LiveStatsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public LiveStatsState load() throws IOException, InterruptedException {
        List<TeamJson> teams = apiClient.getTeams();
        List<PlayerJson> players = apiClient.getPlayers();
        List<TeamJson> realTeams = teams.stream()
            .filter(team -> team.id() > 0)
            .toList();
        Map<Integer, LibraryUpdate> librariesByTeamId = realTeams.stream()
            .collect(Collectors.toMap(TeamJson::id, team -> loadLibrary(team.id())));
        return new LiveStatsState(teams, players, librariesByTeamId);
    }

    private LibraryUpdate loadLibrary(int teamId) {
        try {
            return apiClient.getLibrary(teamId);
        } catch (IOException ex) {
            throw new StartupLoadException("Unable to load library for team " + teamId, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StartupLoadException("Interrupted loading library for team " + teamId, ex);
        }
    }

    public static class StartupLoadException extends RuntimeException {
        public StartupLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
