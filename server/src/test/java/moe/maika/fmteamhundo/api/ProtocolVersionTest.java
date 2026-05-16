package moe.maika.fmteamhundo.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProtocolVersionTest {

    @Test
    void parseValueAcceptsConfiguredVersion() {
        assertThat(ProtocolVersion.parseValue("1")).contains("1");
    }

    @Test
    void parseValueTrimsConfiguredVersion() {
        assertThat(ProtocolVersion.parseValue(" 1 \n")).contains("1");
    }

    @Test
    void parseValueRejectsBlankValue() {
        assertThat(ProtocolVersion.parseValue(" ")).isEmpty();
    }

    @Test
    void parseValueRejectsSpringStylePlaceholder() {
        assertThat(ProtocolVersion.parseValue("${FM_HUNDO_PROTOCOL_VERSION}")).isEmpty();
    }

    @Test
    void parseValueRejectsMavenStylePlaceholder() {
        assertThat(ProtocolVersion.parseValue("@env.FM_HUNDO_PROTOCOL_VERSION@")).isEmpty();
    }
}
