package moe.maika.fmteamhundo.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import moe.maika.fmteamhundo.api.CardAcquisition;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideoStatus;
import moe.maika.fmteamhundo.data.repos.AcquisitionVideoRepository;

@Slf4j
@Service
public class AcquisitionVideoService {

    private final AcquisitionVideoRepository repository;
    private final Set<AcquisitionVideoListener> listeners = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<AcquisitionVideoKey, AcquisitionVideo> resolvedVideoCache = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public AcquisitionVideoService(AcquisitionVideoRepository repository) {
        this.repository = repository;
    }

    public synchronized AcquisitionVideo recordAcquisition(int teamId, CardAcquisition acquisition) {
        Instant now = Instant.now();
        Optional<AcquisitionVideo> existing = repository.findByTeamIdAndCardId(teamId, acquisition.cardId());
        if(existing.isEmpty()) {
            return repository.save(new AcquisitionVideo(teamId, acquisition, now));
        }

        AcquisitionVideo row = existing.get();
        if(!acquisition.acquisitionTime().isBefore(row.getAcquisitionTime())) {
            return row;
        }

        if(row.isResolved()) {
            log.warn(
                    "Replay found earlier acquisition for resolved VoD row team={} card={} existingTime={} earlierTime={}; keeping resolved link {}",
                    teamId, acquisition.cardId(), row.getAcquisitionTime(), acquisition.acquisitionTime(), row.getTwitchUrl());
            return row;
        }

        row.applyAcquisition(acquisition);
        row.requeue(now);
        return repository.save(row);
    }

    public List<AcquisitionVideo> findDueQueuedRows(Instant now, int batchSize) {
        return repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                AcquisitionVideoStatus.QUEUED, now, PageRequest.of(0, batchSize));
    }

    public Optional<AcquisitionVideo> getResolvedVideo(int teamId, int cardId) {
        return Optional.ofNullable(resolvedVideoCache.get(new AcquisitionVideoKey(teamId, cardId)));
    }

    public List<AcquisitionVideo> getResolvedVideos(int teamId, Collection<Integer> cardIds) {
        if(cardIds.isEmpty()) {
            return List.of();
        }
        return cardIds.stream()
                .map(cardId -> resolvedVideoCache.get(new AcquisitionVideoKey(teamId, cardId)))
                .filter(video -> video != null)
                .toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadResolvedVideoCacheOnStartup() {
        refreshResolvedVideoCache();
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void refreshResolvedVideoCache() {
        List<AcquisitionVideo> resolvedVideos = repository.findByStatus(AcquisitionVideoStatus.RESOLVED).stream()
                .filter(AcquisitionVideoService::hasResolvedUrl)
                .toList();
        Map<AcquisitionVideoKey, AcquisitionVideo> refreshedCache = new HashMap<>();
        for(AcquisitionVideo video : resolvedVideos) {
            refreshedCache.put(AcquisitionVideoKey.from(video), video);
        }
        resolvedVideoCache.clear();
        resolvedVideoCache.putAll(refreshedCache);
    }

    public synchronized AcquisitionVideo markResolved(AcquisitionVideo row, TwitchVodMatch match, Instant now) {
        AcquisitionVideo current = repository.findById(row.getDatabaseId()).orElse(row);
        current.setStatus(AcquisitionVideoStatus.RESOLVED);
        current.setLastAttemptAt(now);
        current.setAttemptCount(current.getAttemptCount() + 1);
        current.setTwitchChannelId(match.channelId());
        current.setTwitchChannelLogin(match.channelLogin());
        current.setTwitchStreamId(match.streamId());
        current.setStreamStartedAt(match.streamStartedAt());
        current.setTwitchVideoId(match.videoId());
        current.setOffsetSeconds(match.offsetSeconds());
        current.setResolvedAt(now);
        current.setNextAttemptAt(null);
        current.setLastError(null);
        AcquisitionVideo saved = repository.save(current);
        putResolvedVideoInCache(saved);
        notifyResolved(saved);
        return saved;
    }

    public synchronized AcquisitionVideo markAttemptFailed(AcquisitionVideo row, String error, Instant now,
            TwitchVodProperties properties) {
        AcquisitionVideo current = repository.findById(row.getDatabaseId()).orElse(row);
        current.setLastAttemptAt(now);
        current.setAttemptCount(current.getAttemptCount() + 1);
        current.setLastError(truncate(error));
        Instant firstQueuedAt = current.getFirstQueuedAt() != null ? current.getFirstQueuedAt() : now;
        if(!now.isBefore(firstQueuedAt.plus(properties.getRetryGracePeriod()))) {
            current.setStatus(AcquisitionVideoStatus.NOT_FOUND);
            current.setNextAttemptAt(null);
        }
        else {
            current.setStatus(AcquisitionVideoStatus.QUEUED);
            current.setNextAttemptAt(now.plus(properties.getRetryCadence()));
        }
        return repository.save(current);
    }

    public void addListener(AcquisitionVideoListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AcquisitionVideoListener listener) {
        listeners.remove(listener);
    }

    private void notifyResolved(AcquisitionVideo acquisitionVideo) {
        for(AcquisitionVideoListener listener : listeners) {
            executorService.submit(() -> {
                try {
                    listener.onAcquisitionVideoResolved(acquisitionVideo);
                }
                catch(Exception ex) {
                    log.error("Error notifying acquisition video listener for team {} card {}: {}",
                            acquisitionVideo.getTeamId(), acquisitionVideo.getCardId(), ex.getMessage(), ex);
                }
            });
        }
    }

    private static String truncate(String value) {
        if(value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private static boolean hasResolvedUrl(AcquisitionVideo video) {
        return video.getTwitchVideoId() != null && video.getOffsetSeconds() != null;
    }

    private void putResolvedVideoInCache(AcquisitionVideo video) {
        if(hasResolvedUrl(video)) {
            resolvedVideoCache.put(AcquisitionVideoKey.from(video), video);
        }
    }

    private record AcquisitionVideoKey(int teamId, int cardId) {
        static AcquisitionVideoKey from(AcquisitionVideo video) {
            return new AcquisitionVideoKey(video.getTeamId(), video.getCardId());
        }
    }
}
