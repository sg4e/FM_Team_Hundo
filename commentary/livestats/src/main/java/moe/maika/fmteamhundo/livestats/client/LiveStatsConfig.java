package moe.maika.fmteamhundo.livestats.client;

import java.net.URI;
import java.net.URL;

public class LiveStatsConfig {
    public static final String DEFAULT_BASE_URL = "https://hundo.maika.moe";

    private final URI baseUri;

    public LiveStatsConfig(URI baseUri) {
        this.baseUri = normalize(baseUri);
    }

    public static LiveStatsConfig fromArgs(String[] args) {
        if (args.length > 0) {
            return fromUrl(args[0]);
        }
        return fromUrl(DEFAULT_BASE_URL);
    }

    public static LiveStatsConfig fromUrl(String url) {
        try {
            URI uri = URI.create(url);
            URL parsedUrl = uri.toURL();
            return new LiveStatsConfig(parsedUrl.toURI());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid LiveStats server URL: " + url, ex);
        }
    }

    public URI restUri(String path) {
        return baseUri.resolve(path);
    }

    public URI websocketUri(String path) {
        String scheme = switch (baseUri.getScheme()) {
            case "http" -> "ws";
            case "https" -> "wss";
            default -> throw new IllegalArgumentException("LiveStats server URL must use http or https: " + baseUri);
        };
        return URI.create(baseUri.toString().replaceFirst("^" + baseUri.getScheme(), scheme)).resolve(path);
    }

    public URI baseUri() {
        return baseUri;
    }

    private static URI normalize(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("LiveStats server URL must include a scheme and host: " + uri);
        }
        String value = uri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }
}
