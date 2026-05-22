package moe.maika.fmteamhundo.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import moe.maika.fmteamhundo.api.LibraryUpdate;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.Team;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.AcquisitionVideoRepository;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ManualPlayerUpdateService;
import moe.maika.fmteamhundo.service.ManualPlayerUpdateService.ManualPlayerUpdateRequest;

@SpringBootTest
@ActiveProfiles("test")
class ManualPlayerUpdateServiceIntegrationTest {

    @Autowired
    private ManualPlayerUpdateService manualPlayerUpdateService;

    @Autowired
    private GameStateService gameStateService;

    @Autowired
    private HundoConstants hundoConstants;

    @Autowired
    private UserMappings userMappings;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private PlayerUpdateRepository playerUpdateRepository;

    @Autowired
    private AcquisitionVideoRepository acquisitionVideoRepository;

    private User user;
    private Team team;

    @BeforeEach
    void setUp() {
        gameStateService.reset();
        acquisitionVideoRepository.deleteAll();
        playerUpdateRepository.deleteAll();
        userRepository.deleteAll();
        teamRepository.deleteAll();
        userMappings.clearCaches();

        team = teamRepository.save(new Team("Manual Team"));

        user = new User();
        user.setTwitchId("manual_user");
        user.setName("ManualUser");
        user.setTeamId(team.getTeamId());
        user.setOauth("oauth");
        user = userRepository.save(user);
    }

    @Test
    void savesCardUpdateAndAppliesItToGameState() {
        Instant timestamp = Instant.now().minus(Duration.ofMinutes(5));

        PlayerUpdate saved = manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(user.getDatabaseId(), MessageType.DROP, 122, 1, timestamp)
        );

        List<PlayerUpdate> updates = playerUpdateRepository.findAll();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getDatabaseId()).isEqualTo(saved.getDatabaseId());
        assertThat(saved.getParticipantId()).isEqualTo(user.getDatabaseId());
        assertThat(saved.getSource()).isEqualTo(MessageType.DROP);
        assertThat(saved.getValue()).isEqualTo(122);
        assertThat(saved.getOpponentId()).isEqualTo(1);
        assertThat(saved.getTime()).isEqualTo(timestamp);
        assertThat(saved.getLastRng()).isZero();
        assertThat(saved.getNowRng()).isZero();

        sleep();
        LibraryUpdate snapshot = gameStateService.getLatestLibraryUpdate(team.getTeamId());
        assertThat(snapshot.newAcquisitions()).anyMatch(acquisition -> acquisition.cardId() == 122);
        assertThat(gameStateService.getLatestPlayerUpdates(user.getDatabaseId()))
            .extracting(PlayerUpdate::getDatabaseId)
            .contains(saved.getDatabaseId());
    }

    @Test
    void savesStarchipsWithNoOpponentAndAppliesItToGameState() {
        PlayerUpdate saved = manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(user.getDatabaseId(), MessageType.STARCHIPS, 500, null, null)
        );

        assertThat(saved.getSource()).isEqualTo(MessageType.STARCHIPS);
        assertThat(saved.getValue()).isEqualTo(500);
        assertThat(saved.getOpponentId()).isZero();
        assertThat(saved.getLastRng()).isZero();
        assertThat(saved.getNowRng()).isZero();
        assertThat(saved.getTime()).isNotNull();

        sleep();
        assertThat(gameStateService.getLibrary(team.getTeamId()).getStarchips(user.getDatabaseId())).isEqualTo(500);
        assertThat(playerUpdateRepository.findAll()).hasSize(1);
    }

    @Test
    void rejectsInvalidManualUpdates() {
        assertThatThrownBy(() -> manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(user.getDatabaseId(), MessageType.DROP, -1, 1, null)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid card id");

        assertThatThrownBy(() -> manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(
                user.getDatabaseId(),
                MessageType.DROP,
                hundoConstants.getUnobtainableCards().iterator().next(),
                1,
                null
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Update contains unobtainable cards");

        assertThatThrownBy(() -> manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(user.getDatabaseId(), MessageType.STARCHIPS, 1_000_000, null, null)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Total starchips cannot be equal to or exceed 1000000");

        assertThatThrownBy(() -> manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(user.getDatabaseId(), MessageType.DROP, 122, null, null)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Opponent is required for card updates");

        assertThatThrownBy(() -> manualPlayerUpdateService.createManualUpdate(
            new ManualPlayerUpdateRequest(
                user.getDatabaseId(),
                MessageType.DROP,
                122,
                1,
                Instant.now().plus(Duration.ofMinutes(1))
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Retroactive time cannot be in the future");

        assertThat(playerUpdateRepository.findAll()).isEmpty();
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }
}
