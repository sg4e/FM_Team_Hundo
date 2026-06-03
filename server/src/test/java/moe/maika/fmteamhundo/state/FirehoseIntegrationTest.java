package moe.maika.fmteamhundo.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import moe.maika.fmteamhundo.api.EmuMessage;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FirehoseIntegrationTest {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private UserMappings userMappings;

    @Autowired
    private GameStateService gameStateService;

    @Autowired
    @Qualifier("playerUpdateHandler")
    private FirehoseHandler playerUpdateHandler;

    @Autowired
    @Qualifier("teamUpdateHandler")
    private FirehoseHandler teamUpdateHandler;

    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
    private final List<WebSocketSession> sessions = new ArrayList<>();

    private User team1User;
    private User team2User;
    private String team1ApiKey;
    private String team2ApiKey;

    @BeforeEach
    void setUp() {
        closeSessions();
        gameStateService.reset();
        playerUpdateRepository.deleteAll();
        userRepository.deleteAll();
        userMappings.clearCaches();

        team1User = createUser("team1_user", "Team1User", 1);
        team2User = createUser("team2_user", "Team2User", 2);
        team1ApiKey = apiKeyService.generateNewApiKey(team1User);
        team2ApiKey = apiKeyService.generateNewApiKey(team2User);
    }

    @AfterEach
    void tearDown() {
        closeSessions();
    }

    @Test
    void playerEndpointEchoesMessagesAndTracksSessions() throws Exception {
        CollectingWebSocketHandler client = new CollectingWebSocketHandler();
        WebSocketSession session = connect("/firehose/player", client);

        assertThat(playerUpdateHandler.getEndpointPath()).isEqualTo("/firehose/player");
        assertSessionCount(playerUpdateHandler, 1);

        session.sendMessage(new TextMessage("ping-player"));

        assertThat(client.awaitMessages(1)).isTrue();
        assertThat(client.payloads()).containsExactly("ping-player");

        session.close(CloseStatus.NORMAL);
        assertSessionCount(playerUpdateHandler, 0);
    }

    @Test
    void teamEndpointEchoesMessagesAndIsIndependentFromPlayerEndpoint() throws Exception {
        CollectingWebSocketHandler playerClient = new CollectingWebSocketHandler();
        CollectingWebSocketHandler teamClient = new CollectingWebSocketHandler();
        WebSocketSession playerSession = connect("/firehose/player", playerClient);
        WebSocketSession teamSession = connect("/firehose/team", teamClient);

        assertSessionCount(playerUpdateHandler, 1);
        assertSessionCount(teamUpdateHandler, 1);

        teamSession.sendMessage(new TextMessage("ping-team"));

        assertThat(teamClient.awaitMessages(1)).isTrue();
        assertThat(teamClient.payloads()).containsExactly("ping-team");
        assertThat(playerClient.payloads()).isEmpty();

        playerSession.close(CloseStatus.NORMAL);
        assertSessionCount(playerUpdateHandler, 0);
        assertSessionCount(teamUpdateHandler, 1);
    }

    @Test
    void apiUpdatesAreBroadcastToPlayerFirehoseAsPlayerUpdateJson() throws Exception {
        CollectingWebSocketHandler playerClient = new CollectingWebSocketHandler();
        connect("/firehose/player", playerClient);

        postUpdates(team1ApiKey, List.of(
            new EmuMessage(MessageType.DROP, 122, 3, 4, 5),
            new EmuMessage(MessageType.STARCHIPS, 250, 0, 0, 0)
        ));

        assertThat(playerClient.awaitMessages(2)).isTrue();

        List<JsonNode> payloads = playerClient.jsonPayloads(objectMapper);
        assertThat(payloads)
            .extracting(node -> node.get("source").asString())
            .containsExactlyInAnyOrder("drop", "starchips");

        JsonNode dropUpdate = payloads.stream()
            .filter(node -> node.get("source").asString().equals("drop"))
            .findFirst()
            .orElseThrow();
        assertThat(dropUpdate.get("value").asInt()).isEqualTo(122);
        assertThat(dropUpdate.get("participantId").asLong()).isEqualTo(team1User.getDatabaseId());
        assertThat(dropUpdate.get("lastRng").asInt()).isEqualTo(3);
        assertThat(dropUpdate.get("nowRng").asInt()).isEqualTo(4);
        assertThat(dropUpdate.get("opponentId").asInt()).isEqualTo(5);
    }

    @Test
    void apiUpdatesAreBroadcastToTeamFirehoseAsLibrarySnapshots() throws Exception {
        CollectingWebSocketHandler teamClient = new CollectingWebSocketHandler();
        connect("/firehose/team", teamClient);

        postUpdates(team1ApiKey, List.of(
            new EmuMessage(MessageType.DROP, 122, 3, 4, 5),
            new EmuMessage(MessageType.STARCHIPS, 250, 0, 0, 0)
        ));

        assertThat(teamClient.awaitMessages(1)).isTrue();

        JsonNode snapshot = objectMapper.readTree(teamClient.payloads().get(0));
        assertThat(snapshot.get("teamId").asInt()).isEqualTo(1);
        assertThat(snapshot.get("totalStarchips").asLong()).isEqualTo(250L);
        assertThat(snapshot.get("uniqueCardCount").asInt()).isEqualTo(1);
        assertThat(snapshot.has("cardIds")).isFalse();
        assertThat(snapshot.get("newAcquisitions")).hasSize(1);
        assertThat(snapshot.get("newAcquisitions").get(0).get("cardId").asInt()).isEqualTo(122);
        assertThat(snapshot.get("newAcquisitions").get(0).get("playerId").asLong()).isEqualTo(team1User.getDatabaseId());
    }

    @Test
    void playerAndTeamFirehosesReceiveOnlyTheirOwnPayloadShapes() throws Exception {
        CollectingWebSocketHandler playerClient = new CollectingWebSocketHandler();
        CollectingWebSocketHandler teamClient = new CollectingWebSocketHandler();
        connect("/firehose/player", playerClient);
        connect("/firehose/team", teamClient);

        postUpdates(team2ApiKey, List.of(new EmuMessage(MessageType.FUSE, 200, 1, 2, 3)));

        assertThat(playerClient.awaitMessages(1)).isTrue();
        assertThat(teamClient.awaitMessages(1)).isTrue();

        JsonNode playerPayload = objectMapper.readTree(playerClient.payloads().get(0));
        JsonNode teamPayload = objectMapper.readTree(teamClient.payloads().get(0));

        assertThat(playerPayload.has("source")).isTrue();
        assertThat(playerPayload.get("source").asString()).isEqualTo("fuse");
        assertThat(playerPayload.has("teamId")).isFalse();

        assertThat(teamPayload.has("teamId")).isTrue();
        assertThat(teamPayload.get("teamId").asInt()).isEqualTo(2);
        assertThat(teamPayload.has("source")).isFalse();
    }

    @Test
    void closedWebSocketSessionsAreRemovedBeforeBroadcasting() throws Exception {
        CollectingWebSocketHandler closedClient = new CollectingWebSocketHandler();
        WebSocketSession closedSession = connect("/firehose/player", closedClient);
        closedSession.close(CloseStatus.NORMAL);
        assertSessionCount(playerUpdateHandler, 0);

        CollectingWebSocketHandler activeClient = new CollectingWebSocketHandler();
        connect("/firehose/player", activeClient);

        postUpdates(team1ApiKey, List.of(new EmuMessage(MessageType.RITUAL, 667, 7, 8, 9)));

        assertThat(activeClient.awaitMessages(1)).isTrue();
        assertThat(closedClient.payloads()).isEmpty();
        assertSessionCount(playerUpdateHandler, 1);
    }

    private User createUser(String twitchId, String name, int teamId) {
        User user = new User();
        user.setTwitchId(twitchId);
        user.setName(name);
        user.setTeamId(teamId);
        user.setOauth("oauth_" + twitchId);
        return userRepository.save(user);
    }

    private WebSocketSession connect(String path, CollectingWebSocketHandler handler) throws Exception {
        WebSocketSession session = webSocketClient.execute(handler, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + path))
            .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private void postUpdates(String apiKey, List<EmuMessage> messages) throws Exception {
        mockMvc.perform(post("/api/update")
            .header("X-API-Key", apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(messages)))
            .andExpect(status().isOk());
    }

    private static void assertSessionCount(FirehoseHandler handler, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + EVENT_TIMEOUT.toNanos();
        while (handler.getSessionCount() != expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(handler.getSessionCount()).isEqualTo(expectedCount);
    }

    private void closeSessions() {
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception ignored) {
                // Best effort cleanup between integration tests.
            }
        }
        sessions.clear();
    }

    private static final class CollectingWebSocketHandler extends TextWebSocketHandler {
        private final List<String> payloads = new CopyOnWriteArrayList<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            payloads.add(message.getPayload());
        }

        List<String> payloads() {
            return payloads;
        }

        List<JsonNode> jsonPayloads(ObjectMapper objectMapper) throws Exception {
            List<JsonNode> nodes = new ArrayList<>();
            for (String payload : payloads) {
                nodes.add(objectMapper.readTree(payload));
            }
            return nodes;
        }

        boolean awaitMessages(int expectedCount) throws InterruptedException {
            long deadline = System.nanoTime() + EVENT_TIMEOUT.toNanos();
            while (payloads.size() < expectedCount && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            return payloads.size() >= expectedCount;
        }
    }
}
