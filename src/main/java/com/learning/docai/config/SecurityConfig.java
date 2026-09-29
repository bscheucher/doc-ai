package com.learning.docai.config;

import java.util.List;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
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
import lombok.extern.slf4j.Slf4j;

/**
 * The resource server of SPEC §7: JWTs from Azure Entra ID, checked for issuer, audience and
 * the app role `DocAi.Process`. Active in every profile except a `local` that is not `prod` -
 * the complement of {@link LocalSecurityConfig}, so exactly one of the two chains applies and
 * `local` can never switch authentication off in production.
 *
 * <p>Issuer and audience are read from Boot's own {@link OAuth2ResourceServerProperties}, so the
 * configuration keys are the ones SPEC §7 names. Defining the {@link JwtDecoder} below makes
 * Boot's {@code JwtDecoderConfiguration} back off (it is
 * {@code @ConditionalOnMissingBean(JwtDecoder.class)}), which is deliberate: Boot treats an
 * empty audience list as "no audience check", where §7 wants a deployment that cannot name its
 * audience to refuse to start rather than accept every token in the tenant.
 */
@Slf4j
@Configuration
@Profile("!local | prod")
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ISSUER_KEY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCES_KEY = "spring.security.oauth2.resourceserver.jwt.audiences";

    private final SecurityProperties properties;
    private final OAuth2ResourceServerProperties oauth2Properties;
    private final Environment environment;

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
     *
     * <p>The price of that is where every failure lands: whatever goes wrong here - unreachable
     * metadata, but also an issuer that does not match the one the metadata reports - reaches
     * the caller as a bare 401, and Spring Security logs the cause at DEBUG only. At the
     * default level a misconfigured issuer would 401 every call and say nothing, so the failure
     * is logged here instead. It repeats per request because the decoder is only cached once it
     * has been built, which is the right side to err on for a deployment that accepts nothing.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        String issuer = pflichtwert(oauth2Properties.getJwt().getIssuerUri(), ISSUER_KEY);
        List<String> audiences = pflichtwerte(oauth2Properties.getJwt().getAudiences());

        return new SupplierJwtDecoder(() -> {
            try {
                NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        new AudienceValidator(audiences)));
                return decoder;
            } catch (RuntimeException e) {
                log.error("Cannot build the JWT decoder for {}={}; every call will be answered "
                        + "401 until this is fixed. For Entra v2.0 the issuer is the tenant "
                        + "GUID, not the tenant domain: {}", ISSUER_KEY, issuer, e.getMessage());
                throw e;
            }
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
    private String pflichtwert(String wert, String property) {
        if (wert == null || wert.isBlank()) {
            throw new IllegalStateException(property
                    + " must be set when the API is secured (SPEC §7)"
                    + altKeyHinweis(property)
                    + "; the 'local' profile is the only way to run without it");
        }
        return wert;
    }

    private List<String> pflichtwerte(List<String> audiences) {
        if (audiences == null || audiences.stream().allMatch(a -> a == null || a.isBlank())) {
            pflichtwert(null, AUDIENCES_KEY);
        }
        return audiences.stream().filter(a -> a != null && !a.isBlank()).toList();
    }

    /**
     * An earlier version of this service read `docai.security.issuer-uri` and
     * `docai.security.audience`. A deployment still setting those would have them silently
     * ignored and see nothing but the name of a property it never configured, so the failure
     * names the old key when it finds one - the same trap the move was meant to close.
     */
    private String altKeyHinweis(String property) {
        String alt = switch (property) {
            case ISSUER_KEY -> "docai.security.issuer-uri";
            case AUDIENCES_KEY -> "docai.security.audience";
            default -> null;
        };
        return alt != null && environment.containsProperty(alt)
                ? ". Found " + alt + ", which this service no longer reads: rename it to "
                        + property
                : "";
    }
}
