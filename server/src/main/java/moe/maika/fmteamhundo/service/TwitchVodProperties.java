package moe.maika.fmteamhundo.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import lombok.Getter;

@ConfigurationProperties(prefix = "hundo.twitch-vod")
@Getter
public class TwitchVodProperties {

    private final boolean enabled;
    private final Duration retryCadence;
    private final Duration retryGracePeriod;
    private final Duration matchTolerance;
    private final int batchSize;

    @ConstructorBinding
    public TwitchVodProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("5m") Duration retryCadence,
            @DefaultValue("24h") Duration retryGracePeriod,
            @DefaultValue("2m") Duration matchTolerance,
            @DefaultValue("50") int batchSize) {
        this.enabled = enabled;
        this.retryCadence = retryCadence;
        this.retryGracePeriod = retryGracePeriod;
        this.matchTolerance = matchTolerance;
        this.batchSize = batchSize;
    }
}
