package moe.maika.fmteamhundo.state;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import lombok.Getter;

@ConfigurationProperties(prefix = "ygo")
@Getter
public class HundoConstants {
    private final String apiUrl;
    private final int totalObtainableCards;
    private final Set<Integer> unobtainableCards;

    @ConstructorBinding
    public HundoConstants(String apiUrl, int totalObtainableCards, List<Integer> unobtainableCards) {
        this.apiUrl = apiUrl;
        this.totalObtainableCards = totalObtainableCards;
        this.unobtainableCards = Collections.unmodifiableSet(new HashSet<>(unobtainableCards));
    }
}
