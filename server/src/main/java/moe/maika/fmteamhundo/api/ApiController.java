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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.Getter;
import moe.maika.fmteamhundo.data.entities.PlayerUpdate;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.PlayerUpdateRepository;
import moe.maika.fmteamhundo.service.ApiKeyService;
import moe.maika.fmteamhundo.state.GameStateService;
import moe.maika.fmteamhundo.state.HundoConstants;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {

    private final ApiKeyService apiKeyService;
    private final PlayerUpdateRepository playerUpdateRepository;
    private final GameStateService gameStateService;
    private final HundoConstants hundoConstants;
    private final Set<Integer> unobtainableCards;

    @Autowired
    public ApiController(ApiKeyService apiKeyService, PlayerUpdateRepository playerUpdateRepository, GameStateService gameStateService, 
            HundoConstants hundoConstants) {
        this.apiKeyService = apiKeyService;
        this.playerUpdateRepository = playerUpdateRepository;
        this.gameStateService = gameStateService;
        this.hundoConstants = hundoConstants;
        this.unobtainableCards = hundoConstants.getUnobtainableCards();
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, String>> validateCredential(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        Validation validation = new Validation(apiKeyService, apiKey);
        HashMap<String, String> response = validation.getResponse();
        validation.getUser().ifPresent(user -> response.put("message", user.getName()));
        
        if (validation.isValid()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }

    @PostMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> update(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody List<EmuMessage> emuMessages) {
        Validation validation = new Validation(apiKeyService, apiKey);
        HashMap<String, String> response = validation.getResponse();
        
        if (validation.isValid()) {
            User user = validation.getUser().get();
            response.put("message", user.getName());

            if(emuMessages.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("result", "error", "message", "No updates provided"));
            }

            // Make sure no unobtainable cards are included in the update
            if(emuMessages.stream().filter(msg -> msg.getType() != MessageType.STARCHIPS).map(EmuMessage::getValue)
                .anyMatch(cardId -> unobtainableCards.contains(cardId))) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("result", "error", "message", "Update contains unobtainable cards"));
            }

            // Check if user is on team 0 (no team)
            if (user.getTeamId() == 0) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("result", "error", "message", "User is not assigned to a team"));
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
