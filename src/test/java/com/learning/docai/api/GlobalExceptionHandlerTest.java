package com.learning.docai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.config.IntakeProperties;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.TestDocuments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Every intake error from SPEC §6 must map to the documented status and slug.
 */
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_TYPE = "https://doc-ai/errors/";

    private final MetrikRekorder rekorder = new MetrikRekorder();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DocumentIntakeService service =
                new DocumentIntakeService(new IntakeProperties(150, 1600, 5));
        mockMvc = MockMvcBuilders.standaloneSetup(new IntakeTestController(service))
                .setControllerAdvice(new GlobalExceptionHandler(rekorder.metrics()))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void returns200ForAValidDocument() throws Exception {
        mockMvc.perform(multipart("/test/intake").file(filePart(TestDocuments.pdf(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages").value(1));
    }

    @Test
    void mapsUnsupportedTypeTo415() throws Exception {
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        mockMvc.perform(multipart("/test/intake").file(filePart(gif)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unsupported-type"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void mapsTooManyPagesTo422() throws Exception {
        mockMvc.perform(multipart("/test/intake").file(filePart(TestDocuments.pdf(6))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unreadable-document"));
    }

    @Test
    void mapsEncryptedPdfTo422() throws Exception {
        mockMvc.perform(multipart("/test/intake").file(filePart(TestDocuments.pdfWithOwnerPasswordOnly())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unreadable-document"));
    }

    @Test
    void mapsEmptyFileTo400() throws Exception {
        mockMvc.perform(multipart("/test/intake").file(filePart(new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "missing-file"));
    }

    @Test
    void mapsAbsentFilePartTo400() throws Exception {
        mockMvc.perform(multipart("/test/intake"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "missing-file"));
    }

    @Test
    void mapsOversizedUploadTo413() throws Exception {
        mockMvc.perform(multipart("/test/too-large").file(filePart(new byte[] { 1, 2, 3 })))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "file-too-large"));
    }

    @Test
    void mapsUnexpectedFailuresTo500() throws Exception {
        mockMvc.perform(multipart("/test/boom").file(filePart(new byte[] { 1, 2, 3 })))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "internal-error"));
    }

    /**
     * SPEC §10: every problem response is one `docai.requests` with outcome `error`. Counted
     * here rather than in the services, because an unsupported type or an oversized upload
     * never reaches one. These test paths are not API paths, so the `endpoint` tag folds to
     * `unbekannt` - the bound that keeps a stray URI from opening its own time series.
     */
    @Test
    void countsEveryProblemResponseAsAnError() throws Exception {
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        mockMvc.perform(multipart("/test/intake").file(filePart(gif)))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(multipart("/test/boom").file(filePart(new byte[] { 1, 2, 3 })))
                .andExpect(status().isInternalServerError());

        assertThat(rekorder.anfragen(ApiEndpoint.UNBEKANNT, "error")).isEqualTo(2);
        assertThat(rekorder.anfragen(ApiEndpoint.UNBEKANNT, "ok")).isZero();
    }

    @Test
    void reportsTheRequestIdInBodyAndHeader() throws Exception {
        String incoming = UUID.randomUUID().toString();
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        mockMvc.perform(multipart("/test/intake").file(filePart(gif))
                        .header(RequestIdFilter.HEADER, incoming))
                .andExpect(header().string(RequestIdFilter.HEADER, incoming))
                .andExpect(jsonPath("$.requestId").value(incoming));
    }

    @Test
    void neverPutsTheFileNameOrContentIntoTheProblemBody() throws Exception {
        byte[] content = "Geheime Diagnose".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "krankenstand-mueller.txt",
                MediaType.TEXT_PLAIN_VALUE, content);

        String body = mockMvc.perform(multipart("/test/intake").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("krankenstand-mueller")
                .doesNotContain("Geheime Diagnose");
    }

    @Test
    void keepsSpringsOwn405ForAWrongVerb() throws Exception {
        mockMvc.perform(get("/test/intake"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void mapsANonMultipartRequestTo415() throws Exception {
        mockMvc.perform(post("/test/intake")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE + "unsupported-type"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void keepsSpringsOwn400ForAFailedValidation() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kennung\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void leavesAccessDeniedToSpringSecurity() {
        // Turning it into a 500 - or into a 403 here - would rob the security filter of the
        // choice between 401 and 403.
        assertThatThrownBy(() -> mockMvc.perform(get("/test/denied")))
                .rootCause()
                .isInstanceOf(AccessDeniedException.class);
    }

    private static MockMultipartFile filePart(byte[] content) {
        return new MockMultipartFile("file", "dokument.bin",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    }

    @RestController
    static class IntakeTestController {

        private final DocumentIntakeService service;

        IntakeTestController(DocumentIntakeService service) {
            this.service = service;
        }

        @PostMapping(path = "/test/intake", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        Map<String, Integer> intake(@RequestPart("file") MultipartFile file) {
            return Map.of("pages", service.toPageImages(file).pageCount());
        }

        @PostMapping(path = "/test/too-large", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        Map<String, Integer> tooLarge(@RequestPart("file") MultipartFile file) {
            throw new MaxUploadSizeExceededException(20L * 1024 * 1024);
        }

        @PostMapping(path = "/test/boom", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        Map<String, Integer> boom(@RequestPart("file") MultipartFile file) throws IOException {
            throw new IOException("unerwartet");
        }

        @PostMapping(path = "/test/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Integer> validated(@Valid @RequestBody Anfrage anfrage) {
            return Map.of("ok", 1);
        }

        @GetMapping("/test/denied")
        Map<String, Integer> denied() {
            throw new AccessDeniedException("kein Zugriff");
        }
    }

    record Anfrage(@NotBlank String kennung) {
    }
}
