package moe.maika.fmteamhundo.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.User;
import moe.maika.fmteamhundo.data.repos.UserRepository;

@Slf4j
@Service
public class TwitchVodResolver {

    private static final int VIDEO_LOOKUP_LIMIT = 20;

    private final TwitchVodProperties properties;
    private final TwitchHelixClient twitchClient;
    private final UserRepository userRepository;
    private final AcquisitionVideoService acquisitionVideoService;
    private final TwitchAccountService twitchAccountService;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public TwitchVodResolver(TwitchVodProperties properties, TwitchHelixClient twitchClient, UserRepository userRepository,
            AcquisitionVideoService acquisitionVideoService, TwitchAccountService twitchAccountService) {
        this.properties = properties;
        this.twitchClient = twitchClient;
        this.userRepository = userRepository;
        this.acquisitionVideoService = acquisitionVideoService;
        this.twitchAccountService = twitchAccountService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if(!properties.isEnabled()) {
            return;
        }
        twitchAccountService.backfillAltTwitchIds();
        resolveDueRows();
    }

    @Scheduled(fixedDelayString = "${hundo.twitch-vod.retry-cadence:5m}")
    public void scheduledResolveDueRows() {
        if(properties.isEnabled()) {
            resolveDueRows();
        }
    }

    public void resolveDueRows() {
        Instant now = Instant.now();
        List<AcquisitionVideo> dueRows = acquisitionVideoService.findDueQueuedRows(now, properties.getBatchSize());
        for(AcquisitionVideo row : dueRows) {
            resolve(row, now);
        }
    }

    public void submit(AcquisitionVideo row) {
        if(!properties.isEnabled()) {
            return;
        }
        executorService.submit(() -> resolve(row, Instant.now()));
    }

    public void resolve(AcquisitionVideo row, Instant now) {
        try {
            Optional<User> user = userRepository.getByDatabaseId(row.getPlayerId());
            if(user.isEmpty()) {
                acquisitionVideoService.markAttemptFailed(row, "Player not found", now, properties);
                return;
            }

            ResolutionAttempt attempt = resolveForUser(row, user.get(), now);
            if(attempt.match().isPresent()) {
                acquisitionVideoService.markResolved(row, attempt.match().get(), now);
            }
            else {
                acquisitionVideoService.markAttemptFailed(row, attempt.message(), now, properties);
            }
        }
        catch(RuntimeException ex) {
            log.warn("Unable to resolve Twitch VoD for team {} card {}: {}", row.getTeamId(), row.getCardId(), ex.getMessage());
            acquisitionVideoService.markAttemptFailed(row, ex.getMessage(), now, properties);
        }
    }

    ResolutionAttempt resolveForUser(AcquisitionVideo row, User user, Instant now) {
        List<ChannelCandidate> candidates = channelCandidates(user);
        if(candidates.isEmpty()) {
            return ResolutionAttempt.notFound("No Twitch channel candidates");
        }

        String lastMessage = "No matching stream or archive video found";
        for(ChannelCandidate candidate : candidates) {
            ResolutionAttempt attempt = resolveOnChannel(row, candidate, now);
            if(attempt.match().isPresent()) {
                return attempt;
            }
            lastMessage = attempt.message();
        }
        return ResolutionAttempt.notFound(lastMessage);
    }

    private List<ChannelCandidate> channelCandidates(User user) {
        List<ChannelCandidate> candidates = new ArrayList<>();
        if(user.getAltTwitchId() != null && !user.getAltTwitchId().isBlank()) {
            candidates.add(new ChannelCandidate(user.getAltTwitchId(), user.getAltAccount()));
        }
        else if(user.getAltAccount() != null && !user.getAltAccount().isBlank() && properties.isEnabled()) {
            try {
                twitchClient.getUserByLogin(user.getAltAccount()).ifPresent(twitchUser -> {
                    user.setAltAccount(twitchUser.login());
                    user.setAltTwitchId(twitchUser.id());
                    userRepository.save(user);
                    candidates.add(new ChannelCandidate(twitchUser.id(), twitchUser.login()));
                });
            }
            catch(RuntimeException ex) {
                log.warn("Unable to resolve alt Twitch account {} for user {}: {}", user.getAltAccount(), user.getDatabaseId(),
                        ex.getMessage());
            }
        }
        if(user.getTwitchId() != null && !user.getTwitchId().isBlank()) {
            candidates.add(new ChannelCandidate(user.getTwitchId(), user.getName()));
        }
        return candidates.stream().filter(distinctByChannelId()).toList();
    }

