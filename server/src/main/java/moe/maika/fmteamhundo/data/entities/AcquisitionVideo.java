package moe.maika.fmteamhundo.data.entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import moe.maika.fmteamhundo.api.CardAcquisition;
import moe.maika.fmteamhundo.api.MessageType;

@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(
    name = "acquisition_videos",
    uniqueConstraints = @UniqueConstraint(columnNames = { "teamId", "cardId" }),
    indexes = {
        @Index(columnList = "teamId, cardId"),
        @Index(columnList = "status, nextAttemptAt")
    }
)
public class AcquisitionVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long databaseId;

    private int teamId;
    private int cardId;
    private long playerId;
    private Instant acquisitionTime;
    @Enumerated(EnumType.STRING)
    private MessageType source;
    private int opponentId;

    @Enumerated(EnumType.STRING)
    private AcquisitionVideoStatus status = AcquisitionVideoStatus.QUEUED;
    private Instant firstQueuedAt;
    private Instant nextAttemptAt;
    private Instant lastAttemptAt;
    private int attemptCount;

    private String twitchChannelId;
    private String twitchChannelLogin;
    private String twitchStreamId;
    private Instant streamStartedAt;
    private String twitchVideoId;
    private Long offsetSeconds;
    private Instant resolvedAt;

    @Column(length = 1000)
    private String lastError;

    public AcquisitionVideo(int teamId, CardAcquisition acquisition, Instant queuedAt) {
        this.teamId = teamId;
        applyAcquisition(acquisition);
        requeue(queuedAt);
    }

    public boolean isResolved() {
        return status == AcquisitionVideoStatus.RESOLVED;
    }

    public void applyAcquisition(CardAcquisition acquisition) {
        this.cardId = acquisition.cardId();
        this.playerId = acquisition.playerId();
        this.acquisitionTime = acquisition.acquisitionTime();
        this.source = acquisition.source();
        this.opponentId = acquisition.opponentId();
    }

    public void requeue(Instant queuedAt) {
        status = AcquisitionVideoStatus.QUEUED;
        firstQueuedAt = queuedAt;
        nextAttemptAt = queuedAt;
        lastAttemptAt = null;
        attemptCount = 0;
        clearResolution();
    }

    public void clearResolution() {
        twitchChannelId = null;
        twitchChannelLogin = null;
        twitchStreamId = null;
        streamStartedAt = null;
        twitchVideoId = null;
        offsetSeconds = null;
        resolvedAt = null;
        lastError = null;
    }

    public String getTwitchUrl() {
        if(twitchVideoId == null || offsetSeconds == null) {
            return null;
        }
        long remaining = Math.max(0, offsetSeconds);
        long hours = remaining / 3600;
        remaining %= 3600;
        long minutes = remaining / 60;
        long seconds = remaining % 60;
        return String.format("https://www.twitch.tv/videos/%s?t=%dh%dm%ds", twitchVideoId, hours, minutes, seconds);
    }
}
