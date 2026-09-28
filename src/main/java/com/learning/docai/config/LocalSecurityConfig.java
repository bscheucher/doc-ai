package com.learning.docai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import lombok.extern.slf4j.Slf4j;

/**
 * Development only: SPEC §7 allows the `local` profile to run without authentication, so the
 * endpoints can be exercised by hand before the resource server exists. Without any
 * {@code SecurityFilterChain} Spring Boot's default chain answers every call with 401.
 *
 * <p>The profile expression is the guard from SPEC §7 that `local` must never disable auth in
 * `prod`: with both active this configuration is skipped and the secured chain applies. The
 * real chain (JWT, audience, role `DocAi.Process`) is phase 5.
 */
@Slf4j
@Configuration
@Profile("local & !prod")
public class LocalSecurityConfig {

    @Bean
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        log.warn("Profile 'local' is active: the API is reachable without authentication");

        return http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // No browser session and no cookies, so there is nothing for CSRF to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
