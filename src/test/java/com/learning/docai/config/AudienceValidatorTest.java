package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * SPEC §7: every application in the tenant is signed by the same keys, so the audience is what
 * separates a token meant for doc-ai from one meant for anything else.
 */
class AudienceValidatorTest {

    private static final String AUDIENCE = "api://doc-ai";

    private final AudienceValidator validator = new AudienceValidator(AUDIENCE);

    @Test
    void acceptsATokenIssuedForThisApi() {
        assertThat(validator.validate(token(List.of(AUDIENCE))).hasErrors()).isFalse();
    }

    @Test
    void acceptsATokenThatNamesThisApiAmongOthers() {
        assertThat(validator.validate(token(List.of("api://etwas-anderes", AUDIENCE))).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsATokenForAnotherApi() {
        OAuth2TokenValidatorResult result = validator.validate(token(List.of("api://ibosng")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode)
                .containsExactly(OAuth2ErrorCodes.INVALID_TOKEN);
    }

    @Test
    void rejectsATokenWithoutAnAudience() {
        assertThat(validator.validate(token(null)).hasErrors()).isTrue();
    }

    private static Jwt token(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("ibosng-backend");
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
