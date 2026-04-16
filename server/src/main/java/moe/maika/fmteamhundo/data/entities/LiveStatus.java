package moe.maika.fmteamhundo.data.entities;

import com.github.twitch4j.helix.domain.Stream;
import java.io.Serializable;
import java.sql.Timestamp;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author sg4e
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@Slf4j
public class LiveStatus implements Serializable {
    @Id
    private String twitchId;
    private String gameId, gameName, streamId, thumbnailUrl, title;
    private int viewerCount;
    private Timestamp startedAt;
    private boolean isLive;
    @OneToOne(mappedBy = "liveStatus")
    @ToString.Exclude
    private User user;
    
    public LiveStatus(String twitchId) {
        this.twitchId = twitchId;
        isLive = false;
    }
    
    public void update(Stream stream) {
        if(!(twitchId + "").equals(stream.getUserId())) {
            log.warn("LiveStatus was passed update data with a different Twitch userId: {} != {}", twitchId, stream.getUserId());
            return;
        }
        gameId = stream.getGameId();
        gameName = stream.getGameName();
        streamId = stream.getId();
        thumbnailUrl = stream.getThumbnailUrl();
        title = stream.getTitle();
        viewerCount = stream.getViewerCount();
        startedAt = Timestamp.from(stream.getStartedAtInstant());
        isLive = true; // Twitch API doesn't return a response at all for queries of non-live streams
    }
}

