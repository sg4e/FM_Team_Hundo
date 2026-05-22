package moe.maika.fmteamhundo.service;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;
import moe.maika.ygofm.gamedata.FMDB;

@Service
public class ManualPlayerUpdateService {

    private static final int MAX_CARD_ID = 722;
    private static final int MAX_STARCHIPS_EXCLUSIVE = 1_000_000;

    private final UserRepository userRepository;
    private final PlayerUpdateRepository playerUpdateRepository;
    private final GameStateService gameStateService;
    private final Set<Integer> unobtainableCards;
    private final FMDB fmdb;

    @Autowired
    public ManualPlayerUpdateService(UserRepository userRepository, PlayerUpdateRepository playerUpdateRepository,
            GameStateService gameStateService, HundoConstants hundoConstants) {
        this.userRepository = userRepository;
        this.playerUpdateRepository = playerUpdateRepository;
        this.gameStateService = gameStateService;
        this.unobtainableCards = hundoConstants.getUnobtainableCards();
        this.fmdb = FMDB.getInstance();
    }

    public PlayerUpdate createManualUpdate(ManualPlayerUpdateRequest request) {
        Instant now = Instant.now();
        User user = validateManualUpdate(request, now);

        PlayerUpdate update = new PlayerUpdate();
        update.setParticipantId(user.getDatabaseId());
        update.setSource(request.source());
        update.setValue(request.value());
        update.setTime(request.time() == null ? now : request.time());
        update.setLastRng(0);
        update.setNowRng(0);
        update.setOpponentId(request.source() == MessageType.STARCHIPS ? 0 : request.opponentId());

        PlayerUpdate saved = playerUpdateRepository.save(update);
        gameStateService.update(Set.of(saved));
        return saved;
    }

    public void validateManualUpdate(ManualPlayerUpdateRequest request) {
        validateManualUpdate(request, Instant.now());
    }

    public String describeValue(ManualPlayerUpdateRequest request) {
        if(request.source() == MessageType.STARCHIPS) {
            return Integer.toString(request.value());
        }
        return request.value() + " - " + fmdb.getCard(request.value());
    }

    private User validateManualUpdate(ManualPlayerUpdateRequest request, Instant now) {
        if(request == null) {
            throw new IllegalArgumentException("Manual update request is required");
        }
        if(request.source() == null) {
            throw new IllegalArgumentException("Source is required");
        }
        if(request.time() != null && request.time().isAfter(now)) {
            throw new IllegalArgumentException("Retroactive time cannot be in the future");
        }

        User user = userRepository.getByDatabaseId(request.playerId())
            .orElseThrow(() -> new IllegalArgumentException("Player is required"));
        if(user.getTeamId() == 0) {
            throw new IllegalArgumentException("Player must be assigned to a team");
        }

        if(request.source() == MessageType.STARCHIPS) {
            validateStarchips(request.value());
        }
        else {
            validateCardUpdate(request);
        }
        return user;
    }

    private void validateCardUpdate(ManualPlayerUpdateRequest request) {
        if(request.value() <= 0 || request.value() > MAX_CARD_ID) {
            throw new IllegalArgumentException("Invalid card id");
        }
        if(unobtainableCards.contains(request.value())) {
            throw new IllegalArgumentException("Update contains unobtainable cards");
        }
        if(request.opponentId() == null) {
            throw new IllegalArgumentException("Opponent is required for card updates");
        }

        Set<Integer> validOpponentIds = fmdb.getAllDuelists().stream()
            .map(duelist -> duelist.getId())
            .collect(Collectors.toSet());
        if(!validOpponentIds.contains(request.opponentId())) {
            throw new IllegalArgumentException("Invalid opponent");
        }
    }

    private void validateStarchips(int value) {
        if(value >= MAX_STARCHIPS_EXCLUSIVE) {
            throw new IllegalArgumentException("Total starchips cannot be equal to or exceed 1000000");
        }
    }

    public record ManualPlayerUpdateRequest(long playerId, MessageType source, int value, Integer opponentId, Instant time) {
    }
}
