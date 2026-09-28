package com.learning.docai.klassifikation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.intake.TestDocuments;

/**
 * Endpoint 1 over a real servlet stack. The MockMvc tests build the request themselves and so
 * never reach multipart parsing or the security chain; the 413 of SPEC §6 and the `local`
 * profile of SPEC §7 only exist there. The model is still mocked (CLAUDE.md hard rules).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=8KB")
@ActiveProfiles({ "test", "local" })
class KlassifikationServerTest {

    @MockitoBean
    private DocumentAiClient aiClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rejectsAnOversizedUploadWith413() {
        ResponseEntity<String> response = post(new byte[16 * 1024]);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody())
                .contains("https://doc-ai/errors/file-too-large")
                .contains("requestId");
    }

    @Test
    void servesTheEndpointWithoutAuthenticationOnTheLocalProfile() throws Exception {
        when(aiClient.extract(any(), any(), eq(KlassifikationErgebnis.class))).thenReturn(
                new AiResult<>(new KlassifikationErgebnis(Dokumenttyp.ZEITBESTAETIGUNG,
                        "Bestaetigung eines Termins."), "test", "test-modell", 1200, 42, 1500L));

        ResponseEntity<String> response = post(TestDocuments.pdf(1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ZEITBESTAETIGUNG");
    }

    private ResponseEntity<String> post(byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "dokument.pdf";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        return restTemplate.postForEntity("/api/v1/klassifikation",
                new HttpEntity<>(parts, headers), String.class);
    }
}
