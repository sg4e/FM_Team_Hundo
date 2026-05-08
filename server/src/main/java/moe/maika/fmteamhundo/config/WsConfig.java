package moe.maika.fmteamhundo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import moe.maika.fmteamhundo.state.FirehoseHandler;

@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private static final String PLAYER_ENDPOINT = "/firehose/player";
    private static final String TEAM_ENDPOINT = "/firehose/team";

    private final String[] allowedOriginPatterns;

    public WsConfig(@Value("${app.websocket.allowed-origin-patterns:*}") String[] allowedOriginPatterns) {
        //this.allowedOriginPatterns = allowedOriginPatterns;
        this.allowedOriginPatterns = new String[0];
    }

    @Bean
    public FirehoseHandler playerUpdateHandler() {
        return new FirehoseHandler(PLAYER_ENDPOINT);
    }

    @Bean
    public FirehoseHandler teamUpdateHandler() {
        return new FirehoseHandler(TEAM_ENDPOINT);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(playerUpdateHandler(), PLAYER_ENDPOINT).setAllowedOriginPatterns(allowedOriginPatterns);
        registry.addHandler(teamUpdateHandler(), TEAM_ENDPOINT).setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
