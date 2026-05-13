package moe.maika.fmteamhundo.service;

import java.time.Instant;

public record TwitchStream(String id, String userId, String userLogin, Instant startedAt) { }
