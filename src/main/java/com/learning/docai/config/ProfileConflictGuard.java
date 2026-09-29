package com.learning.docai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * SPEC §7: `local` may disable authentication, and must therefore never be active together
 * with `prod`. {@link LocalSecurityConfig} already stands down in that case, so the API would
 * stay secured - but a deployment that asks for both is configured by mistake, and the rest of
 * what `local` implies is not worth guessing at. It fails to start instead.
 */
@Configuration
@Profile("local & prod")
public class ProfileConflictGuard {

    public ProfileConflictGuard() {
        throw new IllegalStateException(
                "Profiles 'local' and 'prod' must not be active together: 'local' runs the API "
                        + "without authentication (SPEC §7)");
    }
}
