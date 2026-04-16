package moe.maika.fmteamhundo.data.entities;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import moe.maika.fmteamhundo.data.repos.UserRepository;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 *
 * @author sg4e
 */
@Slf4j
@Entity
@Getter
@Setter
@ToString
@Table(name = "users")
public class User implements OAuth2User, Serializable {
   
    @Id
    private String twitchId;
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "twitch_id", referencedColumnName = "id")
    private TwitchInfo twitchInfo;
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "twitch_id", referencedColumnName = "twitch_id")
    private LiveStatus liveStatus;
    private boolean isAdmin = false;
    @Column(name = "api_key_hash")
    private String apiKeyHash;
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
    public static User getFromOAuth(OAuth2User oauth, String token, UserRepository userRepository) {
        User u;
        final String twitchId = oauth.getAttribute("sub");
        Optional<User> opt = userRepository.findById(twitchId);
        if(opt.isPresent())
            u = opt.get();
        else {
            log.info("Registering {}: {}", oauth.getName(), twitchId);
            u = new User();
            u.setTwitchId(twitchId);
            u.setTwitchInfo(new TwitchInfo(twitchId));
            u.setName(oauth.getName());
            u.setLiveStatus(new LiveStatus(twitchId));
            u = userRepository.save(u);
        }
        u.setOauth2Attributes(oauth.getAttributes());
        u.setOauth2GrantedAuthorities(oauth.getAuthorities());
        //update oauth
        if(!token.equals(u.getTwitchInfo().getOauth())) {
            u.getTwitchInfo().setOauth(token);
            userRepository.save(u);
        }
        return u;
    }
    
    @Override
    public String getName() {
        return getTwitchInfo().getDisplayName();
    }
    
    public void setName(String displayName) {
        getTwitchInfo().setDisplayName(displayName);
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
    
    public String getStreamUrl() {
        return "https://twitch.tv/" + getTwitchInfo().getLogin();
    }
}

