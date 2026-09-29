package com.learning.docai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.api.DocAiException;
import com.learning.docai.api.DocAiMetrics;
import com.learning.docai.api.ErrorType;
import com.learning.docai.config.EvalProperties;
import com.learning.docai.config.JacksonConfig;
import com.learning.docai.config.IntakeProperties;
import com.learning.docai.config.ValidationProperties;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.TestDocuments;
import com.learning.docai.klassifikation.Dokumenttyp;
import com.learning.docai.klassifikation.KlassifikationErgebnis;
import com.learning.docai.klassifikation.KlassifikationService;
import com.learning.docai.kompetenz.KompetenzprofilService;
import com.learning.docai.kompetenz.KompetenzprofilValidator;
import com.learning.docai.krankenstand.KrankenstandDaten;
import com.learning.docai.krankenstand.KrankenstandService;
import com.learning.docai.krankenstand.KrankenstandValidator;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.zeitbestaetigung.ZeitbestaetigungDaten;
import com.learning.docai.zeitbestaetigung.ZeitbestaetigungService;
import com.learning.docai.zeitbestaetigung.ZeitbestaetigungValidator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The evaluation tool of SPEC §12 against a mocked model: it has to produce one JSON per document
 * and one summary.csv, survive a document the service rejects, and compare against a reference
 * where one exists.
 */
class EvalRunnerTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);
    private static final String GUELTIGE_SVNR = "1237010180";

    private final DocumentAiClient aiClient = mock(DocumentAiClient.class);
    private final ObjectMapper mapper = objectMapper();

    /**
     * Boot's mapper as the application builds it, including the customizer of
     * {@link JacksonConfig}: the evaluation output has to be the same JSON the API sends, and a
     * mapper assembled by hand here would quietly stop testing that.
     */
    private static ObjectMapper objectMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new JacksonConfig().uhrzeitOhneSekunden().customize(builder);
        return builder.build();
    }

    @TempDir
    Path wurzel;

    private EvalRunner runner;
    private Path input;
    private Path output;
    private Path expected;

    @BeforeEach
    void setUp() {
        input = wurzel.resolve("input");
        output = wurzel.resolve("output");
        expected = wurzel.resolve("expected");

        ValidationProperties validation = new ValidationProperties(90, 14, 60);
        SharedRules shared = new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                ZoneId.of("Europe/Vienna")), validation);
        DocumentIntakeService intake =
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5));
        DocAiMetrics metrics = new DocAiMetrics(new SimpleMeterRegistry());

        runner = new EvalRunner(new EvalProperties(input, output, expected), mapper,
                new KlassifikationService(intake, aiClient, metrics),
                new KrankenstandService(intake, aiClient,
                        new KrankenstandValidator(shared, validation), metrics),
                new ZeitbestaetigungService(intake, aiClient,
                        new ZeitbestaetigungValidator(shared), metrics),
                new KompetenzprofilService(intake, aiClient,
                        new KompetenzprofilValidator(shared), metrics));
    }

    @Test
    void writesOneJsonPerDocumentAndOneSummary() throws Exception {
        antworteKrankenstand(vollstaendig());
        antworteKlassifikation(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG);
        lege("krankenstand", "fall-a.pdf");
        lege("klassifikation", "fall-b.pdf");

        runner.run(new DefaultApplicationArguments());

        assertThat(output.resolve("krankenstand/fall-a.json")).isRegularFile();
        assertThat(output.resolve("klassifikation/fall-b.json")).isRegularFile();

        List<String> csv = summary();
        assertThat(csv.get(0)).isEqualTo(
                "datei,endpunkt,typ,manuellePruefung,codes,inputTokens,outputTokens,dauerMs");
        assertThat(csv).anySatisfy(zeile -> assertThat(zeile)
                .startsWith("fall-b.pdf,klassifikation,KRANKENSTANDSBESTAETIGUNG,false,"));
        assertThat(csv).anySatisfy(zeile -> assertThat(zeile)
                .startsWith("fall-a.pdf,krankenstand,KRANKENSTAND,false,"));
    }

    /** The JSON is the response the API would have sent, so it can be diffed against one. */
    @Test
    void writesTheResponseEnvelopeAsJson() throws Exception {
        antworteKrankenstand(vollstaendig());
        lege("krankenstand", "fall-a.pdf");

        runner.run(new DefaultApplicationArguments());

        String json = Files.readString(output.resolve("krankenstand/fall-a.json"));
        assertThat(json)
                .contains("\"dokumenttyp\" : \"KRANKENSTAND\"")
                .contains("\"arbeitsunfaehigVon\" : \"2026-03-15\"")
                .contains("\"manuellePruefung\" : false");
    }

    @Test
    void reportsTheIssueCodesOfAFinding() throws Exception {
        // No first day of inability to work: a mandatory field, so PFLICHTFELD_FEHLT.
        antworteKrankenstand(new KrankenstandDaten("Hans", "Müller", GUELTIGE_SVNR,
                "Musterweg 1", null, null, HEUTE));
        lege("krankenstand", "lueckenhaft.pdf");

        runner.run(new DefaultApplicationArguments());

        assertThat(summary()).anySatisfy(zeile -> assertThat(zeile)
                .startsWith("lueckenhaft.pdf,krankenstand,KRANKENSTAND,true,")
                .contains("PFLICHTFELD_FEHLT"));
    }

    /**
     * SPEC §12: where a reference exists, every field it names gets its own column. A reference
     * that fixes two fields asks about those two, not about the whole record.
     */
    @Test
    void comparesFieldByFieldAgainstTheReference() throws Exception {
        antworteKrankenstand(vollstaendig());
        lege("krankenstand", "fall-a.pdf");
        legeErwartung("fall-a.json", """
                {
                  "vorname": "Hans",
                  "arbeitsunfaehigVon": "2026-03-99"
                }""");

        runner.run(new DefaultApplicationArguments());

        List<String> csv = summary();
        assertThat(csv.get(0)).endsWith(",match.arbeitsunfaehigVon,match.vorname");
        assertThat(csv).anySatisfy(zeile -> assertThat(zeile).endsWith(",false,true"));
    }

    /** A reference may be a whole envelope, which is what an evaluator copies from a good run. */
    @Test
    void acceptsAReferenceThatCarriesTheWholeEnvelope() throws Exception {
        antworteKrankenstand(vollstaendig());
        lege("krankenstand", "fall-a.pdf");
        legeErwartung("fall-a.json", """
                {
                  "dokumenttyp": "KRANKENSTAND",
                  "daten": { "familienname": "Müller" }
                }""");

        runner.run(new DefaultApplicationArguments());

        assertThat(summary().get(0)).endsWith(",match.familienname");
        assertThat(summary()).anySatisfy(zeile -> assertThat(zeile).endsWith(",true"));
    }

    @Test
    void recordsARejectedDocumentInsteadOfStopping() throws Exception {
        antworteKrankenstand(vollstaendig());
        Files.createDirectories(input.resolve("krankenstand"));
        Files.write(input.resolve("krankenstand/kein-dokument.gif"),
                new byte[] { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 });
        lege("krankenstand", "fall-a.pdf");

        runner.run(new DefaultApplicationArguments());

        assertThat(summary()).anySatisfy(zeile -> assertThat(zeile)
                .startsWith("kein-dokument.gif,krankenstand,unsupported-type,,"));
        // The healthy document in the same directory is still evaluated.
        assertThat(output.resolve("krankenstand/fall-a.json")).isRegularFile();
    }

    @Test
    void passesAModelFailureIntoTheSummary() throws Exception {
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class)))
                .thenThrow(new DocAiException(ErrorType.MODEL_TIMEOUT));
        lege("krankenstand", "langsam.pdf");

        runner.run(new DefaultApplicationArguments());

        assertThat(summary()).anySatisfy(zeile -> assertThat(zeile)
                .startsWith("langsam.pdf,krankenstand,model-timeout,,"));
    }

    @Test
    void skipsAnEndpointWithoutAnInputDirectory() throws Exception {
        antworteKlassifikation(Dokumenttyp.ZEITBESTAETIGUNG);
        lege("klassifikation", "fall-b.pdf");

        runner.run(new DefaultApplicationArguments());

        assertThat(output.resolve("krankenstand")).doesNotExist();
        assertThat(summary()).hasSize(2);
    }

    @Test
    void writesAHeaderOnlySummaryWhenThereIsNothingToEvaluate() throws Exception {
        runner.run(new DefaultApplicationArguments());

        assertThat(summary()).hasSize(1);
    }

    /** SPEC §3.4 promises HH:mm, and the evaluation output is the same envelope. */
    @Test
    void writesTimesTheWayTheApiDoes() throws Exception {
        when(aiClient.extract(any(), any(), eq(ZeitbestaetigungDaten.class))).thenReturn(
                new AiResult<>(new ZeitbestaetigungDaten("Hans", "Müller", HEUTE, null,
                        LocalTime.of(10, 30), LocalTime.of(11, 15), HEUTE, "Arzttermin",
                        "Ordination Dr. Muster"), "test", "test-modell", 900, 30, 800L));
        lege("zeitbestaetigung", "termin.pdf");

        runner.run(new DefaultApplicationArguments());

        assertThat(Files.readString(output.resolve("zeitbestaetigung/termin.json")))
                .contains("\"zeitVon\" : \"10:30\"");
    }

    private List<String> summary() throws IOException {
        return Files.readAllLines(output.resolve("summary.csv"), StandardCharsets.UTF_8);
    }

    private void lege(String endpunkt, String name) throws Exception {
        Files.createDirectories(input.resolve(endpunkt));
        Files.write(input.resolve(endpunkt).resolve(name), TestDocuments.pdf(1));
    }

    private void legeErwartung(String name, String json) throws IOException {
        Files.createDirectories(expected);
        Files.writeString(expected.resolve(name), json);
    }

    private void antworteKrankenstand(KrankenstandDaten daten) {
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class))).thenReturn(
                new AiResult<>(daten, "test", "test-modell", 1200, 42, 1500L));
    }

    private void antworteKlassifikation(Dokumenttyp typ) {
        when(aiClient.extract(any(), any(), eq(KlassifikationErgebnis.class))).thenReturn(
                new AiResult<>(new KlassifikationErgebnis(typ, "Begruendung."),
                        "test", "test-modell", 1200, 42, 1500L));
    }

    private static KrankenstandDaten vollstaendig() {
        return new KrankenstandDaten("Hans", "Müller", GUELTIGE_SVNR, "Musterweg 1, 1010 Wien",
                HEUTE, HEUTE.plusDays(3), HEUTE);
    }
}
