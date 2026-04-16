package moe.maika.fmteamhundo.data.entities;

import java.io.Serializable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @author sg4e
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
public class TwitchInfo implements Serializable {
    @Id
    private String id;
    private String login, displayName, profileImageUrl, oauth;
    
    public TwitchInfo(String id) {
        this.id = id;
    }
    
    public void update(com.github.twitch4j.helix.domain.User user) {
        //id isn't updated because it shouldn't be changed (and would affect DB if it was)
        setLogin(user.getLogin());
        setDisplayName(user.getDisplayName());
        setProfileImageUrl(user.getProfileImageUrl());
    }

}

