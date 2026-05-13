package moe.maika.fmteamhundo.service;

import java.time.Instant;

public record TwitchVodMatch(
    String channelId,
    String channelLogin,
    String streamId,
    Instant streamStartedAt,
    String videoId,
    long offsetSeconds
) { }
