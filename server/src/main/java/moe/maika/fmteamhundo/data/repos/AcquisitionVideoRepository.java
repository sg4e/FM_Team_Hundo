package moe.maika.fmteamhundo.data.repos;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import moe.maika.fmteamhundo.data.entities.AcquisitionVideo;
import moe.maika.fmteamhundo.data.entities.AcquisitionVideoStatus;

public interface AcquisitionVideoRepository extends JpaRepository<AcquisitionVideo, Long> {

    Optional<AcquisitionVideo> findByTeamIdAndCardId(int teamId, int cardId);

    Optional<AcquisitionVideo> findByTeamIdAndCardIdAndStatus(int teamId, int cardId, AcquisitionVideoStatus status);

    List<AcquisitionVideo> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            AcquisitionVideoStatus status, Instant now, Pageable pageable);

    List<AcquisitionVideo> findByTeamIdAndCardIdInAndStatus(
            int teamId, Collection<Integer> cardIds, AcquisitionVideoStatus status);
}
