package moe.maika.fmteamhundo.data.repos;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import moe.maika.fmteamhundo.data.entities.Team;

public interface TeamRepository extends JpaRepository<Team, Integer> {
    Optional<Team> findByNameIgnoreCase(String name);
}
