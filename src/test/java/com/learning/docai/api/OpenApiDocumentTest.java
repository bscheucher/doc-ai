package com.learning.docai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.learning.docai.ai.DocumentAiClient;

/**
 * SPEC §10: the schema is served at /v3/api-docs and describes all four endpoints, with an
 * example per success response and the bearer scheme of SPEC §7.
 *
 * <p>Runs on the `local` profile because everything but /actuator/health needs a token
 * otherwise - including the schema, which is why the Swagger UI is a development aid.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "test", "local" })
class OpenApiDocumentTest {

    @MockitoBean
    private DocumentAiClient aiClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesTheSchemaWithAllFourEndpoints() {
        JsonNode schema = schema();

        assertThat(schema.at("/info/title").asText()).isEqualTo("doc-ai");
        assertThat(felder(schema.at("/paths"))).containsExactlyInAnyOrder(
                "/api/v1/klassifikation",
                "/api/v1/extraktion/krankenstand",
                "/api/v1/extraktion/zeitbestaetigung",
                "/api/v1/extraktion/kompetenzprofil");
    }

    @Test
    void givesEveryEndpointAnExampleForItsSuccessResponse() {
        JsonNode paths = schema().at("/paths");

        felder(paths).forEach(pfad -> {
            JsonNode beispiele = paths.at("/" + pfad.replace("/", "~1")
                    + "/post/responses/200/content/application~1json/examples");
            assertThat(beispiele.isMissingNode())
                    .withFailMessage("no 200 example for %s", pfad).isFalse();
            assertThat(felder(beispiele))
                    .withFailMessage("empty 200 examples for %s", pfad).isNotEmpty();
        });
    }

    /** The errors of SPEC §6 are documented as problem+json, not as a bare status code. */
    @Test
    void documentsTheProblemResponses() {
        JsonNode antworten = schema().at("/paths/~1api~1v1~1extraktion~1krankenstand/post/responses");

        assertThat(felder(antworten))
                .contains("200", "400", "413", "415", "422", "502", "503", "504");
        assertThat(felder(antworten.at("/422/content")))
                .containsExactly("application/problem+json");
    }

    /** SPEC §7: /api/** needs the app role, and a schema that hides that sends callers into 401s. */
    @Test
    void declaresTheBearerScheme() {
        JsonNode schema = schema();

        assertThat(schema.at("/components/securitySchemes/entraBearer/scheme").asText())
                .isEqualTo("bearer");
        assertThat(schema.at("/components/securitySchemes/entraBearer/bearerFormat").asText())
                .isEqualTo("JWT");
        assertThat(schema.at("/security/0/entraBearer").isArray()).isTrue();
    }

    @Test
    void servesTheSwaggerUi() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static List<String> felder(JsonNode knoten) {
        List<String> namen = new ArrayList<>();
        knoten.fieldNames().forEachRemaining(namen::add);
        return namen;
    }

    private JsonNode schema() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/v3/api-docs", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
