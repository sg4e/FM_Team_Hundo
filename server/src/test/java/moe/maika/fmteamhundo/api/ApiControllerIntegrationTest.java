package moe.maika.fmteamhundo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;
import moe.maika.fmteamhundo.state.HundoConstants;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private HundoConstants hundoConstants;

    private User testUser;
    private Team testTeam;
    private String validApiKey;

    @BeforeEach
    void setUp() {
        // Clear repositories before each test
        playerUpdateRepository.deleteAll();
        userRepository.deleteAll();
        teamRepository.deleteAll();

        // Create a test team
        testTeam = new Team("Test Team");
        testTeam = teamRepository.save(testTeam);

        // Create a test user
        testUser = new User();
        testUser.setTwitchId("test_twitch_id");
        testUser.setName("TestUser");
        testUser.setAltAccount("TestAlt");
        testUser.setTeamId(testTeam.getTeamId());
        testUser.setOauth("test_oauth_token");
        testUser = userRepository.save(testUser);

        // Generate a valid API key
        validApiKey = apiKeyService.generateNewApiKey(testUser);
    }


    private User createNoTeamUser(String twitchId) {
        User noTeamUser = new User();
        noTeamUser.setTwitchId(twitchId);
        noTeamUser.setName("NoTeamUser");
        noTeamUser.setTeamId(0);
        noTeamUser.setOauth("test_oauth_token");
        return userRepository.save(noTeamUser);
    }

    @Test
    void testPlayersEndpointReturnsUsersAssignedToTeams() throws Exception {
        User noTeamUser = new User();
        noTeamUser.setTwitchId("no_team_user");
        noTeamUser.setName("NoTeamUser");
        noTeamUser.setTeamId(0);
        noTeamUser.setOauth("test_oauth_token");
        userRepository.save(noTeamUser);

        mockMvc.perform(get("/api/players")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(testUser.getDatabaseId()))
                .andExpect(jsonPath("$[0].twitchId").value("test_twitch_id"))
                .andExpect(jsonPath("$[0].name").value("TestUser"))
                .andExpect(jsonPath("$[0].altAccount").value("TestAlt"))
                .andExpect(jsonPath("$[0].teamId").value(testTeam.getTeamId()));
    }

    @Test
    void testTeamsEndpointReturnsTeams() throws Exception {
        Team otherTeam = new Team("Other Team");
        otherTeam = teamRepository.save(otherTeam);

        mockMvc.perform(get("/api/teams")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == %s && @.name == 'Test Team')]",
                        testTeam.getTeamId()).exists())
                .andExpect(jsonPath("$[?(@.id == %s && @.name == 'Other Team')]",
                        otherTeam.getTeamId()).exists());
    }

    @Test
    void testLibraryEndpointReturnsLatestEmptyLibrarySnapshot() throws Exception {
        int emptyTeamId = testTeam.getTeamId() + 10_000;

        mockMvc.perform(get("/api/library/{teamId}", emptyTeamId)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library").doesNotExist())
                .andExpect(jsonPath("$.teamId").value(emptyTeamId))
                .andExpect(jsonPath("$.totalStarchips").value(0))
                .andExpect(jsonPath("$.uniqueCardCount").value(0))
                .andExpect(jsonPath("$.newAcquisitions").isArray())
                .andExpect(jsonPath("$.newAcquisitions.length()").value(0))
                .andExpect(jsonPath("$.hasCompletedHundo").value(false))
                .andExpect(jsonPath("$.bewdCount").value(0));
    }

    @Test
    void testLibraryEndpointWithInvalidTeamIdReturnsApiError() throws Exception {
        mockMvc.perform(get("/api/library/not-an-integer")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid path parameter"));
    }

    @Test
    void testCreditsEndpointIsPublicAndReturnsRosterStatsShape() throws Exception {
        mockMvc.perform(get("/api/credits")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams").isArray())
                .andExpect(jsonPath("$.teams[?(@.id == %s && @.name == 'Test Team')]",
                        testTeam.getTeamId()).exists())
                .andExpect(jsonPath("$.allTeams.totalDrops").value(0))
                .andExpect(jsonPath("$.teamStats").isArray());
    }

    @Test
    void testProtocolVersionEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/protocol_version")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol_version").value("1"));
    }

    @Test
    void testValidateEndpointWithValidApiKey() throws Exception {
        mockMvc.perform(get("/api/validate")
                .header("X-API-Key", validApiKey)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"))
                .andExpect(jsonPath("$.message").value("TestUser"))
                .andExpect(jsonPath("$.protocol_version").value("1"));
    }

    @Test
    void testValidateEndpointWithInvalidApiKey() throws Exception {
        mockMvc.perform(get("/api/validate")
                .header("X-API-Key", "invalid_key_that_definitely_does_not_exist")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid API key"));
    }

    @Test
    void testValidateEndpointWithMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/validate")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testValidateEndpointWithBlankApiKey() throws Exception {
        mockMvc.perform(get("/api/validate")
                .header("X-API-Key", "")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testUpdateEndpointWithValidApiKey() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1),
                new EmuMessage(MessageType.STARCHIPS, 5, 0, 0, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"))
                .andExpect(jsonPath("$.message").value("TestUser"));

        // Verify database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(2);

        PlayerUpdate firstUpdate = updates.get(0);
        assertThat(firstUpdate.getSource()).isEqualTo(MessageType.DROP);
        assertThat(firstUpdate.getValue()).isEqualTo(122);
        assertThat(firstUpdate.getLastRng()).isEqualTo(0);
        assertThat(firstUpdate.getNowRng()).isEqualTo(1);
        assertThat(firstUpdate.getParticipantId()).isEqualTo(testUser.getDatabaseId());

        PlayerUpdate secondUpdate = updates.get(1);
        assertThat(secondUpdate.getSource()).isEqualTo(MessageType.STARCHIPS);
        assertThat(secondUpdate.getValue()).isEqualTo(5);
        assertThat(secondUpdate.getParticipantId()).isEqualTo(testUser.getDatabaseId());
    }


    @Test
    void testUpdateEndpointWithTestHeaderReturnsTestResponseWithoutPersisting() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1),
                new EmuMessage(MessageType.STARCHIPS, 5, 0, 0, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .header("test", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"))
                .andExpect(jsonPath("$.message").value("TestUser"))
                .andExpect(jsonPath("$.test").value(true));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointAllowsUserWithNoTeamInTestMode() throws Exception {
        User noTeamUser = createNoTeamUser("no_team_test_user");
        String noTeamApiKey = apiKeyService.generateNewApiKey(noTeamUser);
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", noTeamApiKey)
                .header("test", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"))
                .andExpect(jsonPath("$.message").value("NoTeamUser"))
                .andExpect(jsonPath("$.test").value(true));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointTreatsFalseTestHeaderAsNormalMode() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .header("test", " false ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"))
                .andExpect(jsonPath("$.test").doesNotExist());

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);
    }

    @Test
    void testUpdateEndpointWithInvalidApiKeyStillFailsInTestMode() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", "invalid_api_key")
                .header("test", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid API key"));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointWithInvalidPayloadStillFailsInTestMode() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, -1, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .header("test", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid card id"));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointWithInvalidApiKey() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", "invalid_api_key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid API key"));

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointWithMissingApiKey() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isUnauthorized());

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointAcceptsRawMiddlewareDropPayload() throws Exception {
        String payload = """
                [{ "type": "drop", "value": 122, "last_rng": 0, "now_rng": 1, "opp_id": 1 }]
                """;

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getSource()).isEqualTo(MessageType.DROP);
    }

    @Test
    void testUpdateEndpointAcceptsRawMiddlewareFusePayload() throws Exception {
        String payload = """
                [{ "type": "fuse", "value": 30, "opp_id": 1 }]
                """;

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getSource()).isEqualTo(MessageType.FUSE);
    }

    @Test
    void testUpdateEndpointAcceptsRawMiddlewareRitualPayload() throws Exception {
        String payload = """
                [{ "type": "ritual", "value": 667, "opp_id": 1 }]
                """;

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getSource()).isEqualTo(MessageType.RITUAL);
    }

    @Test
    void testUpdateEndpointAcceptsRawMiddlewareStarchipsPayload() throws Exception {
        String payload = """
                [{ "type": "starchips", "value": 10 }]
                """;

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getSource()).isEqualTo(MessageType.STARCHIPS);
    }

    @Test
    void testUpdateEndpointReturnsJsonForMalformedPayload() throws Exception {
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{type:drop,value:122}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid update payload"));
    }

    @Test
    void testUpdateEndpointReturnsJsonForUnknownRawMessageType() throws Exception {
        String payload = """
                [{ "type": "unknown", "value": 122, "last_rng": 0, "now_rng": 1, "opp_id": 1 }]
                """;

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid update payload"));
    }

    @Test
    void testUpdateEndpointReturnsJsonForInvalidApiKeyWithRawPayload() throws Exception {
        String payload = """
                [{ "type": "drop", "value": 122, "last_rng": 0, "now_rng": 1, "opp_id": 1 }]
                """;

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", "invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid API key"));
    }

    @Test
    void testUpdateEndpointWithMultipleMessages() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 100, 5, 6, 1),
                new EmuMessage(MessageType.FUSE, 50, 10, 11, 1),
                new EmuMessage(MessageType.RITUAL, 75, 20, 21, 1),
                new EmuMessage(MessageType.STARCHIPS, 10, 0, 0, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk());

        // Verify all database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(4);

        assertThat(updates.get(0).getSource()).isEqualTo(MessageType.DROP);
        assertThat(updates.get(1).getSource()).isEqualTo(MessageType.FUSE);
        assertThat(updates.get(2).getSource()).isEqualTo(MessageType.RITUAL);
        assertThat(updates.get(3).getSource()).isEqualTo(MessageType.STARCHIPS);
    }

    @Test
    void testUpdateEndpointPreservesTimestamp() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.STARCHIPS, 5, 0, 0, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk())
                .andReturn();

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);

        PlayerUpdate update = updates.get(0);
        assertThat(update.getTime()).isNotNull();
    }

    @Test
    void testUpdateEndpointRejectsUserWithNoTeam() throws Exception {
        User noTeamUser = createNoTeamUser("no_team_user");
        String noTeamApiKey = apiKeyService.generateNewApiKey(noTeamUser);

        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", noTeamApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("User is not assigned to a team"));

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointRejectsUnobtainableCards() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, hundoConstants.getUnobtainableCards().iterator().next(), 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Update contains unobtainable cards"));

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointRejectsEmptyMessageList() throws Exception {
        List<EmuMessage> messages = List.of();

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("No updates provided"));

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }


    @Test
    void testUpdateEndpointWithInvalidCardId() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, -1, 0, 1, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid card id"));

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

    @Test
    void testUpdateEndpointWithExcessiveStarchips() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.STARCHIPS, 1_000_001, 0, 0, 1)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", validApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("error"))
                .andExpect(jsonPath("$.message").value("Total starchips cannot be equal to or exceed 1000000"));

        // Verify no database insertions
        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).isEmpty();
    }

}
