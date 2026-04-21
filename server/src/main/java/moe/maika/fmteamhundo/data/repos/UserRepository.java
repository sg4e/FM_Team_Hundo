package moe.maika.fmteamhundo.data.repos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import moe.maika.fmteamhundo.data.entities.User;

/**
 *
 * @author sg4e
 */
public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByApiKeyHash(String apiKeyHash);
    Optional<User> findByTwitchId(String twitchId);
    List<User> findByTeamId(int teamId);
}

