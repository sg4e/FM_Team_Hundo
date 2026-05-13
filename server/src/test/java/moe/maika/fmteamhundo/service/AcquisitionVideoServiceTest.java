package moe.maika.fmteamhundo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import moe.maika.fmteamhundo.api.CardAcquisition;
import moe.maika.fmteamhundo.api.MessageType;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideoStatus;
import moe.maika.fmteamhundo.data.repos.AcquisitionVideoRepository;

class AcquisitionVideoServiceTest {

    @Test
    void replacesUnresolvedRowsWhenReplayFindsEarlierAcquisition() {
        AcquisitionVideoRepository repository = mock(AcquisitionVideoRepository.class);
        AcquisitionVideo existing = unresolved(Instant.parse("2026-05-10T12:10:00Z"));
        when(repository.findByTeamIdAndCardId(1, 122)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AcquisitionVideoService service = new AcquisitionVideoService(repository);
        AcquisitionVideo saved = service.recordAcquisition(1, acquisition(Instant.parse("2026-05-10T12:05:00Z")));

        assertThat(saved.getAcquisitionTime()).isEqualTo(Instant.parse("2026-05-10T12:05:00Z"));
        assertThat(saved.getStatus()).isEqualTo(AcquisitionVideoStatus.QUEUED);
        assertThat(saved.getTwitchVideoId()).isNull();
        verify(repository).save(existing);
    }

    @Test
    void doesNotReplaceResolvedRowsWhenReplayFindsEarlierAcquisition() {
        AcquisitionVideoRepository repository = mock(AcquisitionVideoRepository.class);
        AcquisitionVideo existing = unresolved(Instant.parse("2026-05-10T12:10:00Z"));
        existing.setStatus(AcquisitionVideoStatus.RESOLVED);
        existing.setTwitchVideoId("vod-1");
        existing.setOffsetSeconds(600L);
        when(repository.findByTeamIdAndCardId(1, 122)).thenReturn(Optional.of(existing));

        AcquisitionVideoService service = new AcquisitionVideoService(repository);
        AcquisitionVideo result = service.recordAcquisition(1, acquisition(Instant.parse("2026-05-10T12:05:00Z")));

        assertThat(result.getAcquisitionTime()).isEqualTo(Instant.parse("2026-05-10T12:10:00Z"));
        assertThat(result.getTwitchVideoId()).isEqualTo("vod-1");
        verify(repository, never()).save(any());
    }

    @Test
    void getResolvedVideoReadsFromCacheWithoutRepositoryCalls() {
        AcquisitionVideoRepository repository = mock(AcquisitionVideoRepository.class);
        AcquisitionVideoService service = new AcquisitionVideoService(repository);

        assertThat(service.getResolvedVideo(1, 122)).isEmpty();

        verifyNoMoreInteractions(repository);
    }

    @Test
    void refreshResolvedVideoCacheLoadsResolvedRows() {
        AcquisitionVideoRepository repository = mock(AcquisitionVideoRepository.class);
        AcquisitionVideo resolved = resolved(1, 122, "vod-1", 563L);
        AcquisitionVideo incomplete = resolved(1, 456, null, 10L);
        when(repository.findByStatus(AcquisitionVideoStatus.RESOLVED)).thenReturn(List.of(resolved, incomplete));

        AcquisitionVideoService service = new AcquisitionVideoService(repository);
        service.refreshResolvedVideoCache();

        assertThat(service.getResolvedVideo(1, 122)).containsSame(resolved);
        assertThat(service.getResolvedVideo(1, 456)).isEmpty();
    }

    @Test
    void markResolvedUpdatesCacheImmediately() {
        AcquisitionVideoRepository repository = mock(AcquisitionVideoRepository.class);
        AcquisitionVideo row = unresolved(Instant.parse("2026-05-10T12:09:23Z"));
        row.setDatabaseId(123L);
        when(repository.findById(123L)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AcquisitionVideoService service = new AcquisitionVideoService(repository);
        TwitchVodMatch match = new TwitchVodMatch("channel-1", "mai", "stream-1", Instant.parse("2026-05-10T12:00:00Z"),
                "2770883762", 563L);
        AcquisitionVideo saved = service.markResolved(row, match, Instant.parse("2026-05-10T12:10:00Z"));

        assertThat(service.getResolvedVideo(1, 122)).containsSame(saved);
    }

    private static AcquisitionVideo unresolved(Instant acquiredAt) {
        AcquisitionVideo row = new AcquisitionVideo();
        row.setTeamId(1);
        row.setCardId(122);
        row.setPlayerId(10L);
        row.setAcquisitionTime(acquiredAt);
        row.setSource(MessageType.DROP);
        row.setOpponentId(1);
        row.setStatus(AcquisitionVideoStatus.QUEUED);
        return row;
    }

    private static AcquisitionVideo resolved(int teamId, int cardId, String videoId, Long offsetSeconds) {
        AcquisitionVideo row = unresolved(Instant.parse("2026-05-10T12:09:23Z"));
        row.setTeamId(teamId);
        row.setCardId(cardId);
        row.setStatus(AcquisitionVideoStatus.RESOLVED);
        row.setTwitchVideoId(videoId);
        row.setOffsetSeconds(offsetSeconds);
        return row;
    }

    private static CardAcquisition acquisition(Instant acquiredAt) {
        return new CardAcquisition(122, acquiredAt, MessageType.FUSE, 11L, 2);
    }
}