    private java.util.function.Predicate<ChannelCandidate> distinctByChannelId() {
        List<String> seen = new ArrayList<>();
        return candidate -> {
            if(seen.contains(candidate.channelId())) {
                return false;
            }
            seen.add(candidate.channelId());
            return true;
        };
    }

    private ResolutionAttempt resolveOnChannel(AcquisitionVideo row, ChannelCandidate channel, Instant now) {
        Instant acquisitionTime = row.getAcquisitionTime();
        Optional<TwitchStream> liveStream = twitchClient.getLiveStreamByUserId(channel.channelId())
                .filter(stream -> coversLiveStream(stream, acquisitionTime, now, properties.getMatchTolerance()));
        List<TwitchVideo> videos = twitchClient.getArchiveVideosByUserId(channel.channelId(), VIDEO_LOOKUP_LIMIT);

        if(liveStream.isPresent()) {
            TwitchStream stream = liveStream.get();
            Optional<TwitchVideo> video = videos.stream()
                    .filter(candidate -> Objects.equals(candidate.streamId(), stream.id()))
                    .findFirst();
            if(video.isPresent()) {
                return ResolutionAttempt.matched(toMatch(channel, stream.id(), stream.startedAt(), video.get().id(), acquisitionTime));
            }
            return ResolutionAttempt.notFound("Live stream matched, but archive VoD is not available yet");
        }

        return videos.stream()
                .filter(video -> coversArchiveVideo(video, acquisitionTime, properties.getMatchTolerance()))
                .findFirst()
                .map(video -> ResolutionAttempt.matched(toMatch(channel, video.streamId(), video.createdAt(), video.id(), acquisitionTime)))
                .orElseGet(() -> ResolutionAttempt.notFound("No archive VoD covers acquisition time"));
    }

    private static TwitchVodMatch toMatch(ChannelCandidate channel, String streamId, Instant streamStartedAt, String videoId,
            Instant acquisitionTime) {
        long offsetSeconds = Math.max(0, Duration.between(streamStartedAt, acquisitionTime).getSeconds());
        return new TwitchVodMatch(channel.channelId(), channel.channelLogin(), streamId, streamStartedAt, videoId, offsetSeconds);
    }

    static boolean coversLiveStream(TwitchStream stream, Instant acquisitionTime, Instant now, Duration tolerance) {
        return !acquisitionTime.isBefore(stream.startedAt().minus(tolerance))
                && !acquisitionTime.isAfter(now.plus(tolerance));
    }

    static boolean coversArchiveVideo(TwitchVideo video, Instant acquisitionTime, Duration tolerance) {
        Duration duration = parseTwitchDuration(video.duration());
        Instant startedAt = video.createdAt();
        Instant endedAt = startedAt.plus(duration);
        return !acquisitionTime.isBefore(startedAt.minus(tolerance))
                && !acquisitionTime.isAfter(endedAt.plus(tolerance));
    }

    static Duration parseTwitchDuration(String value) {
        if(value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        long totalSeconds = 0;
        StringBuilder digits = new StringBuilder();
        for(char c : value.toCharArray()) {
            if(Character.isDigit(c)) {
                digits.append(c);
                continue;
            }
            if(digits.isEmpty()) {
                continue;
            }
            long amount = Long.parseLong(digits.toString());
            digits.setLength(0);
            if(c == 'h') {
                totalSeconds += amount * 3600;
            }
            else if(c == 'm') {
                totalSeconds += amount * 60;
            }
            else if(c == 's') {
                totalSeconds += amount;
            }
        }
        return Duration.ofSeconds(totalSeconds);
    }

    record ChannelCandidate(String channelId, String channelLogin) { }
    record ResolutionAttempt(Optional<TwitchVodMatch> match, String message) {
        static ResolutionAttempt matched(TwitchVodMatch match) {
            return new ResolutionAttempt(Optional.of(match), null);
        }

        static ResolutionAttempt notFound(String message) {
            return new ResolutionAttempt(Optional.empty(), message);
        }
    }
}
