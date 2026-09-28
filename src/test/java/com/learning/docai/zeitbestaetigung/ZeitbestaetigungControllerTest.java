package com.learning.docai.zeitbestaetigung;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
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
import java.time.LocalTime;
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
import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.api.GlobalExceptionHandler;
import com.learning.docai.api.RequestIdFilter;
import com.learning.docai.config.IntakeProperties;
import com.learning.docai.config.ValidationProperties;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.TestDocuments;
import com.learning.docai.validation.SharedRules;

/**
 * Endpoint 3 against a mocked {@link DocumentAiClient} (SPEC §3.4, §6, §11).
 */
class ZeitbestaetigungControllerTest {

    private static final String PATH = "/api/v1/extraktion/zeitbestaetigung";
    private static final String PROBLEM_TYPE = "https://doc-ai/errors/";
    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);

    private final DocumentAiClient aiClient = mock(DocumentAiClient.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SharedRules shared = new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                ZoneId.of("Europe/Vienna")), new ValidationProperties(90, 14, 60));

        ZeitbestaetigungService service = new ZeitbestaetigungService(
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5)), aiClient,
                new ZeitbestaetigungValidator(shared));

        mockMvc = MockMvcBuilders.standaloneSetup(new ZeitbestaetigungController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
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
                .andExpect(jsonPath("$.dokumenttyp").value("ZEITBESTAETIGUNG"))
                .andExpect(jsonPath("$.daten.vorname").value("Hans"))
                .andExpect(jsonPath("$.daten.datumVon").value("2026-03-15"))
                .andExpect(jsonPath("$.daten.datumBis").doesNotExist())
                .andExpect(jsonPath("$.daten.zeitVon").value(startsWith("10:30")))
                .andExpect(jsonPath("$.daten.zeitBis").value(startsWith("11:15")))
                .andExpect(jsonPath("$.daten.grundDerAbwesenheit").value("Arzttermin"))
                .andExpect(jsonPath("$.daten.aussteller").value("Ordination Dr. Muster"))
                .andExpect(jsonPath("$.probleme").isEmpty())
                .andExpect(jsonPath("$.manuellePruefung").value(false))
                .andExpect(jsonPath("$.metadaten.seiten").value(1));
    }

    @Test
    void reportsAMissingTimeAndAsksForReview() throws Exception {
        answerWith(new ZeitbestaetigungDaten("Hans", "Müller", HEUTE, null,
                LocalTime.of(10, 30), null, HEUTE, "Arzttermin", "Ordination Dr. Muster"));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuellePruefung").value(true))
                .andExpect(jsonPath("$.probleme[0].feld").value("zeitBis"))
                .andExpect(jsonPath("$.probleme[0].code").value("UHRZEIT_FEHLT"))
                .andExpect(jsonPath("$.probleme[0].schweregrad").value("WARNUNG"));
    }

    @Test
    void comparesTheParticipantHintsFromTheMultipartParts() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf())
                        .part(teil("vorname", "Hans"))
                        .part(teil("familienname", "Fischer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probleme[0].feld").value("familienname"))
                .andExpect(jsonPath("$.probleme[0].code").value("NAME_WEICHT_AB"));
    }

    @Test
    void passesTheZeitbestaetigungInstructionToTheModel() throws Exception {
        answerWith(vollstaendig());

        mockMvc.perform(multipart(PATH).file(pdf())).andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> instruction =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).extract(instruction.capture(), any(), eq(ZeitbestaetigungDaten.class));

        assertThat(instruction.getValue())
                .contains("zeitVon")
                .contains("zeitBis");
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
        when(aiClient.extract(any(), any(), eq(ZeitbestaetigungDaten.class)))
                .thenThrow(new DocAiException(ErrorType.MODEL_TIMEOUT));

        mockMvc.perform(multipart(PATH).file(pdf()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "model-timeout"));
    }

    private void answerWith(ZeitbestaetigungDaten daten) {
        when(aiClient.extract(any(), any(), eq(ZeitbestaetigungDaten.class))).thenReturn(
                new AiResult<>(daten, "test", "test-modell", 900, 30, 800L));
    }

    private static ZeitbestaetigungDaten vollstaendig() {
        return new ZeitbestaetigungDaten("Hans", "Müller", HEUTE, null, LocalTime.of(10, 30),
                LocalTime.of(11, 15), HEUTE, "Arzttermin", "Ordination Dr. Muster");
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
