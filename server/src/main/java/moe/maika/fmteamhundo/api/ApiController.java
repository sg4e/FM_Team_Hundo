package moe.maika.fmteamhundo.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.Getter;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;
import moe.maika.fmteamhundo.service.CreditsService;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {

    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PlayerUpdateRepository playerUpdateRepository;
    private final GameStateService gameStateService;
    private final CreditsService creditsService;
    private final HundoConstants hundoConstants;
    private final ProtocolVersion protocolVersion;
    private final Set<Integer> unobtainableCards;

    @Autowired
    public ApiController(ApiKeyService apiKeyService, UserRepository userRepository, TeamRepository teamRepository,
            PlayerUpdateRepository playerUpdateRepository, GameStateService gameStateService, HundoConstants hundoConstants,
            ProtocolVersion protocolVersion, CreditsService creditsService) {
        this.apiKeyService = apiKeyService;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.playerUpdateRepository = playerUpdateRepository;
        this.gameStateService = gameStateService;
        this.creditsService = creditsService;
        this.hundoConstants = hundoConstants;
        this.protocolVersion = protocolVersion;
        this.unobtainableCards = hundoConstants.getUnobtainableCards();
    }

    /**
     * Returns a list of all players on teams (i.e. teamId != 0) with their Twitch ID, display name, and team ID.
     * @return
     */
    @GetMapping("/players")
    public ResponseEntity<List<PlayerJson>> getPlayers() {
        return ResponseEntity.ok(userRepository.findAll().stream()
            .filter(user -> user.getTeamId() != 0)
            .map(PlayerJson::fromUser)
            .collect(Collectors.toList()));
    }

    @GetMapping("/teams")
    public ResponseEntity<List<TeamJson>> getTeams() {
        return ResponseEntity.ok(teamRepository.findAll().stream()
            .map(TeamJson::fromTeam)
            .collect(Collectors.toList()));
    }

    @GetMapping("/library/{teamId}")
    public ResponseEntity<LibraryUpdate> getLibrary(@PathVariable int teamId) {
        return ResponseEntity.ok(gameStateService.getLatestLibraryUpdate(teamId));
    }

    @GetMapping("/library_contents/{teamId}")
    public ResponseEntity<List<Integer>> getLibraryContents(@PathVariable int teamId) {
        return ResponseEntity.ok(gameStateService.getLibrary(teamId).getAcquiredCardIds());
    }

    @GetMapping("/credits")
    public ResponseEntity<CreditsResponse> getCredits() {
        return ResponseEntity.ok(creditsService.getCredits());
    }

    @GetMapping("/protocol_version")
    public ResponseEntity<Map<String, String>> getProtocolVersion() {
        Map<String, String> response = protocolVersion.getValue()
            .map(version -> Map.of("protocol_version", version))
            .orElseGet(Map::of);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, String>> validateCredential(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        Validation validation = new Validation(apiKeyService, apiKey);
        HashMap<String, String> response = validation.getResponse();
        validation.getUser().ifPresent(user -> response.put("message", user.getName()));
        if (validation.isValid()) {
            protocolVersion.getValue().ifPresent(version -> response.put("protocol_version", version));
        }
        
        if (validation.isValid()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }

    @PostMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "test", required = false) String testHeader,
            @RequestBody List<EmuMessage> emuMessages) {
        Validation validation = new Validation(apiKeyService, apiKey);
        HashMap<String, Object> response = new HashMap<>(validation.getResponse());
        boolean testMode = isTestMode(testHeader);
        
        if (validation.isValid()) {
            User user = validation.getUser().get();
            response.put("message", user.getName());

            if(emuMessages.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.<String, Object>of("result", "error", "message", "No updates provided"));
            }

            // Make sure no unobtainable cards are included in the update
            if(emuMessages.stream().filter(msg -> msg.type() != MessageType.STARCHIPS).map(EmuMessage::value)
                .anyMatch(cardId -> unobtainableCards.contains(cardId))) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.<String, Object>of("result", "error", "message", "Update contains unobtainable cards"));
            }

            //Make sure no card id is non-positive or greater than 722
            if(emuMessages.stream().filter(msg -> msg.type() != MessageType.STARCHIPS).map(EmuMessage::value)
                .anyMatch(cardId -> cardId <= 0 || cardId > 722)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.<String, Object>of("result", "error", "message", "Invalid card id"));
            }

            //Make sure starchips total is not at or above 1000000
            if(emuMessages.stream().filter(msg -> msg.type() == MessageType.STARCHIPS).mapToInt(EmuMessage::value)
                .anyMatch(starchips -> starchips >= 1_000_000)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.<String, Object>of("result", "error", "message", "Total starchips cannot be equal to or exceed 1000000"));
            }

            // Check if user is on team 0 (no team) unless this is an API test
            if (!testMode && user.getTeamId() == 0) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.<String, Object>of("result", "error", "message", "User is not assigned to a team"));
            }

            if (testMode) {
                response.put("test", true);
                return ResponseEntity.ok(response);
            }
            
            Instant now = Instant.now();
            List<PlayerUpdate> updates = emuMessages.stream().map(emu -> new PlayerUpdate(user, emu, now)).collect(Collectors.toList());
            playerUpdateRepository.saveAll(updates);
            gameStateService.update(updates);
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }

    private boolean isTestMode(String testHeader) {
        return testHeader != null && !testHeader.trim().equalsIgnoreCase("false");
    }

    @Getter
    private static class Validation {
        private final boolean valid;
        private final Optional<User> user;
        private final HashMap<String, String> response;

        public Validation(ApiKeyService apiKeyService, String apiKey) {
            response = new HashMap<>();
            user = apiKeyService.getUserForApiKey(apiKey);
            if(user.isPresent()) {
                valid = true;
                response.put("result", "ok");
            }
            else {
                valid = false;
                response.put("result", "error");
                response.put("message", "Invalid API key");
            }
        }
    }
}
