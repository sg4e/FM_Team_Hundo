package moe.maika.fmteamhundo.livestats.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import moe.maika.fmteamhundo.livestats.api.LibraryUpdate;
import moe.maika.fmteamhundo.livestats.api.PlayerJson;
import moe.maika.fmteamhundo.livestats.api.TeamJson;

public class LiveStatsApiClient {
    private static final TypeReference<List<PlayerJson>> PLAYER_LIST = new TypeReference<>() { };
    private static final TypeReference<List<TeamJson>> TEAM_LIST = new TypeReference<>() { };

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LiveStatsConfig config;

    public LiveStatsApiClient(HttpClient httpClient, ObjectMapper objectMapper, LiveStatsConfig config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public List<PlayerJson> getPlayers() throws IOException, InterruptedException {
        return get("/api/players", PLAYER_LIST);
    }

    public List<TeamJson> getTeams() throws IOException, InterruptedException {
        return get("/api/teams", TEAM_LIST);
    }

    public LibraryUpdate getLibrary(int teamId) throws IOException, InterruptedException {
        return get("/api/library/" + teamId, LibraryUpdate.class);
    }

    private <T> T get(String path, Class<T> type) throws IOException, InterruptedException {
        HttpResponse<String> response = sendGet(path);
        return objectMapper.readValue(response.body(), type);
    }

    private <T> T get(String path, TypeReference<T> type) throws IOException, InterruptedException {
        HttpResponse<String> response = sendGet(path);
        return objectMapper.readValue(response.body(), type);
    }

    private HttpResponse<String> sendGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(config.restUri(path))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + path + " returned HTTP " + response.statusCode());
        }
        return response;
    }
}
