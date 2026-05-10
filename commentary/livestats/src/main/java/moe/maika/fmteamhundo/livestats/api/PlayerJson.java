package moe.maika.fmteamhundo.livestats.api;

public record PlayerJson(
    long id,
    String twitchId,
    String name,
    String altAccount,
    int teamId
) { }
