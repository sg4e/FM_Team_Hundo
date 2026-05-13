package moe.maika.fmteamhundo.service;

import java.time.Instant;

public record TwitchVideo(String id, String streamId, String userId, String userLogin, Instant createdAt, String duration) { }
