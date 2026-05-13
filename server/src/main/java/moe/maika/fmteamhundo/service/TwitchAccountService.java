package moe.maika.fmteamhundo.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Slf4j
@Service
public class TwitchAccountService {

    private final TwitchVodProperties properties;
    private final TwitchHelixClient twitchClient;
    private final UserRepository userRepository;

    @Autowired
    public TwitchAccountService(TwitchVodProperties properties, TwitchHelixClient twitchClient, UserRepository userRepository) {
        this.properties = properties;
        this.twitchClient = twitchClient;
        this.userRepository = userRepository;
    }

    public AltAccountSaveResult applyAltAccount(User user, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if(value.isBlank()) {
            user.setAltAccount(null);
            user.setAltTwitchId(null);
            userRepository.save(user);
            return AltAccountSaveResult.saved(null);
        }

        if(!properties.isEnabled()) {
            user.setAltAccount(value);
            user.setAltTwitchId(null);
            userRepository.save(user);
            return AltAccountSaveResult.saved(null);
        }

        try {
            return twitchClient.getUserByLogin(value)
                    .map(twitchUser -> {
                        user.setAltAccount(twitchUser.login());
                        user.setAltTwitchId(twitchUser.id());
                        userRepository.save(user);
                        return AltAccountSaveResult.saved(twitchUser.login());
                    })
                    .orElseGet(() -> AltAccountSaveResult.failed("Twitch account not found"));
        }
        catch(RuntimeException ex) {
            log.warn("Unable to validate alt Twitch account {} for user {}: {}", value, user.getDatabaseId(), ex.getMessage());
            return AltAccountSaveResult.failed("Twitch validation unavailable");
        }
    }

    public void backfillAltTwitchIds() {
        if(!properties.isEnabled()) {
            return;
        }
        List<User> users = userRepository.findAll().stream()
                .filter(user -> user.getAltAccount() != null && !user.getAltAccount().isBlank())
                .filter(user -> user.getAltTwitchId() == null || user.getAltTwitchId().isBlank())
                .toList();
        for(User user : users) {
            try {
                twitchClient.getUserByLogin(user.getAltAccount()).ifPresentOrElse(twitchUser -> {
                    user.setAltAccount(twitchUser.login());
                    user.setAltTwitchId(twitchUser.id());
                    userRepository.save(user);
                    log.info("Backfilled alt Twitch account {} ({}) for user {}", twitchUser.login(), twitchUser.id(),
                            user.getDatabaseId());
                }, () -> log.warn("Could not backfill unknown alt Twitch account {} for user {}", user.getAltAccount(),
                        user.getDatabaseId()));
            }
            catch(RuntimeException ex) {
                log.warn("Could not backfill alt Twitch account {} for user {}: {}", user.getAltAccount(), user.getDatabaseId(),
                        ex.getMessage());
            }
        }
    }

    public record AltAccountSaveResult(boolean saved, String message, String canonicalLogin) {
        static AltAccountSaveResult saved(String canonicalLogin) {
            return new AltAccountSaveResult(true, "Saved", canonicalLogin);
        }

        static AltAccountSaveResult failed(String message) {
            return new AltAccountSaveResult(false, message, null);
        }
    }
}
