package moe.maika.fmteamhundo.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.Getter;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.service.ApiKeyService;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {

    private final ApiKeyService apiKeyService;

    @Autowired
    public ApiController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping("/validate")
    public Map<String, String> validateCredential(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        Validation validation = new Validation(apiKeyService, apiKey);
        HashMap<String, String> response = validation.getResponse();
        validation.getUser().ifPresent(user -> response.put("message", user.getTwitchInfo().getDisplayName()));
        return response;
    }

    @PostMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> update(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody List<EmuMessage> emuMessages) {
        Validation validation = new Validation(apiKeyService, apiKey);
        HashMap<String, String> response = validation.getResponse();
        if(validation.isValid()) {
            User user = validation.getUser().get();
            System.out.println(emuMessages);
            // Perform update logic here
        }
        return response;
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
