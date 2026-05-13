package moe.maika.fmteamhundo.data.entities;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import moe.maika.fmteamhundo.data.repos.TeamRepository;
import moe.maika.fmteamhundo.data.repos.UserRepository;
import moe.maika.fmteamhundo.state.HundoConstants;

/**
 *
 * @author sg4e
 */
@Slf4j
@Entity
@Getter
@Setter
@ToString
@Table(name = "users", indexes = @Index(columnList = "twitchId", unique = true))
public class User implements OAuth2User, Serializable {
   
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long databaseId;
    private String twitchId;
    private String name;  // display name
    private String oauth;
    private boolean isAdmin = false;
    @Column(name = "api_key_hash")
    private String apiKeyHash;
    private String altAccount;
    private String altTwitchId;
    private int teamId = 0;
    //These 2 fields are taken from the OAuth2 flow and not persisted in the DB
    //If these fields ever need to be persisted in the DB, the order of setting and save
    //needs to be adjusted in getFromOAuth below
    @Transient
    private Map<String, Object> oauth2Attributes;
    @Transient
    private Collection<? extends GrantedAuthority> oauth2GrantedAuthorities;
    
    /**
     * Retrieves the user from the database, or if they don't exist, registers 
     * them.
     * 
     * @param oauth
     * @param token
     * @param userRepository
     * @return 
     */
    public static User getFromOAuth(OAuth2User oauth, String token, UserRepository userRepository,
            TeamRepository teamRepository, HundoConstants hundoConstants) {
        User u;
        final String twitchId = oauth.getAttribute("sub");
        Optional<User> opt = userRepository.findByTwitchId(twitchId);
        if(opt.isPresent())
            u = opt.get();
        else {
            log.info("Registering {}: {}", oauth.getName(), twitchId);
            u = new User();
            u.setTwitchId(twitchId);
            u.setName(oauth.getName());
            u = userRepository.save(u);
        }
        u.setOauth2Attributes(oauth.getAttributes());
        u.setOauth2GrantedAuthorities(oauth.getAuthorities());
        //update oauth
        if(!token.equals(u.getOauth())) {
            u.setOauth(token);
            userRepository.save(u);
        }
        //update display name
        if(!oauth.getName().equals(u.getName())) {
            u.setName(oauth.getName());
            userRepository.save(u);
        }
        if(hundoConstants.isTeamAutoAssign() && u.getTeamId() == 0) {
            List<Team> teams = teamRepository.findAll();
            Optional<Team> teamToAssign = teams.stream()
                    .filter(team -> !team.isNoTeam())
                    .min(Comparator.comparingLong(team -> userRepository.countByTeamId(team.getTeamId())));
            if(teamToAssign.isPresent()) {
                Team team = teamToAssign.get();
                u.setTeamId(team.getTeamId());
                userRepository.save(u);
                log.info("Auto-assigned {} ({}) to team {}", u.getName(), u.getTwitchId(), team.getName());
            }
        }
        return u;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    public void setName(String displayName) {
        this.name = displayName;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return oauth2Attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return oauth2GrantedAuthorities;
    }

    public boolean hasApiKey() {
        return apiKeyHash != null && !apiKeyHash.isBlank();
    }
    
}

