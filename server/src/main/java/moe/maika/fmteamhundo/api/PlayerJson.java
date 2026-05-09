package moe.maika.fmteamhundo.api;

import moe.maika.fmteamhundo.data.entities.User;

public record PlayerJson(
    long id,
    String twitchId,
    String name,
    String altAccount,
    int teamId
) {
    public static PlayerJson fromUser(User user) {
        return new PlayerJson(
            user.getDatabaseId(),
            user.getTwitchId(),
            user.getName(),
            user.getAltAccount(),
            user.getTeamId()
        );
    }
}
