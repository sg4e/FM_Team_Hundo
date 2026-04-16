package moe.maika.fmteamhundo.data.repos;

import org.springframework.data.jpa.repository.JpaRepository;

import moe.maika.fmteamhundo.data.entities.User;

/**
 *
 * @author sg4e
 */
public interface UserRepository extends JpaRepository<User,String> {
    
}

