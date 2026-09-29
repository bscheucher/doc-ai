package com.learning.docai.klassifikation;

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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.api.ApiEndpoint;
import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.api.GlobalExceptionHandler;
import com.learning.docai.api.MetrikRekorder;
import com.learning.docai.api.RequestIdFilter;
import com.learning.docai.config.IntakeProperties;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.TestDocuments;

/**
 * Endpoint 1 against a mocked {@link DocumentAiClient} (SPEC §3.2, §6, §11). No model is
 * reached: the AI client is the seam, so every case here is deterministic.
 */
class KlassifikationControllerTest {

    private static final String PROBLEM_TYPE = "https://doc-ai/errors/";

    private final DocumentAiClient aiClient = mock(DocumentAiClient.class);

    private final MetrikRekorder rekorder = new MetrikRekorder();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DocumentIntakeService intake =
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5));
        KlassifikationController controller = new KlassifikationController(
                new KlassifikationService(intake, aiClient, rekorder.metrics()));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(rekorder.metrics()))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void returnsTheClassificationWithoutManualReview() throws Exception {
        answerWith(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG,
                "OEGK-Formular mit Zeitraum der Arbeitsunfaehigkeit.");

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typ").value("KRANKENSTANDSBESTAETIGUNG"))
                .andExpect(jsonPath("$.begruendung")
                        .value("OEGK-Formular mit Zeitraum der Arbeitsunfaehigkeit."))
                .andExpect(jsonPath("$.manuellePruefung").value(false))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.metadaten.provider").value("test"))
                .andExpect(jsonPath("$.metadaten.modell").value("test-modell"))
                .andExpect(jsonPath("$.metadaten.seiten").value(1))
                .andExpect(jsonPath("$.metadaten.inputTokens").value(1200))
                .andExpect(jsonPath("$.metadaten.outputTokens").value(42))
                .andExpect(jsonPath("$.metadaten.dauerMs").value(1500));
    }

    @Test
    void setsManualReviewForAnUnknownType() throws Exception {
        answerWith(Dokumenttyp.UNBEKANNT, "Kein zuordenbares Formular erkennbar.");

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typ").value("UNBEKANNT"))
                .andExpect(jsonPath("$.manuellePruefung").value(true));
    }

    @Test
    void treatsAMissingTypeAsUnknownRatherThanFailing() throws Exception {
        answerWith(null, "Nicht lesbar.");

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typ").value("UNBEKANNT"))
                .andExpect(jsonPath("$.manuellePruefung").value(true));
    }

    /**
     * SPEC §10: a classification that needs no review counts as `ok`, and UNBEKANNT - the one
     * case that sets manuellePruefung here - as `review`. The model call is timed with the
     * duration the client reported, and its tokens are counted per direction.
     */
    @Test
    void recordsTheMetricsOfTheSuccessPath() throws Exception {
        answerWith(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG, "OEGK-Formular.");
        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isOk());

        answerWith(Dokumenttyp.UNBEKANNT, "Kein zuordenbares Formular.");
        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isOk());

        assertThat(rekorder.anfragen(ApiEndpoint.KLASSIFIKATION, "ok")).isEqualTo(1);
        assertThat(rekorder.anfragen(ApiEndpoint.KLASSIFIKATION, "review")).isEqualTo(1);
        assertThat(rekorder.anfragen(ApiEndpoint.KLASSIFIKATION, "error")).isZero();
        assertThat(rekorder.modellaufrufe(ApiEndpoint.KLASSIFIKATION)).isEqualTo(2);
        assertThat(rekorder.modelldauerMs(ApiEndpoint.KLASSIFIKATION)).isEqualTo(3000);
        assertThat(rekorder.tokens("input")).isEqualTo(2400);
        assertThat(rekorder.tokens("output")).isEqualTo(84);
    }

    /** A failed request is one `error` and no model-call sample (SPEC §10). */
    @Test
    void recordsAFailureWithoutAModelCall() throws Exception {
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        mockMvc.perform(multipart("/api/v1/klassifikation").file(filePart(gif)))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(rekorder.anfragen(ApiEndpoint.KLASSIFIKATION, "error")).isEqualTo(1);
        assertThat(rekorder.anfragen(ApiEndpoint.KLASSIFIKATION, "ok")).isZero();
        assertThat(rekorder.modellaufrufe(ApiEndpoint.KLASSIFIKATION)).isZero();
    }

    @Test
    void reportsEveryRenderedPageInTheMetadata() throws Exception {
        answerWith(Dokumenttyp.ZEITBESTAETIGUNG, "Ambulanzbestaetigung mit Uhrzeit von-bis.");

        mockMvc.perform(multipart("/api/v1/klassifikation")
                        .file(filePart(TestDocuments.pdf(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadaten.seiten").value(3));
    }

    @Test
    void echoesTheIncomingRequestId() throws Exception {
        answerWith(Dokumenttyp.ZEITBESTAETIGUNG, "Bestaetigung eines Termins.");
        String incoming = UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf())
                        .header(RequestIdFilter.HEADER, incoming))
                .andExpect(jsonPath("$.requestId").value(incoming))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(RequestIdFilter.HEADER, incoming));
    }

    @Test
    void mapsAnUnsupportedTypeTo415WithoutCallingTheModel() throws Exception {
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        mockMvc.perform(multipart("/api/v1/klassifikation").file(filePart(gif)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unsupported-type"));

        verify(aiClient, never()).extract(any(), any(), any());
    }

    @Test
    void mapsTooManyPagesTo422WithoutCallingTheModel() throws Exception {
        mockMvc.perform(multipart("/api/v1/klassifikation").file(filePart(TestDocuments.pdf(6))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unreadable-document"));

        verify(aiClient, never()).extract(any(), any(), any());
    }

    @Test
    void mapsAMissingFilePartTo400() throws Exception {
        mockMvc.perform(multipart("/api/v1/klassifikation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "missing-file"));
    }

    @Test
    void mapsAModelErrorTo502() throws Exception {
        failWith(ErrorType.MODEL_ERROR);

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "model-error"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void mapsAnUnavailableModelTo503() throws Exception {
        failWith(ErrorType.MODEL_UNAVAILABLE);

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "model-unavailable"));
    }

    @Test
    void mapsAModelTimeoutTo504() throws Exception {
        failWith(ErrorType.MODEL_TIMEOUT);

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "model-timeout"));
    }

    @Test
    void passesTheKlassifikationInstructionToTheModel() throws Exception {
        answerWith(Dokumenttyp.ZEITBESTAETIGUNG, "Bestaetigung eines Termins.");

        mockMvc.perform(multipart("/api/v1/klassifikation").file(pdf()))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> instruction =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).extract(instruction.capture(), any(),
                eq(KlassifikationErgebnis.class));

        assertThat(instruction.getValue())
                .contains("KRANKENSTANDSBESTAETIGUNG")
                .contains("ZEITBESTAETIGUNG")
                .contains("UNBEKANNT");
    }

    @Test
    void neverEchoesTheFileNameOrContentInAProblemBody() throws Exception {
        byte[] content = "Diagnose: Grippe".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "krankenstand-mueller.txt",
                MediaType.TEXT_PLAIN_VALUE, content);

        String body = mockMvc.perform(multipart("/api/v1/klassifikation").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("krankenstand-mueller").doesNotContain("Grippe");
    }

    private void answerWith(Dokumenttyp typ, String begruendung) {
        when(aiClient.extract(any(), any(), eq(KlassifikationErgebnis.class))).thenReturn(
                new AiResult<>(new KlassifikationErgebnis(typ, begruendung),
                        "test", "test-modell", 1200, 42, 1500L));
    }

    private void failWith(ErrorType errorType) {
        when(aiClient.extract(any(), any(), eq(KlassifikationErgebnis.class)))
                .thenThrow(new DocAiException(errorType));
    }

    private static MockMultipartFile pdf() throws Exception {
        return filePart(TestDocuments.pdf(1));
    }

    private static MockMultipartFile filePart(byte[] content) {
        return new MockMultipartFile("file", "dokument.bin",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    }
}
