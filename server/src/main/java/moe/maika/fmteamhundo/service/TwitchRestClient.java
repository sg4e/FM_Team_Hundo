package moe.maika.fmteamhundo.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class TwitchRestClient implements TwitchHelixClient {

    private static final String HELIX_BASE_URL = "https://api.twitch.tv/helix";
    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.MIN;

    public TwitchRestClient(
            @Value("${spring.security.oauth2.client.registration.twitch.client-id:}") String clientId,
            @Value("${spring.security.oauth2.client.registration.twitch.client-secret:}") String clientSecret) {
        this.restClient = RestClient.builder().build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public Optional<TwitchUser> getUserByLogin(String login) {
        TwitchUsersResponse response = get("/users?login={login}", TwitchUsersResponse.class, login);
        return response.data().stream().findFirst()
                .map(user -> new TwitchUser(user.id(), user.login(), user.display_name()));
    }

    @Override
    public Optional<TwitchStream> getLiveStreamByUserId(String userId) {
        TwitchStreamsResponse response = get("/streams?user_id={userId}", TwitchStreamsResponse.class, userId);
        return response.data().stream().findFirst()
                .map(stream -> new TwitchStream(stream.id(), stream.user_id(), stream.user_login(), stream.started_at()));
    }

    @Override
    public List<TwitchVideo> getArchiveVideosByUserId(String userId, int limit) {
        TwitchVideosResponse response = get("/videos?user_id={userId}&type=archive&first={limit}", TwitchVideosResponse.class, userId,
                limit);
        return response.data().stream()
                .map(video -> new TwitchVideo(video.id(), video.stream_id(), video.user_id(), video.user_login(), video.created_at(),
                        video.duration()))
                .toList();
    }

    private <T> T get(String uri, Class<T> responseType, Object... uriVariables) {
        return restClient.get()
                .uri(HELIX_BASE_URL + uri, uriVariables)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken())
                .header("Client-Id", clientId)
                .retrieve()
                .body(responseType);
    }

    private String getAccessToken() {
        Instant now = Instant.now();
        String current = accessToken;
        if(current != null && expiresAt.isAfter(now.plusSeconds(60))) {
            return current;
        }
        synchronized(this) {
            if(accessToken != null && expiresAt.isAfter(now.plusSeconds(60))) {
                return accessToken;
            }
            if(clientId.isBlank() || clientSecret.isBlank()) {
                throw new IllegalStateException("Twitch client id/secret are not configured");
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("grant_type", "client_credentials");
            TwitchTokenResponse response = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TwitchTokenResponse.class);
            accessToken = response.access_token();
            expiresAt = now.plusSeconds(Math.max(0, response.expires_in()));
            return accessToken;
        }
    }

    private record TwitchTokenResponse(String access_token, int expires_in, String token_type) { }
    private record TwitchUsersResponse(List<TwitchUserResponse> data) { }
    private record TwitchUserResponse(String id, String login, String display_name) { }
    private record TwitchStreamsResponse(List<TwitchStreamResponse> data) { }
    private record TwitchStreamResponse(String id, String user_id, String user_login, Instant started_at) { }
    private record TwitchVideosResponse(List<TwitchVideoResponse> data) { }
    private record TwitchVideoResponse(String id, String stream_id, String user_id, String user_login, Instant created_at,
            String duration) { }
}
