package moe.maika.fmteamhundo.service;

import java.util.List;
import java.util.Optional;

public interface TwitchHelixClient {

    Optional<TwitchUser> getUserByLogin(String login);

    Optional<TwitchStream> getLiveStreamByUserId(String userId);

    List<TwitchVideo> getArchiveVideosByUserId(String userId, int limit);
}
