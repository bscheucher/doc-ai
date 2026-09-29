package com.learning.docai.kompetenz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.api.ApiEndpoint;
import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.api.GlobalExceptionHandler;
import com.learning.docai.api.MetrikRekorder;
import com.learning.docai.api.RequestIdFilter;
import com.learning.docai.config.IntakeProperties;
import com.learning.docai.config.ValidationProperties;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.TestDocuments;
import com.learning.docai.validation.SharedRules;

/**
 * Endpoint 4 against a mocked {@link DocumentAiClient} (SPEC §3.5, §6, §11).
 */
class KompetenzprofilControllerTest {

    private static final String PATH = "/api/v1/extraktion/kompetenzprofil";
    private static final String PROBLEM_TYPE = "https://doc-ai/errors/";

    private final DocumentAiClient aiClient = mock(DocumentAiClient.class);

    private final MetrikRekorder rekorder = new MetrikRekorder();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SharedRules shared = new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                ZoneId.of("Europe/Vienna")), new ValidationProperties(90, 14, 60));

        KompetenzprofilService service = new KompetenzprofilService(
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5)), aiClient,
                new KompetenzprofilValidator(shared), rekorder.metrics());

        mockMvc = MockMvcBuilders.standaloneSetup(new KompetenzprofilController(service))
                .setControllerAdvice(new GlobalExceptionHandler(rekorder.metrics()))
                .addFilters(new RequestIdFilter())
                // Spring Boot configures these two; the standalone builder does not, and would
                // write LocalDate as [1985,7,13] and read parts as ISO-8859-1.
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(
                                Jackson2ObjectMapperBuilder.json()
                                        .featuresToDisable(SerializationFeature
                                                .WRITE_DATES_AS_TIMESTAMPS)
                                        .build()))
                .build();
    }

    @Test
    void returnsTheEnvelopeOfSpec31() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.dokumenttyp").value("KOMPETENZPROFIL"))
                .andExpect(jsonPath("$.daten.vorname").value("Amira"))
                .andExpect(jsonPath("$.daten.nachname").value("Ahmed"))
                .andExpect(jsonPath("$.daten.geburtsdatum").value("1985-07-13"))
                .andExpect(jsonPath("$.daten.fachlich[0].bezeichnung").value("Buero Verwaltung"))
                .andExpect(jsonPath("$.daten.fachlich[0].score").value(70))
                .andExpect(jsonPath("$.daten.zertifikate[0]").value("Staplerschein"))
                .andExpect(jsonPath("$.probleme").isEmpty())
                .andExpect(jsonPath("$.manuellePruefung").value(false))
                .andExpect(jsonPath("$.metadaten.seiten").value(1));
    }

    @Test
    void writesListsTheModelLeftOutAsEmptyOnes() throws Exception {
        answerWith(new KompetenzprofilDaten("Amira", "Ahmed", LocalDate.of(1985, 7, 13), null,
                null, null, null, null));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daten.fachlich").isArray())
                .andExpect(jsonPath("$.daten.fachlich").isEmpty())
                .andExpect(jsonPath("$.daten.ueberfachlich").isArray())
                .andExpect(jsonPath("$.daten.zertifikate").isArray())
                .andExpect(jsonPath("$.daten.interessengebiete").isArray())
                // An empty `fachlich` is worth a look, but it is a warning, not a rejection.
                .andExpect(jsonPath("$.probleme[0].code").value("KEINE_KOMPETENZEN"))
                .andExpect(jsonPath("$.manuellePruefung").value(true));
    }

    @Test
    void reportsTheNatifFailureCaseAndAsksForReview() throws Exception {
        // A score with no competency next to it, exactly as natif returned it.
        answerWith(new KompetenzprofilDaten("Amira", "Ahmed", LocalDate.of(1985, 7, 13), null,
                Arrays.asList(new Kompetenz(null, 95), new Kompetenz("Berufserfahrung", 90)),
                List.of(), List.of(), List.of()));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuellePruefung").value(true))
                .andExpect(jsonPath("$.probleme[0].feld").value("fachlich[0]"))
                .andExpect(jsonPath("$.probleme[0].code").value("SCORE_OHNE_BEZEICHNUNG"))
                .andExpect(jsonPath("$.probleme[0].schweregrad").value("FEHLER"));
    }

    @Test
    void dropsEmptyRowsBeforeTheIndicesAreHandedOut() throws Exception {
        // The empty row is gone from `daten`, so `fachlich[0]` in an issue means the row the
        // caller can actually see.
        answerWith(new KompetenzprofilDaten("Amira", "Ahmed", null, null,
                Arrays.asList(new Kompetenz(null, null), new Kompetenz("Berufserfahrung", null)),
                List.of(), List.of(), List.of()));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daten.fachlich.length()").value(1))
                .andExpect(jsonPath("$.probleme[0].feld").value("fachlich[0]"))
                .andExpect(jsonPath("$.probleme[0].code").value("BEZEICHNUNG_OHNE_SCORE"));
    }

    /** SPEC §10: a clean document is `ok`, a finding makes it `review`. */
    @Test
    void recordsTheOutcomeOfEachRequest() throws Exception {
        answerWith(vollstaendig());
        mockMvc.perform(multipart(PATH).file(pdf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.manuellePruefung").value(false));

        assertThat(rekorder.anfragen(ApiEndpoint.KOMPETENZPROFIL, "ok")).isEqualTo(1);
        assertThat(rekorder.anfragen(ApiEndpoint.KOMPETENZPROFIL, "review")).isZero();
        assertThat(rekorder.modellaufrufe(ApiEndpoint.KOMPETENZPROFIL)).isEqualTo(1);
        assertThat(rekorder.modelldauerMs(ApiEndpoint.KOMPETENZPROFIL)).isEqualTo(2100);
        assertThat(rekorder.tokens("output")).isEqualTo(180);
    }

    @Test
    void passesTheKompetenzprofilInstructionToTheModel() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf())).andExpect(status().isOk());

        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(aiClient).extract(instruction.capture(), any(), eq(KompetenzprofilDaten.class));

        assertThat(instruction.getValue())
                .contains("fachlich")
                .contains("ueberfachlich");
    }

    @Test
    void mapsAnUnsupportedTypeTo415WithoutCallingTheModel() throws Exception {
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        mockMvc.perform(multipart(PATH).file(filePart(gif)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unsupported-type"));

        verify(aiClient, never()).extract(any(), any(), any());
    }

    @Test
    void mapsAMissingFilePartTo400() throws Exception {
        mockMvc.perform(multipart(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "missing-file"));
    }

    @Test
    void mapsAModelTimeoutTo504() throws Exception {
        when(aiClient.extract(any(), any(), eq(KompetenzprofilDaten.class)))
                .thenThrow(new DocAiException(ErrorType.MODEL_TIMEOUT));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "model-timeout"));
    }

    private void answerWith(KompetenzprofilDaten daten) {
        when(aiClient.extract(any(), any(), eq(KompetenzprofilDaten.class))).thenReturn(
                new AiResult<>(daten, "test", "test-modell", 2400, 180, 2100L));
    }

    private static KompetenzprofilDaten vollstaendig() {
        return new KompetenzprofilDaten("Amira", "Ahmed", LocalDate.of(1985, 7, 13), "1237010180",
                List.of(new Kompetenz("Buero Verwaltung", 70),
                        new Kompetenz("Berufserfahrung", 90)),
                List.of(new Kompetenz("Teamarbeit", 80)),
                List.of("Staplerschein"), List.of("Logistik"));
    }

    private static MockMultipartFile pdf() throws Exception {
        return filePart(TestDocuments.pdf(1));
    }

    private static MockMultipartFile filePart(byte[] content) {
        return new MockMultipartFile("file", "dokument.bin",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    }
}
