package moe.maika.fmteamhundo.livestats.client;

import java.net.URI;

public final class LiveStatsConfig {
    public static final String BASE_URL = "http://localhost:8080";

    private LiveStatsConfig() {
    }

    public static URI restUri(String path) {
        return URI.create(BASE_URL + path);
    }

    public static URI websocketUri(String path) {
        return URI.create(BASE_URL.replaceFirst("^http", "ws") + path);
    }
}
