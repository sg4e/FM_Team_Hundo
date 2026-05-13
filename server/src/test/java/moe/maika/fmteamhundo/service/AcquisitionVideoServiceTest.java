package moe.maika.fmteamhundo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

    private static CardAcquisition acquisition(Instant acquiredAt) {
        return new CardAcquisition(122, acquiredAt, MessageType.FUSE, 11L, 2);
    }
}
