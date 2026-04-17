package moe.maika.fmteamhundo.api;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        HashMap<String, String> result = new HashMap<>();

        apiKeyService.getUserForApiKey(apiKey).ifPresentOrElse(user -> {
            result.put("result", "ok");
            if (user.getTwitchInfo() != null && user.getTwitchInfo().getDisplayName() != null) {
                result.put("message", user.getTwitchInfo().getDisplayName());
            }
        }, () -> {
            result.put("result", "error");
            result.put("message", "Invalid API key");
        });

        return result;
    }
}
