package com.learning.docai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

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
import com.learning.docai.krankenstand.KrankenstandDaten;
import com.learning.docai.zeitbestaetigung.ZeitbestaetigungDaten;

/**
 * The envelope of SPEC §3.1 as ibosNG actually receives it. The MockMvc tests build their own
 * converters, so only a real server settles how a LocalDate reaches the caller - Jackson
 * writes it as [2026,3,15] unless it is configured otherwise, and that is a contract question,
 * not a test detail.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=8KB")
@ActiveProfiles({ "test", "local" })
class ExtraktionServerTest {

    /** Relative to the real clock: the app validates against today, not against a fixture. */
    private static final LocalDate TAG = LocalDate.now(ZoneId.of("Europe/Vienna")).minusDays(3);

    @MockitoBean
    private DocumentAiClient aiClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void writesDatesAsIsoStrings() throws Exception {
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class))).thenReturn(
                new AiResult<>(new KrankenstandDaten("Hans", "Müller", "1237010180",
                        "Musterweg 1, 1010 Wien", TAG, TAG.plusDays(3), TAG),
                        "test", "test-modell", 1200, 42, 1500L));

        MultiValueMap<String, Object> parts = upload();
        parts.add("vorname", "Hans");
        parts.add("familienname", "Müller");

        ResponseEntity<String> response = post("/api/v1/extraktion/krankenstand", parts);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"arbeitsunfaehigVon\":\"" + TAG + "\"")
                .contains("\"dokumenttyp\":\"KRANKENSTAND\"")
                .doesNotContain("[" + TAG.getYear() + ",");
    }

    @Test
    void keepsAnUmlautInAHintIntactOverTheWire() throws Exception {
        // "Müller" against "Müller" only matches if the part survived as UTF-8.
        when(aiClient.extract(any(), any(), eq(KrankenstandDaten.class))).thenReturn(
                new AiResult<>(new KrankenstandDaten("Hans", "Müller", "1237010180",
                        "Musterweg 1, 1010 Wien", TAG, TAG.plusDays(3), TAG),
                        "test", "test-modell", 1200, 42, 1500L));

        MultiValueMap<String, Object> parts = upload();
        parts.add("familienname", "Müller");

        ResponseEntity<String> response = post("/api/v1/extraktion/krankenstand", parts);

        assertThat(response.getBody()).doesNotContain("NAME_WEICHT_AB");
    }

    @Test
    void writesTimesAsHoursAndMinutes() throws Exception {
        when(aiClient.extract(any(), any(), eq(ZeitbestaetigungDaten.class))).thenReturn(
                new AiResult<>(new ZeitbestaetigungDaten("Hans", "Müller", TAG, null,
                        LocalTime.of(10, 30), LocalTime.of(11, 15), TAG, "Arzttermin",
                        "Ordination Dr. Muster"), "test", "test-modell", 900, 30, 800L));

        ResponseEntity<String> response =
                post("/api/v1/extraktion/zeitbestaetigung", upload());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"zeitVon\":\"10:30\"")
                .contains("\"zeitBis\":\"11:15\"")
                .contains("\"datumBis\":null");
    }

    private static MultiValueMap<String, Object> upload() throws Exception {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(TestDocuments.pdf(1)) {
            @Override
            public String getFilename() {
                return "dokument.pdf";
            }
        });
        return parts;
    }

    private ResponseEntity<String> post(String path, MultiValueMap<String, Object> parts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(path, new HttpEntity<>(parts, headers), String.class);
    }
}
