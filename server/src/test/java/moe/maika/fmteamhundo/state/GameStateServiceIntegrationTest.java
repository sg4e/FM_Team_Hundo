package moe.maika.fmteamhundo.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import moe.maika.fmteamhundo.api.EmuMessage;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ParameterizedClass
@ValueSource(booleans = { false, true })
class GameStateServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    private UserMappings userMappings;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private GameStateService gameStateService;

    private List<User> team1Users;
    private List<User> team2Users;
    private List<String> team1ApiKeys;
    private List<String> team2ApiKeys;

    private final boolean flushGameState;

    GameStateServiceIntegrationTest(boolean flushGameState) {
        this.flushGameState = flushGameState;
    }

    @BeforeEach
    void setUp() {
        // Reset GameStateService state
        gameStateService.reset();
        
        // Clear repositories before each test
        playerUpdateRepository.deleteAll();
        userRepository.deleteAll();
        userMappings.clearCaches();

        team1Users = new ArrayList<>();
        team2Users = new ArrayList<>();
        team1ApiKeys = new ArrayList<>();
        team2ApiKeys = new ArrayList<>();

        // Create 3 users on Team 1
        for (int i = 1; i <= 3; i++) {
            User user = new User();
            user.setTwitchId("team1_user" + i);
            user.setName("Team1User" + i);
            user.setTeamId(1);
            user.setOauth("oauth_token_team1_" + i);
            user = userRepository.save(user);
            team1Users.add(user);
            team1ApiKeys.add(apiKeyService.generateNewApiKey(user));
        }

        // Create 2 users on Team 2
        for (int i = 1; i <= 2; i++) {
            User user = new User();
            user.setTwitchId("team2_user" + i);
            user.setName("Team2User" + i);
            user.setTeamId(2);
            user.setOauth("oauth_token_team2_" + i);
            user = userRepository.save(user);
            team2Users.add(user);
            team2ApiKeys.add(apiKeyService.generateNewApiKey(user));
        }
        if(flushGameState) {
            gameStateService.reset();
            gameStateService.reloadFromDatabase();
        }
    }

    @Test
    void testSingleUserStarchipUpdate() throws Exception {
        List<EmuMessage> messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 100, 0, 0)
        );

        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(messages)))
                .andExpect(status().isOk());
        sleep();

        Library team1Library = gameStateService.getLibrary(1);
        assertThat(team1Library.getStarchips(team1Users.get(0).getDatabaseId())).isEqualTo(100);
        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(100);
    }

    @Test
    void testMultipleUsersOnSameTeamStarchips() throws Exception {
        // User 1 on Team 1 sends 50 starchips
        List<EmuMessage> user1Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 50, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user1Messages)))
                .andExpect(status().isOk());

        // User 2 on Team 1 sends 75 starchips
        List<EmuMessage> user2Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 75, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user2Messages)))
                .andExpect(status().isOk());

        // User 3 on Team 1 sends 25 starchips
        List<EmuMessage> user3Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 25, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(2))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(user3Messages)))
                .andExpect(status().isOk());
        sleep();
        Library team1Library = gameStateService.getLibrary(1);
        assertThat(team1Library.getStarchips(team1Users.get(0).getDatabaseId())).isEqualTo(50);
        assertThat(team1Library.getStarchips(team1Users.get(1).getDatabaseId())).isEqualTo(75);
        assertThat(team1Library.getStarchips(team1Users.get(2).getDatabaseId())).isEqualTo(25);
        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(150);
    }

    @Test
    void testTeamsIndependentStarchips() throws Exception {
        // Team 1: User 1 sends 100 starchips
        List<EmuMessage> team1User1Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 100, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team1User1Messages)))
                .andExpect(status().isOk());

        // Team 1: User 2 sends 50 starchips
        List<EmuMessage> team1User2Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 50, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team1User2Messages)))
                .andExpect(status().isOk());

        // Team 2: User 1 sends 200 starchips
        List<EmuMessage> team2User1Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 200, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team2ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team2User1Messages)))
                .andExpect(status().isOk());

        // Team 2: User 2 sends 75 starchips
        List<EmuMessage> team2User2Messages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 75, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team2ApiKeys.get(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team2User2Messages)))
                .andExpect(status().isOk());
        sleep();
        Library team1Library = gameStateService.getLibrary(1);
        Library team2Library = gameStateService.getLibrary(2);

        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(150);
        assertThat(team2Library.getTotalTeamStarchips()).isEqualTo(275);
    }

    @Test
    void testStarchipValuesAreOverwritten() throws Exception {
        // User 1 on Team 1 sends 50 starchips
        List<EmuMessage> firstMessages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 50, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(firstMessages)))
                .andExpect(status().isOk());

        Library team1Library = gameStateService.getLibrary(1);
        assertThat(team1Library.getStarchips(team1Users.get(0).getDatabaseId())).isEqualTo(50);
        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(50);

        // Same user sends 100 starchips (newer update)
        List<EmuMessage> secondMessages = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 100, 0, 0)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(secondMessages)))
                .andExpect(status().isOk());
        sleep();
        // Verify the value was overwritten with the most recent
        assertThat(team1Library.getStarchips(team1Users.get(0).getDatabaseId())).isEqualTo(100);
        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(100);
    }

    @Test
    void testCardAcquisitionTrackedPerTeam() throws Exception {
        // Team 1: User 1 acquires card 122 (DROP)
        List<EmuMessage> team1Card1 = Arrays.asList(
                createEmuMessage(MessageType.DROP, 122, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team1Card1)))
                .andExpect(status().isOk());

        // Team 1: User 2 acquires card 456 (FUSE)
        List<EmuMessage> team1Card2 = Arrays.asList(
                createEmuMessage(MessageType.FUSE, 456, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team1Card2)))
                .andExpect(status().isOk());

        // Team 2: User 1 acquires card 789 (RITUAL)
        List<EmuMessage> team2Card1 = Arrays.asList(
                createEmuMessage(MessageType.RITUAL, 789, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team2ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team2Card1)))
                .andExpect(status().isOk());

        sleep();
        Library team1Library = gameStateService.getLibrary(1);
        Library team2Library = gameStateService.getLibrary(2);

        Map<Integer, CardAcquisition> team1Cards = team1Library.getAcquiredCards();
        Map<Integer, CardAcquisition> team2Cards = team2Library.getAcquiredCards();

        assertThat(team1Cards).containsKeys(122, 456);
        assertThat(team1Cards.get(122).cardId()).isEqualTo(122);
        assertThat(team1Cards.get(456).cardId()).isEqualTo(456);

        assertThat(team2Cards).containsKeys(789);
        assertThat(team2Cards.get(789).cardId()).isEqualTo(789);

        // Verify team isolation
        assertThat(team1Cards).doesNotContainKey(789);
        assertThat(team2Cards).doesNotContainKeys(122, 456);
    }

    @Test
    void testMultipleCardTypesPerUser() throws Exception {
        // Team 1: User 1 acquires various card types
        List<EmuMessage> multiCardMessages = Arrays.asList(
                createEmuMessage(MessageType.DROP, 100, 0, 1),
                createEmuMessage(MessageType.FUSE, 200, 0, 1),
                createEmuMessage(MessageType.RITUAL, 300, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(multiCardMessages)))
                .andExpect(status().isOk());

        sleep();

        Library team1Library = gameStateService.getLibrary(1);
        Map<Integer, CardAcquisition> team1Cards = team1Library.getAcquiredCards();

        assertThat(team1Cards).hasSize(3);
        assertThat(team1Cards.get(100).source()).isEqualTo(MessageType.DROP);
        assertThat(team1Cards.get(200).source()).isEqualTo(MessageType.FUSE);
        assertThat(team1Cards.get(300).source()).isEqualTo(MessageType.RITUAL);
    }

    @Test
    void testCardAcquisitionTimeTracking() throws Exception {
        // Team 1: User 1 acquires card 122
        List<EmuMessage> cardMessage1 = Arrays.asList(
                createEmuMessage(MessageType.DROP, 122, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cardMessage1)))
                .andExpect(status().isOk());

        sleep();

        Library team1Library = gameStateService.getLibrary(1);
        CardAcquisition acquisition = team1Library.getAcquiredCards().get(122);

        assertThat(acquisition.acquisitionTime()).isNotNull();
        assertThat(acquisition.playerId()).isEqualTo(team1Users.get(0).getDatabaseId());
    }

    @Test
    void testFirstCardAcquisitionWins() throws Exception {
        Instant now = Instant.now();

        // Create two updates for the same card, one older and one newer
        List<PlayerUpdate> updates = Arrays.asList(
                createPlayerUpdate(team1Users.get(0), MessageType.DROP, 122, now.plusSeconds(10)),
                createPlayerUpdate(team1Users.get(1), MessageType.DROP, 122, now)
        );

        gameStateService.update(updates);
        sleep();

        Library team1Library = gameStateService.getLibrary(1);
        CardAcquisition acquisition = team1Library.getAcquiredCards().get(122);

        // Should be owned by User 2 (the one with the earlier timestamp)
        assertThat(acquisition.playerId()).isEqualTo(team1Users.get(1).getDatabaseId());
    }

    @Test
    void testCombinedTeamState() throws Exception {
        // Complex scenario: mix of starchips and cards across both teams

        // Team 1: User 1 - 50 starchips and 2 cards
        List<EmuMessage> team1User1 = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 50, 0, 0),
                createEmuMessage(MessageType.DROP, 100, 0, 1),
                createEmuMessage(MessageType.FUSE, 200, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team1User1)))
                .andExpect(status().isOk());

        // Team 1: User 2 - 75 starchips and 1 card
        List<EmuMessage> team1User2 = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 75, 0, 0),
                createEmuMessage(MessageType.RITUAL, 300, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team1ApiKeys.get(1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team1User2)))
                .andExpect(status().isOk());

        // Team 2: User 1 - 100 starchips and 2 cards
        List<EmuMessage> team2User1 = Arrays.asList(
                createEmuMessage(MessageType.STARCHIPS, 100, 0, 0),
                createEmuMessage(MessageType.DROP, 150, 0, 1),
                createEmuMessage(MessageType.FUSE, 250, 0, 1)
        );
        mockMvc.perform(post("/api/update")
                .header("X-API-Key", team2ApiKeys.get(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(team2User1)))
                .andExpect(status().isOk());
        sleep();

        Library team1Library = gameStateService.getLibrary(1);
        Library team2Library = gameStateService.getLibrary(2);

        // Verify Team 1
        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(125);
        assertThat(team1Library.getAcquiredCards()).hasSize(3).containsKeys(100, 200, 300);

        // Verify Team 2
        assertThat(team2Library.getTotalTeamStarchips()).isEqualTo(100);
        assertThat(team2Library.getAcquiredCards()).hasSize(2).containsKeys(150, 250);
    }

    @Test
    void testStateCanBeReloadedFromDatabaseInBatches() {
        Instant baseTime = Instant.now();
        List<PlayerUpdate> updates = new ArrayList<>();

        for (int i = 0; i < 1001; i++) {
            updates.add(createPlayerUpdate(team1Users.get(0), MessageType.STARCHIPS, i, baseTime.plusSeconds(i)));
        }
        updates.add(createPlayerUpdate(team1Users.get(1), MessageType.DROP, 777, baseTime.plusSeconds(2000)));

        playerUpdateRepository.saveAll(updates);

        gameStateService.reset();
        gameStateService.reloadFromDatabase();
        sleep();

        Library team1Library = gameStateService.getLibrary(1);
        assertThat(team1Library.getStarchips(team1Users.get(0).getDatabaseId())).isEqualTo(1000);
        assertThat(team1Library.getTotalTeamStarchips()).isEqualTo(1000);
        assertThat(team1Library.getAcquiredCards()).containsKey(777);
        assertThat(team1Library.getAcquiredCards().get(777).playerId()).isEqualTo(team1Users.get(1).getDatabaseId());
    }

    @Test
    void testProcessedTeamSnapshotIncludesLatestAcquisitions() throws Exception {
        List<EmuMessage> user1Messages = Arrays.asList(
            createEmuMessage(MessageType.DROP, 100, 0, 1),
            createEmuMessage(MessageType.FUSE, 200, 0, 1)
        );
        mockMvc.perform(post("/api/update")
            .header("X-API-Key", team1ApiKeys.get(0))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(user1Messages)))
            .andExpect(status().isOk());

        List<EmuMessage> user2Messages = Arrays.asList(
            createEmuMessage(MessageType.STARCHIPS, 75, 0, 0),
            createEmuMessage(MessageType.RITUAL, 300, 0, 1)
        );
        mockMvc.perform(post("/api/update")
            .header("X-API-Key", team1ApiKeys.get(1))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(user2Messages)))
            .andExpect(status().isOk());

        sleep();
        TeamPageSnapshot snapshot = gameStateService.getLatestTeamPageSnapshot(1);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.teamId()).isEqualTo(1);
        assertThat(snapshot.totalStarchips()).isEqualTo(75);
        assertThat(snapshot.uniqueCardCount()).isEqualTo(3);
        assertThat(snapshot.latestAcquisitions()).hasSize(3);
        assertThat(snapshot.latestAcquisitions().get(0).cardId()).isEqualTo(300);
    }

    @Test
    void testPlayerPageSnapshotTracksLatestTenNonStarchipUpdates() {
        Instant baseTime = Instant.now();
        List<PlayerUpdate> updates = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            updates.add(createPlayerUpdate(team1Users.get(0), MessageType.DROP, 100 + i, baseTime.plusSeconds(i)));
        }
        updates.add(createPlayerUpdate(team1Users.get(0), MessageType.STARCHIPS, 999, baseTime.plusSeconds(20)));

        gameStateService.update(updates);
        sleep();

        List<PlayerUpdate> latestUpdates = gameStateService.getLatestPlayerUpdates(team1Users.get(0).getDatabaseId());

        // Should track the 10 most recent DROP messages (non-starchips)
        assertThat(latestUpdates).hasSize(10);
        assertThat(latestUpdates).allMatch(update -> update.getSource() != MessageType.STARCHIPS);
        assertThat(latestUpdates.get(0).getValue()).isEqualTo(111);
        assertThat(latestUpdates.get(9).getValue()).isEqualTo(102);
    }

    @Test
    void testListenersReceiveLatestSnapshots() throws InterruptedException {
        TestTeamUpdateListener teamListener = new TestTeamUpdateListener();
        gameStateService.addTeamUpdateListener(teamListener);

        TestPlayerUpdateListener playerListener = new TestPlayerUpdateListener();
        gameStateService.addPlayerUpdateListener(team1Users.get(0).getDatabaseId(), playerListener);

        Instant baseTime = Instant.now();
        gameStateService.update(List.of(
            createPlayerUpdate(team1Users.get(0), MessageType.STARCHIPS, 321, baseTime),
            createPlayerUpdate(team1Users.get(0), MessageType.DROP, 123, baseTime.plusSeconds(1))
        ));

        sleep();

        assertThat(teamListener.latestTeamSnapshot).isNotNull();
        assertThat(teamListener.latestTeamSnapshot.teamId()).isEqualTo(1);
        assertThat(playerListener.latestPlayerUpdates).isNotNull();
        // player listeners DO receive starchip updates
        assertThat(playerListener.latestPlayerUpdates).hasSize(2);
        assertThat(playerListener.latestPlayerUpdates.get(1).getValue()).isEqualTo(123);
    }

    private static final class TestTeamUpdateListener implements TeamUpdateListener {
        private TeamPageSnapshot latestTeamSnapshot;

        @Override
        public void onTeamUpdate(TeamPageSnapshot snapshot) {
            latestTeamSnapshot = snapshot;
        }
    }

    private static final class TestPlayerUpdateListener implements PlayerUpdateListener {
        private List<PlayerUpdate> latestPlayerUpdates;

        @Override
        public void onPlayerUpdate(List<PlayerUpdate> updates) {
            latestPlayerUpdates = updates;
        }
    }

    private EmuMessage createEmuMessage(MessageType type, int value, int lastRng, int nowRng) {
        EmuMessage message = new EmuMessage();
        message.setType(type);
        message.setValue(value);
        message.setLastRng(lastRng);
        message.setNowRng(nowRng);
        return message;
    }

    private PlayerUpdate createPlayerUpdate(User user, MessageType messageType, int value, Instant time) {
        PlayerUpdate update = new PlayerUpdate();
        update.setParticipantId(user.getDatabaseId());
        update.setSource(messageType);
        update.setValue(value);
        update.setTime(time);
        update.setLastRng(0);
        update.setNowRng(1);
        return update;
    }

    private static void sleep() {
        try {
                Thread.sleep(500);
        }
        catch(Exception ex) {
                throw new RuntimeException(ex);
        }
    }
}
