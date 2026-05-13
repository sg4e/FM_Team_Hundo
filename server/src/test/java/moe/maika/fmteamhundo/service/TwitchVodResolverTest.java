package moe.maika.fmteamhundo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;

class TwitchVodResolverTest {

    @Test
    void parsesTwitchDuration() {
        assertThat(TwitchVodResolver.parseTwitchDuration("1h2m3s")).isEqualTo(Duration.ofSeconds(3723));
        assertThat(TwitchVodResolver.parseTwitchDuration("14m9s")).isEqualTo(Duration.ofSeconds(849));
        assertThat(TwitchVodResolver.parseTwitchDuration("45s")).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void archiveMatchUsesConfiguredTolerance() {
        TwitchVideo video = new TwitchVideo("2770883762", "stream-1", "user-1", "mai", Instant.parse("2026-05-10T12:00:00Z"),
                "10m");

        assertThat(TwitchVodResolver.coversArchiveVideo(video, Instant.parse("2026-05-10T11:58:30Z"), Duration.ofMinutes(2)))
                .isTrue();
        assertThat(TwitchVodResolver.coversArchiveVideo(video, Instant.parse("2026-05-10T11:57:59Z"), Duration.ofMinutes(2)))
                .isFalse();
    }

    @Test
    void resolvesAltFirstAndFallsBackToPrimary() {
        FakeTwitchClient twitchClient = new FakeTwitchClient();
        Instant startedAt = Instant.parse("2026-05-10T12:00:00Z");
        Instant acquiredAt = Instant.parse("2026-05-10T12:09:23Z");
        twitchClient.videos.add(new TwitchVideo("primary-vod", "primary-stream", "primary-id", "mai", startedAt, "1h"));

        TwitchVodResolver resolver = resolver(twitchClient);
        User user = new User();
        user.setDatabaseId(10L);
        user.setTwitchId("primary-id");
        user.setName("Mai");
        user.setAltAccount("mai_alt");
        user.setAltTwitchId("alt-id");

        TwitchVodResolver.ResolutionAttempt attempt = resolver.resolveForUser(row(acquiredAt), user, Instant.parse("2026-05-10T12:10:00Z"));

        assertThat(attempt.match()).isPresent();
        TwitchVodMatch match = attempt.match().get();
        assertThat(match.channelId()).isEqualTo("primary-id");
        assertThat(match.videoId()).isEqualTo("primary-vod");
        assertThat(match.offsetSeconds()).isEqualTo(563);
        assertThat(twitchClient.lookedUpUserIds).containsExactly("alt-id", "primary-id");
    }

    @Test
    void resolvesLiveStreamWhenMatchingArchiveVodExists() {
        FakeTwitchClient twitchClient = new FakeTwitchClient();
        Instant startedAt = Instant.parse("2026-05-10T12:00:00Z");
        Instant acquiredAt = Instant.parse("2026-05-10T12:09:23Z");
        twitchClient.streams.add(new TwitchStream("live-stream", "alt-id", "mai_alt", startedAt));
        twitchClient.videos.add(new TwitchVideo("live-vod", "live-stream", "alt-id", "mai_alt", startedAt, "9m30s"));

        TwitchVodResolver resolver = resolver(twitchClient);
        User user = new User();
        user.setDatabaseId(10L);
        user.setTwitchId("primary-id");
        user.setName("Mai");
        user.setAltAccount("mai_alt");
        user.setAltTwitchId("alt-id");

        TwitchVodResolver.ResolutionAttempt attempt = resolver.resolveForUser(row(acquiredAt), user, Instant.parse("2026-05-10T12:10:00Z"));

        assertThat(attempt.match()).isPresent();
        assertThat(attempt.match().get().channelId()).isEqualTo("alt-id");
        assertThat(attempt.match().get().videoId()).isEqualTo("live-vod");
    }

    private static TwitchVodResolver resolver(TwitchHelixClient twitchClient) {
        TwitchVodProperties properties = new TwitchVodProperties(true, Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofMinutes(2),
                50);
        return new TwitchVodResolver(properties, twitchClient, mock(UserRepository.class), mock(AcquisitionVideoService.class),
                mock(TwitchAccountService.class));
    }

    private static AcquisitionVideo row(Instant acquiredAt) {
        AcquisitionVideo row = new AcquisitionVideo();
        row.setTeamId(1);
        row.setCardId(122);
        row.setPlayerId(10L);
        row.setAcquisitionTime(acquiredAt);
        row.setSource(MessageType.DROP);
        row.setOpponentId(1);
        return row;
    }

    private static final class FakeTwitchClient implements TwitchHelixClient {
        private final List<TwitchStream> streams = new ArrayList<>();
        private final List<TwitchVideo> videos = new ArrayList<>();
        private final List<String> lookedUpUserIds = new ArrayList<>();

        @Override
        public Optional<TwitchUser> getUserByLogin(String login) {
            return Optional.empty();
        }

        @Override
        public Optional<TwitchStream> getLiveStreamByUserId(String userId) {
            lookedUpUserIds.add(userId);
            return streams.stream().filter(stream -> stream.userId().equals(userId)).findFirst();
        }

        @Override
        public List<TwitchVideo> getArchiveVideosByUserId(String userId, int limit) {
            return videos.stream().filter(video -> video.userId().equals(userId)).limit(limit).toList();
        }
    }
}
