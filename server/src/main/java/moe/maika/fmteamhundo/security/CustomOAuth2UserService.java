package moe.maika.fmteamhundo.security;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 *
 * @author sg4e
 */
@Service
@Slf4j
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    
    private final DefaultOAuth2UserService defaultService = new DefaultOAuth2UserService();
    // @Autowired
    // private UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest r) throws OAuth2AuthenticationException {
        String token = r.getAccessToken().getTokenValue();
        OAuth2User user = defaultService.loadUser(r);
        // example OAuth2User:
        // Name: [sg4e], Granted Authorities: [[OAUTH2_USER, SCOPE_null]], 
        // User Attributes: 
        // [{aud=[CLIENT_ID], exp=1750860749, iat=1750859849, iss=https://id.twitch.tv/oauth2,
        // sub=73758417(twitch id), azp=[CLIENT_ID], preferred_username=sg4e}]

        // opportunity to create/query user from database
        //return User.getFromOAuth(user, token, userRepository);

        // Has to be a subclass of OAuth2User:
        return user;
    }
    
}
