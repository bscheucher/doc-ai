package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.learning.docai.DocAiApplication;

/**
 * A secured deployment that is missing one of the three settings of SPEC §7 accepts nothing.
 * It has to say so while starting, not once a caller is looking at an unexplainable 401.
 */
class SecurityStartupTest {

    private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences";

    @Test
    void refusesToStartWithoutAnIssuer() {
        assertThatThrownBy(() -> start("--" + ISSUER + "="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ISSUER);
    }

    @Test
    void refusesToStartWithoutAnAudience() {
        // Boot's own decoder would read an empty list as "no audience check" and accept every
        // token in the tenant; §7 wants the deployment to stop instead.
        assertThatThrownBy(() -> start("--" + AUDIENCES + "="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AUDIENCES);
    }

    @Test
    void refusesToStartWithABlankRole() {
        // It has a default, but application.yml sets it, so an empty override binds as "" -
        // and hasAuthority("") would turn every call into a 403 with nothing in the log.
        assertThatThrownBy(() -> start("--docai.security.required-role="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docai.security.required-role");
    }

    /**
     * The properties moved from `docai.security` to Spring's own keys (SPEC §7). A deployment
     * still carrying the old ones would have them ignored, so the failure has to name them
     * rather than only the key nobody configured.
     */
    @Test
    void namesTheOldKeyWhenItIsStillSet() {
        assertThatThrownBy(() -> start("--" + ISSUER + "=",
                "--docai.security.issuer-uri=https://login.microsoftonline.com/alt/v2.0"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docai.security.issuer-uri")
                .hasMessageContaining("rename it to " + ISSUER);
    }

    private static void start(String... args) {
        new SpringApplicationBuilder(DocAiApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run(argsMitPort(args))
                .close();
    }

    private static String[] argsMitPort(String... args) {
        String[] alle = new String[args.length + 1];
        System.arraycopy(args, 0, alle, 0, args.length);
        alle[args.length] = "--server.port=0";
        return alle;
    }
}
