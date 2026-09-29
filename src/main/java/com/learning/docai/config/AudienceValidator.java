package com.learning.docai.config;

import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token that was issued for a different API (SPEC §7). Entra ID signs every token of
 * the tenant with the same keys, so issuer and signature alone would let a token minted for
 * another application through.
 *
 * <p>Takes the list that {@code spring.security.oauth2.resourceserver.jwt.audiences} binds to:
 * a token is accepted when it names any of them, which is how Spring's own audience validator
 * reads that property. In practice this service configures exactly one.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "The token was not issued for this API", null);

    private final List<String> audiences;

    public AudienceValidator(List<String> audiences) {
        this.audiences = List.copyOf(audiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> aud = token.getAudience();
        return aud != null && aud.stream().anyMatch(audiences::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(ERROR);
    }
}
