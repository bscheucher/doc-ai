package com.learning.docai.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import lombok.RequiredArgsConstructor;

/**
 * The resource server of SPEC §7: JWTs from Azure Entra ID, checked for issuer, audience and
 * the app role `DocAi.Process`. Active in every profile except a `local` that is not `prod` -
 * the complement of {@link LocalSecurityConfig}, so exactly one of the two chains applies and
 * `local` can never switch authentication off in production.
 */
@Configuration
@Profile("!local | prod")
@RequiredArgsConstructor
public class SecurityConfig {

    private final SecurityProperties properties;

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        String rolle = pflichtwert(properties.requiredRole(), "docai.security.required-role");

        return http
                .authorizeHttpRequests(requests -> requests
                        // Liveness and readiness are polled by the platform, which has no token.
                        // Asked for by endpoint rather than by path, so a moved actuator base
                        // path cannot silently turn the probes into 401s.
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers("/api/**").hasAuthority(rolle)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rollenKonverter())))
                // A token per call, nothing to remember between them.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No browser session and no cookies, so there is nothing for CSRF to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * Resolved on the first token rather than at startup: the service must come up even when
     * Entra ID is briefly unreachable, and a health probe has to be answerable while it is.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        String issuer = pflichtwert(properties.issuerUri(), "docai.security.issuer-uri");
        String audience = pflichtwert(properties.audience(), "docai.security.audience");

        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer),
                    new AudienceValidator(audience)));
            return decoder;
        });
    }

    private static JwtAuthenticationConverter rollenKonverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new AppRoleAuthoritiesConverter());
        return converter;
    }

    /**
     * A secured deployment without an issuer, an audience or a role would accept nothing
     * anyway; saying so at startup beats a 401 or a 403 that nobody can explain. The role is
     * checked too although it has a default: application.yml sets it, so the default no longer
     * applies and an empty override would bind as "" and match no token.
     */
    private static String pflichtwert(String wert, String property) {
        if (wert == null || wert.isBlank()) {
            throw new IllegalStateException(property
                    + " must be set when the API is secured (SPEC §7); "
                    + "the 'local' profile is the only way to run without it");
        }
        return wert;
    }
}
