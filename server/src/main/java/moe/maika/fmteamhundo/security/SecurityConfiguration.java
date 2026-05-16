package moe.maika.fmteamhundo.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import moe.maika.fmteamhundo.security.twitch.TwitchMapOAuth2AccessTokenResponseConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

/**
 *
 */
@EnableWebSecurity 
@Configuration
public class SecurityConfiguration { 
    
    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/firehose/**").permitAll()
                .requestMatchers("/widgets/**").permitAll()
            )
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
        http.csrf(csrf -> csrf
            .ignoringRequestMatchers("/api/**", "/ws/**")  // Only disable for API and WebSocket paths
        );

        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> {
        });

        return http.build();
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        RestClient restClient = RestClient.builder()
            .configureMessageConverters(messageConverters -> {
                messageConverters.disableDefaults();
                messageConverters.addCustomConverter(new FormHttpMessageConverter());
                var tokenConverter = new OAuth2AccessTokenResponseHttpMessageConverter();
                tokenConverter.setAccessTokenResponseConverter(
                    new TwitchMapOAuth2AccessTokenResponseConverter()
                );
                messageConverters.addCustomConverter(tokenConverter);
            })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();

        RestClientAuthorizationCodeTokenResponseClient client =
            new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);

        return client;
    }
    
}
