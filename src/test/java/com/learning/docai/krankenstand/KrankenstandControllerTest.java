package com.learning.docai.krankenstand;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
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
 * Endpoint 2 against a mocked {@link DocumentAiClient} (SPEC §3.3, §6, §11). The validator is
 * the real one, so the envelope is assembled exactly as in production.
 */
class KrankenstandControllerTest {

    private static final String PATH = "/api/v1/extraktion/krankenstand";
    private static final String PROBLEM_TYPE = "https://doc-ai/errors/";
    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);
    private static final String GUELTIGE_SVNR = "1237010180";

    private final DocumentAiClient aiClient = mock(DocumentAiClient.class);

    private final MetrikRekorder rekorder = new MetrikRekorder();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ValidationProperties validation = new ValidationProperties(90, 14, 60);
        SharedRules shared = new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                ZoneId.of("Europe/Vienna")), validation);

        KrankenstandService service = new KrankenstandService(
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5)), aiClient,
                new KrankenstandValidator(shared, validation), rekorder.metrics());

        mockMvc = MockMvcBuilders.standaloneSetup(new KrankenstandController(service))
                .setControllerAdvice(new GlobalExceptionHandler(rekorder.metrics()))
                .addFilters(new RequestIdFilter())
                // Spring Boot configures these two; the standalone builder does not, and would
                // write LocalDate as [2026,3,15] and read parts as ISO-8859-1.
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
                .andExpect(jsonPath("$.dokumenttyp").value("KRANKENSTAND"))
                .andExpect(jsonPath("$.daten.vorname").value("Hans"))
                .andExpect(jsonPath("$.daten.familienname").value("Müller"))
                .andExpect(jsonPath("$.daten.versicherungsnummer").value(GUELTIGE_SVNR))
                .andExpect(jsonPath("$.daten.krankenstandsadresse").value("Musterweg 1, 1010 Wien"))
                .andExpect(jsonPath("$.daten.arbeitsunfaehigVon").value("2026-03-15"))
                .andExpect(jsonPath("$.daten.letzterTagArbeitsunfaehigkeit").value("2026-03-18"))
                .andExpect(jsonPath("$.daten.ausstellungsdatum").value("2026-03-15"))
                .andExpect(jsonPath("$.probleme").isArray())
                .andExpect(jsonPath("$.probleme").isEmpty())
                .andExpect(jsonPath("$.manuellePruefung").value(false))
                .andExpect(jsonPath("$.metadaten.provider").value("test"))
                .andExpect(jsonPath("$.metadaten.seiten").value(1))
                .andExpect(jsonPath("$.metadaten.dauerMs").value(1500));
    }

    @Test
    void reportsAFindingAndAsksForReview() throws Exception {
        answerWith(new KrankenstandDaten(null, "Müller", GUELTIGE_SVNR, null, HEUTE,
                HEUTE.plusDays(3), HEUTE));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuellePruefung").value(true))
                .andExpect(jsonPath("$.probleme[0].feld").value("vorname"))
                .andExpect(jsonPath("$.probleme[0].code").value("PFLICHTFELD_FEHLT"))
                .andExpect(jsonPath("$.probleme[0].schweregrad").value("FEHLER"))
                .andExpect(jsonPath("$.probleme[0].meldung").isNotEmpty());
    }

    /**
     * SPEC §10: `manuellePruefung` decides between `ok` and `review`, which for endpoints 2-4
     * means a clean document counts as `ok` and any finding as `review`.
     */
    @Test
    void recordsTheOutcomeOfEachRequest() throws Exception {
        answerWith(vollstaendig());
        mockMvc.perform(multipart(PATH).file(pdf())).andExpect(status().isOk());

        answerWith(new KrankenstandDaten(null, "Müller", GUELTIGE_SVNR, null, HEUTE,
                HEUTE.plusDays(3), HEUTE));
        mockMvc.perform(multipart(PATH).file(pdf())).andExpect(status().isOk());

        assertThat(rekorder.anfragen(ApiEndpoint.KRANKENSTAND, "ok")).isEqualTo(1);
        assertThat(rekorder.anfragen(ApiEndpoint.KRANKENSTAND, "review")).isEqualTo(1);
        assertThat(rekorder.modellaufrufe(ApiEndpoint.KRANKENSTAND)).isEqualTo(2);
        assertThat(rekorder.tokens("input")).isEqualTo(2400);
    }

    @Test
    void comparesTheParticipantHintsFromTheMultipartParts() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf())
                        .part(teil("vorname", "Hans"))
                        .part(teil("familienname", "Fischer"))
                        .part(teil("svnr", "4568 150392")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuellePruefung").value(true))
                .andExpect(jsonPath("$.probleme[?(@.code == 'NAME_WEICHT_AB')].feld")
                        .value("familienname"))
                .andExpect(jsonPath("$.probleme[?(@.code == 'SVNR_WEICHT_AB')]").isNotEmpty());
    }

    @Test
    void keepsAnUmlautInAHintIntact() throws Exception {
        // The hint travels as a multipart part, so its charset is the part's, not the URL's.
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf()).part(teil("familienname", "Müller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probleme").isEmpty());
    }

    @Test
    void worksWithoutAnyHints() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probleme").isEmpty());
    }

    @Test
    void passesTheKrankenstandInstructionToTheModel() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf())).andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> instruction =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).extract(instruction.capture(), any(), eq(KrankenstandDaten.class));

        assertThat(instruction.getValue())
                .contains("Krankenstandsbestaetigung")
                .contains("versicherungsnummer");
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
    void mapsTooManyPagesTo422WithoutCallingTheModel() throws Exception {
        mockMvc.perform(multipart(PATH).file(filePart(TestDocuments.pdf(6))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unreadable-document"));

        verify(aiClient, never()).extract(any(), any(), any());
    }

    @Test
    void mapsAMissingFilePartTo400() throws Exception {
        mockMvc.perform(multipart(PATH).part(teil("vorname", "Hans")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "missing-file"));
    }

    @Test
    void mapsAModelErrorTo502() throws Exception {
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class)))
                .thenThrow(new DocAiException(ErrorType.MODEL_ERROR));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "model-error"));
    }

    @Test
    void neverEchoesTheFileNameOrContentInAProblemBody() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "krankenstand-mueller.txt",
                MediaType.TEXT_PLAIN_VALUE, "Diagnose: Grippe".getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(multipart(PATH).file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("krankenstand-mueller").doesNotContain("Grippe");
    }

    private void answerWith(KrankenstandDaten daten) {
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class))).thenReturn(
                new AiResult<>(daten, "test", "test-modell", 1200, 42, 1500L));
    }

    private static KrankenstandDaten vollstaendig() {
        return new KrankenstandDaten("Hans", "Müller", GUELTIGE_SVNR, "Musterweg 1, 1010 Wien",
                HEUTE, HEUTE.plusDays(3), HEUTE);
    }

    private static MockPart teil(String name, String wert) {
        MockPart part = new MockPart(name, wert.getBytes(StandardCharsets.UTF_8));
        part.getHeaders().setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        return part;
    }

    private static MockMultipartFile pdf() throws Exception {
        return filePart(TestDocuments.pdf(1));
    }

    private static MockMultipartFile filePart(byte[] content) {
        return new MockMultipartFile("file", "dokument.bin",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    }
}
