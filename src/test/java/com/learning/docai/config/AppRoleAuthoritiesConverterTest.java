package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * SPEC §7: the app role `DocAi.Process` decides, and Entra ID delivers it in `roles`.
 */
class AppRoleAuthoritiesConverterTest {

    private final AppRoleAuthoritiesConverter converter = new AppRoleAuthoritiesConverter();

    @Test
    void takesTheAppRolesUnderTheirOwnName() {
        assertThat(converter.convert(token(Map.of("roles", List.of("DocAi.Process")))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("DocAi.Process");
    }

    @Test
    void returnsNothingWhenTheTokenCarriesNoRoles() {
        // A client-credentials token of another application reaches us without `roles`.
        assertThat(converter.convert(token(Map.of()))).isEmpty();
    }

    @Test
    void keepsScopesOfADelegatedTokenApart() {
        assertThat(converter.convert(token(Map.of("scope", "doc.read",
                "roles", List.of("DocAi.Process")))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_doc.read", "DocAi.Process");
    }

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("caller-backend");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
