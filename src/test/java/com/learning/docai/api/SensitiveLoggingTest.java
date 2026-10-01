package com.learning.docai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.config.IntakeProperties;
import com.learning.docai.config.ValidationProperties;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.TestDocuments;
import com.learning.docai.krankenstand.KrankenstandController;
import com.learning.docai.krankenstand.KrankenstandDaten;
import com.learning.docai.krankenstand.KrankenstandService;
import com.learning.docai.krankenstand.KrankenstandValidator;
import com.learning.docai.validation.SharedRules;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * SPEC §11 and the hard rules in CLAUDE.md: a request must not leave extracted values, the
 * Teilnehmer hints or the file name anywhere in the log.
 *
 * <p>The appender sits on the root logger, so it collects every line the request produces, and
 * `com.learning.docai` is turned down to TRACE on top of that: our own code is examined at maximum
 * verbosity, including lines that would be below the threshold in production, while the frameworks
 * stay at the level a deployment actually runs at. Raising the root itself to TRACE would instead
 * test Spring MVC's debug feature of dumping the serialised response body - which is document
 * content by definition, is never enabled in production, and would say nothing about our code.
 *
 * <p>The values below are deliberately unusual strings: a value that also occurs in ordinary log
 * text could not tell a leak from a coincidence.
 */
class SensitiveLoggingTest {

    private static final String PATH = "/api/v1/extraktion/krankenstand";
    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);

    /** Extracted values - what the model read off the document. */
    private static final String VORNAME = "Hansjoerg";
    private static final String FAMILIENNAME = "Zwetschkenbaum";
    private static final String SVNR = "1237010180";
    private static final String ADRESSE = "Geheimgasse 7, 9999 Verschwiegen";

    /** Hints from the caller, which are just as sensitive and deliberately differ from the above. */
    private static final String HINWEIS_VORNAME = "Hinweisvorname";
    private static final String HINWEIS_FAMILIENNAME = "Hinweisfamilienname";
    private static final String HINWEIS_SVNR = "4568150392";

    private static final String DATEINAME = "krankmeldung-zwetschkenbaum.pdf";

    private final DocumentAiClient aiClient = mock(DocumentAiClient.class);
    private final ListAppender<ILoggingEvent> mitschnitt = new ListAppender<>();

    private Logger root;
    private Logger docai;
    private Level vorherigesLevel;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ValidationProperties validation = new ValidationProperties(90, 14, 60);
        SharedRules shared = new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                ZoneId.of("Europe/Vienna")), validation);
        KrankenstandService service = new KrankenstandService(
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5)), aiClient,
                new KrankenstandValidator(shared, validation),
                new DocAiMetrics(new SimpleMeterRegistry()));

        mockMvc = MockMvcBuilders.standaloneSetup(new KrankenstandController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new DocAiMetrics(
                        new SimpleMeterRegistry())))
                .addFilters(new RequestIdFilter())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();

        mitschnitt.start();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(mitschnitt);

        docai = (Logger) LoggerFactory.getLogger("com.learning.docai");
        vorherigesLevel = docai.getLevel();
        docai.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(mitschnitt);
        docai.setLevel(vorherigesLevel);
        mitschnitt.stop();
    }

    @Test
    void logsNothingFromASuccessfulRequest() throws Exception {
        antworteMit(new KrankenstandDaten(VORNAME, FAMILIENNAME, SVNR, ADRESSE, HEUTE,
                HEUTE.plusDays(3), HEUTE));

        mockMvc.perform(multipart(PATH).file(pdf())
                        .part(teil("vorname", HINWEIS_VORNAME))
                        .part(teil("familienname", HINWEIS_FAMILIENNAME))
                        .part(teil("svnr", HINWEIS_SVNR)))
                .andExpect(status().isOk());

        assertThat(protokoll()).isNotEmpty();
        assertThat(protokoll()).doesNotContain(VORNAME, FAMILIENNAME, SVNR, ADRESSE,
                HINWEIS_VORNAME, HINWEIS_FAMILIENNAME, HINWEIS_SVNR, DATEINAME,
                // The dates are extracted values too, and the fixed clock keeps them clear of
                // any real timestamp in the log.
                "2026-03-15", "2026-03-18");
    }

    /**
     * The findings path logs more than the clean one - codes, and for endpoint 2 a name that did
     * not match - so it is the likelier place for a value to slip in.
     */
    @Test
    void logsNothingFromARequestWithFindings() throws Exception {
        antworteMit(new KrankenstandDaten(VORNAME, FAMILIENNAME, "1111111111", null, HEUTE,
                null, HEUTE));

        mockMvc.perform(multipart(PATH).file(pdf())
                        .part(teil("familienname", HINWEIS_FAMILIENNAME)))
                .andExpect(status().isOk());

        assertThat(protokoll())
                // The codes themselves are what may be logged, so the test is honest only if
                // they are actually there. NAME_WEICHT_AB also proves the hint comparison ran,
                // which is the path that has both a hint and an extracted name in hand.
                .contains("NAME_WEICHT_AB")
                .doesNotContain(VORNAME, FAMILIENNAME, "1111111111", HINWEIS_FAMILIENNAME,
                        DATEINAME);
    }

    /** A rejected document must not put its name or its bytes into the log either (SPEC §6). */
    @Test
    void logsNothingFromARejectedDocument() throws Exception {
        byte[] inhalt = ("Diagnose: " + ADRESSE).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile datei = new MockMultipartFile("file", DATEINAME,
                MediaType.TEXT_PLAIN_VALUE, inhalt);

        mockMvc.perform(multipart(PATH).file(datei))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(protokoll()).doesNotContain(DATEINAME, ADRESSE, "Diagnose");
    }

    /**
     * Everything the appender saw: the formatted message, the raw pattern and the arguments. An
     * argument that never reaches the message - because the line was below the threshold in
     * production - would still show a value that was handed to the logger.
     */
    private String protokoll() {
        return mitschnitt.list.stream()
                .flatMap(SensitiveLoggingTest::bestandteile)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static Stream<String> bestandteile(ILoggingEvent event) {
        Object[] argumente = event.getArgumentArray();
        return Stream.concat(
                Stream.of(event.getFormattedMessage(), event.getMessage(),
                        String.valueOf(event.getThrowableProxy())),
                argumente == null ? Stream.of()
                        : Arrays.stream(argumente).map(String::valueOf));
    }

    private void antworteMit(KrankenstandDaten daten) {
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class))).thenReturn(
                new AiResult<>(daten, "test", "test-modell", 1200, 42, 1500L));
    }

    private static MockMultipartFile pdf() throws Exception {
        return new MockMultipartFile("file", DATEINAME, MediaType.APPLICATION_PDF_VALUE,
                TestDocuments.pdf(1));
    }

    private static MockPart teil(String name, String wert) {
        MockPart part = new MockPart(name, wert.getBytes(StandardCharsets.UTF_8));
        part.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        return part;
    }
}
