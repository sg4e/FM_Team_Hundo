package moe.maika.fmteamhundo.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.web.client.RestClient;
import moe.maika.fmteamhundo.security.twitch.TwitchMapOAuth2AccessTokenResponseConverter;

/**
 *
 */
@EnableWebSecurity 
@Configuration
public class SecurityConfiguration extends VaadinWebSecurity { 
    
    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http); 
        
        http
            .oauth2Client(oauth2 -> oauth2
                .authorizationCodeGrant(codeGrant -> codeGrant
                    .accessTokenResponseClient(this.accessTokenResponseClient())
                )
            )
            .oauth2Login(oauth2 -> {
                oauth2.tokenEndpoint(tokenEndpoint ->
                    tokenEndpoint.accessTokenResponseClient(this.accessTokenResponseClient())
                );
                oauth2.userInfoEndpoint(userInfo -> userInfo
                    .userService(customOAuth2UserService)
                );
            });
    }
    
    /**
     * Allows access to static resources, bypassing Spring security.
     */
    @Override
    public void configure(WebSecurity web) throws Exception {
        // Configure your static resources with public access here:
        // web.ignoring().requestMatchers(
        //         "/images/**"
        // );

        // Delegating the ignoring configuration for Vaadin's
        // related static resources to the super class:
        super.configure(web); 
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        RestClient restClient = RestClient.builder()
            .messageConverters(messageConverters -> {
                messageConverters.clear();
                messageConverters.add(new FormHttpMessageConverter());
                var tokenConverter = new OAuth2AccessTokenResponseHttpMessageConverter();
                tokenConverter.setAccessTokenResponseConverter(
                    new TwitchMapOAuth2AccessTokenResponseConverter()
                );
                messageConverters.add(tokenConverter);
            })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();

        RestClientAuthorizationCodeTokenResponseClient client =
            new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);

        return client;
    }
    
}