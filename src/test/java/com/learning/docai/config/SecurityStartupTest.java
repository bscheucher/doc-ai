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

    @Test
    void refusesToStartWithoutAnIssuer() {
        assertThatThrownBy(() -> start("--docai.security.issuer-uri="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docai.security.issuer-uri");
    }

    @Test
    void refusesToStartWithoutAnAudience() {
        assertThatThrownBy(() -> start("--docai.security.audience="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docai.security.audience");
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
