package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.learning.docai.DocAiApplication;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The two profile combinations of SPEC §7: `local` must never run in `prod`, and the hosted
 * provider in `prod` has to announce itself.
 */
class ProfileGuardTest {

    private final List<Logger> mitgeschnitten = new ArrayList<>();

    /** Logback loggers are global and outlive the test, so the appenders have to come off. */
    @AfterEach
    void detachAppenders() {
        mitgeschnitten.forEach(Logger::detachAndStopAllAppenders);
        mitgeschnitten.clear();
    }

    @Test
    void refusesToStartWithLocalAndProdTogether() {
        assertThatThrownBy(() -> start("local", "prod"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'local'")
                .hasMessageContaining("'prod'");
    }

    @Test
    void warnsAboutTheHostedModelInProd() {
        // The warning itself, without Spring: Boot re-initialises Logback while the context
        // starts, which would drop an appender attached beforehand.
        ListAppender<ILoggingEvent> appender = mitschnitt(HostedModelWarning.class);

        new HostedModelWarning();

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(meldung -> assertThat(meldung)
                        .contains("hosted model")
                        .contains("prod")
                        .contains("Art. 9"));
    }

    @Test
    void raisesThatWarningWhenTheHostedModelRunsInProd() {
        try (ConfigurableApplicationContext context = start("anthropic", "prod")) {
            assertThat(context.getBeansOfType(HostedModelWarning.class)).isNotEmpty();
        }
    }

    @Test
    void raisesThatWarningWhenAzureOpenAiRunsInProd() {
        try (ConfigurableApplicationContext context = start("azure-openai", "prod")) {
            assertThat(context.getBeansOfType(HostedModelWarning.class)).isNotEmpty();
        }
    }

    @Test
    void staysQuietWhenTheHostedModelIsNotInProd() {
        try (ConfigurableApplicationContext context = start("anthropic")) {
            assertThat(context.getBeansOfType(HostedModelWarning.class)).isEmpty();
        }
    }

    /**
     * A servlet context on a free port: the guards run while the context is built, but the
     * secured chain of §7 needs the web context to exist at all.
     */
    private static ConfigurableApplicationContext start(String... profiles) {
        return new SpringApplicationBuilder(DocAiApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles(profiles)
                .properties("server.port=0",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri="
                                + "https://login.microsoftonline.com/t/v2.0",
                        "spring.security.oauth2.resourceserver.jwt.audiences=api://doc-ai-test",
                        "spring.ai.anthropic.api-key=test-key-not-used",
                        "spring.ai.azure.openai.endpoint=https://doc-ai-test.openai.azure.com/",
                        "spring.ai.azure.openai.api-key=test-key-not-used")
                .run();
    }

    private ListAppender<ILoggingEvent> mitschnitt(Class<?> quelle) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        Logger logger = (Logger) LoggerFactory.getLogger(quelle);
        logger.addAppender(appender);
        mitgeschnitten.add(logger);
        return appender;
    }
}
