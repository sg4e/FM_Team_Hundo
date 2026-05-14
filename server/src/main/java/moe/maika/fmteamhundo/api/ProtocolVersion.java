package moe.maika.fmteamhundo.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

import org.springframework.stereotype.Component;

@Component
public class ProtocolVersion {

    private static final String RESOURCE_NAME = "fm-hundo-build.properties";
    private static final String PROPERTY_NAME = "protocol.version";

    private final Optional<String> value;

    public ProtocolVersion() {
        this.value = loadProtocolVersion();
    }

    public Optional<String> getValue() {
        return value;
    }

    private static Optional<String> loadProtocolVersion() {
        Properties properties = new Properties();
        try (InputStream stream = ProtocolVersion.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (stream == null) {
                return Optional.empty();
            }
            properties.load(stream);
        } catch (IOException e) {
            return Optional.empty();
        }

        String rawValue = properties.getProperty(PROPERTY_NAME, "").trim();
        if (rawValue.isEmpty() || rawValue.startsWith("${")) {
            return Optional.empty();
        }
        return Optional.of(rawValue);
    }
}
