package moe.maika.fmteamhundo.data.repos;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import moe.maika.fmteamhundo.data.entities.PlayerUpdate;

public interface PlayerUpdateRepository extends JpaRepository<PlayerUpdate, Long> {

    Slice<PlayerUpdate> findAllByOrderByDatabaseIdAsc(Pageable pageable);
}
