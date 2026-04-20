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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;

@SpringBootTest
@AutoConfigureMockMvc
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
    private ApiKeyService apiKeyService;

    private User testUser;
    private String validApiKey;

    @BeforeEach
    void setUp() {
        // Clear repositories before each test
        playerUpdateRepository.deleteAll();
        userRepository.deleteAll();

        // Create a test user
        testUser = new User();
        testUser.setTwitchId("test_twitch_id");
        testUser.setName("TestUser");
        testUser.setTeamId(1);
        testUser.setOauth("test_oauth_token");
        testUser = userRepository.save(testUser);

        // Generate a valid API key
        validApiKey = apiKeyService.generateNewApiKey(testUser);
    }

    @Test
    void testValidateEndpointWithValidApiKey() throws Exception {
        mockMvc.perform(get("/api/validate")
                .header("X-API-Key", validApiKey)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"))
                .andExpect(jsonPath("$.message").value("TestUser"));
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
                new EmuMessage(MessageType.DROP, 122, 0, 1),
                new EmuMessage(MessageType.STARCHIPS, 5, 0, 0)
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
    void testUpdateEndpointWithInvalidApiKey() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 122, 0, 1)
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
                new EmuMessage(MessageType.DROP, 122, 0, 1)
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
    void testUpdateEndpointWithMultipleMessages() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                new EmuMessage(MessageType.DROP, 100, 5, 6),
                new EmuMessage(MessageType.FUSE, 50, 10, 11),
                new EmuMessage(MessageType.RITUAL, 75, 20, 21),
                new EmuMessage(MessageType.STARCHIPS, 10, 0, 0)
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
                new EmuMessage(MessageType.STARCHIPS, 5, 0, 0)
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

}
