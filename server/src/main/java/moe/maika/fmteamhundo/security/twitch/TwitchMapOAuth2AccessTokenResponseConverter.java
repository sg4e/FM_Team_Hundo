
package moe.maika.fmteamhundo.security.twitch;

/**
 * This code is taken from:
 * https://github.com/intricate/twitch-oauth2-client-spring
 * 
 * Credit to Github user "intricate".
 * 
 * The code in this file is licensed under the Apache-2.0 license.
 * See the full license in the directory.
 */
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * A {@link Converter} that is able to convert the provided
 * OAuth 2.0 Access Token Response parameters, either formatted according to
 * the <a href="https://tools.ietf.org/html/rfc6749">specification</a> or to
 * <a href="https://dev.twitch.tv/docs/authentication/getting-tokens-oauth">
 * Twitch's authorization server implementation</a>, to an
 * {@link OAuth2AccessTokenResponse}.
 * <p>
 * The <a href="https://tools.ietf.org/html/rfc6749#section-3.3">OAuth 2.0
 * specification</a> denotes that the scope parameter is to be expressed as "a
 * list of space-delimited, case-sensitive strings". However, in Twitch's
 * OAuth 2.0 Access Token response, the scope parameter is expressed as a JSON
 * array of strings.
 * <p>
 * This solution only works from Spring Security {@code 5.3.0.RELEASE} onward.
 * See <a href="https://github.com/spring-projects/spring-security/issues/6463">
 * spring-projects/spring-security#6463</a> for more information.
 */
public final class TwitchMapOAuth2AccessTokenResponseConverter
    implements Converter<Map<String, Object>, OAuth2AccessTokenResponse> {
    public static final String OAUTH2_SPEC_SCOPE_DELIMITER = " ";
    public static final String OAUTH2_TWITCH_SCOPE_DELIMITER = ", ";
    private static final Set<String> TOKEN_RESPONSE_PARAMETER_NAMES = new HashSet<>(Arrays.asList(
        OAuth2ParameterNames.ACCESS_TOKEN,
        OAuth2ParameterNames.EXPIRES_IN,
        OAuth2ParameterNames.REFRESH_TOKEN,
        OAuth2ParameterNames.SCOPE,
        OAuth2ParameterNames.TOKEN_TYPE
    ));

    public static Set<String> delimitedListToStringSet(
        String scope,
        String delimiter
    ) {
        return new HashSet<>(
            Arrays.asList(
                StringUtils.delimitedListToStringArray(
                    scope,
                    delimiter
                )
            )
        );
    }

    /**
     * Parses an OAuth 2.0 scope which is formatted according to the
     * <a href="https://tools.ietf.org/html/rfc6749#section-3.3">OAuth 2.0
     * specification</a>
     *
     * @param scope The scope string.
     * @return The parsed set of scopes.
     */
    public static Set<String> parseSpecOAuth2Scope(String scope) {
        return delimitedListToStringSet(scope, OAUTH2_SPEC_SCOPE_DELIMITER);
    }

    /**
     * Parses an OAuth 2.0 scope which is formatted according to
     * <a href="https://dev.twitch.tv/docs/authentication/getting-tokens-oauth">
     * Twitch's OAuth 2.0 implementation</a>.
     *
     * @param scope The scope string.
     * @return The parsed set of scopes.
     */
    public static Set<String> parseTwitchOAuth2Scope(String scope) {
        return delimitedListToStringSet(
            // Remove the '[' and ']' characters from the scope String.
            scope.substring(1, scope.length() - 1),
            OAUTH2_TWITCH_SCOPE_DELIMITER
        );
    }

    /**
     * Parses an OAuth 2.0 scope either according to the specification or to
     * Twitch's implementation.
     *
     * @param scope The scope string.
     * @return The parsed set of scopes.
     */
    public static Set<String> parseOAuth2Scope(String scope) {
        if (scope.startsWith("[") && scope.endsWith("]")) {
            return parseTwitchOAuth2Scope(scope);
        } else {
            return parseSpecOAuth2Scope(scope);
        }
    }

    private static class StringProxy {

        private final Map<String, Object> map;
        public StringProxy(Map<String, Object> tokenResponseParameters) {
            this.map = tokenResponseParameters;
        }

        public String get(String key) {
            Object value = map.get(key);
            if(value == null)
                return "null";
            else
                return value.toString();
        }

        public boolean containsKey(String key) {
            return map.containsKey(key);
        }

        public Set<Map.Entry<String, Object>> entrySet() {
            return map.entrySet();
        }
    }

    @Override
    public OAuth2AccessTokenResponse convert(Map<String, Object> tokenResponseParameters) {
        StringProxy stringParameters = new StringProxy(tokenResponseParameters);
        String accessToken = stringParameters.get(OAuth2ParameterNames.ACCESS_TOKEN);

        OAuth2AccessToken.TokenType accessTokenType = null;
        if (OAuth2AccessToken.TokenType.BEARER.getValue().equalsIgnoreCase(
            stringParameters.get(OAuth2ParameterNames.TOKEN_TYPE))) {
            accessTokenType = OAuth2AccessToken.TokenType.BEARER;
        }

        long expiresIn = 0;
        if (stringParameters.containsKey(OAuth2ParameterNames.EXPIRES_IN)) {
            try {
                expiresIn = Long.parseLong(stringParameters.get(OAuth2ParameterNames.EXPIRES_IN));
            } catch (NumberFormatException ex) {
            }
        }

        Set<String> scopes = Optional
            .ofNullable(stringParameters.get(OAuth2ParameterNames.SCOPE))
            .map(TwitchMapOAuth2AccessTokenResponseConverter::parseOAuth2Scope)
            .orElse(Collections.emptySet());

        String refreshToken = stringParameters.get(OAuth2ParameterNames.REFRESH_TOKEN);

        Map<String, Object> additionalParameters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stringParameters.entrySet()) {
            if (!TOKEN_RESPONSE_PARAMETER_NAMES.contains(entry.getKey())) {
                additionalParameters.put(entry.getKey(), entry.getValue());
            }
        }

        return OAuth2AccessTokenResponse.withToken(accessToken)
            .tokenType(accessTokenType)
            .expiresIn(expiresIn)
            .scopes(scopes)
            .refreshToken(refreshToken)
            .additionalParameters(additionalParameters)
            .build();
    }
}
