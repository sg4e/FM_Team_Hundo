package moe.maika.fmteamhundo.livestats.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LiveStatsConfigTest {
    @Test
    void usesHostedServerWhenNoArgsAreProvided() {
        LiveStatsConfig config = LiveStatsConfig.fromArgs(new String[0]);

        assertEquals("https://hundo.maika.moe", config.baseUri().toString());
        assertEquals("https://hundo.maika.moe/api/teams", config.restUri("/api/teams").toString());
        assertEquals("wss://hundo.maika.moe/firehose/player", config.websocketUri("/firehose/player").toString());
    }

    @Test
    void usesFirstArgAsServerUrl() {
        LiveStatsConfig config = LiveStatsConfig.fromArgs(new String[] { "https://example.com:8443/" });

        assertEquals("https://example.com:8443", config.baseUri().toString());
        assertEquals("https://example.com:8443/api/players", config.restUri("/api/players").toString());
        assertEquals("wss://example.com:8443/firehose/team", config.websocketUri("/firehose/team").toString());
    }

    @Test
    void rejectsInvalidUrls() {
        assertThrows(IllegalArgumentException.class, () -> LiveStatsConfig.fromArgs(new String[] { "localhost:8080" }));
    }
}
