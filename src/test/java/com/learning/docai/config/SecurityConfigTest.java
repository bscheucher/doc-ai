package com.learning.docai.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.intake.TestDocuments;
import com.learning.docai.klassifikation.Dokumenttyp;
import com.learning.docai.klassifikation.KlassifikationErgebnis;

/**
 * The resource server of SPEC §7, per the test list of §11. The `test` profile alone does not
 * activate `local`, so this is the chain a deployment gets. Tokens are assembled by
 * spring-security-test, so the decoder - and with it the tenant - is never reached.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    private static final String API = "/api/v1/klassifikation";
    private static final String ROLE = "DocAi.Process";

    @MockitoBean
    private DocumentAiClient aiClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void refusesACallWithoutAToken() throws Exception {
        mockMvc.perform(multipart(API).file(pdf()))
                .andExpect(status().isUnauthorized());

        verify(aiClient, never()).extract(any(), any(), any());
    }

    @Test
    void refusesATokenWithoutTheRequiredRole() throws Exception {
        mockMvc.perform(multipart(API).file(pdf()).with(mitRollen("Irgendwas.Anderes")))
                .andExpect(status().isForbidden());

        verify(aiClient, never()).extract(any(), any(), any());
    }

    @Test
    void refusesATokenWithNoRolesClaimAtAll() throws Exception {
        mockMvc.perform(multipart(API).file(pdf()).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsATokenThatCarriesTheRole() throws Exception {
        when(aiClient.extract(any(), any(), eq(KlassifikationErgebnis.class))).thenReturn(
                new AiResult<>(new KlassifikationErgebnis(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG,
                        "OEGK-Formular mit Zeitraum der Arbeitsunfaehigkeit."),
                        "test", "test-modell", 1200, 42, 1500L));

        mockMvc.perform(multipart(API).file(pdf()).with(mitRollen(ROLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typ").value("KRANKENSTANDSBESTAETIGUNG"));
    }

    @Test
    void leavesTheHealthProbesOpenForThePlatform() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void securesTheRestOfTheActuator() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAnUnknownPathWithoutAToken() throws Exception {
        // `anyRequest().authenticated()`: nothing is reachable just because it is unmapped.
        mockMvc.perform(MockMvcRequestBuilders.get("/gibt-es-nicht"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The token carries the roles claim and the production converter turns it into
     * authorities - the postprocessor would otherwise invent its own.
     */
    private static RequestPostProcessor mitRollen(String... rollen) {
        return jwt().jwt(token -> token.claim("roles", List.of(rollen)))
                .authorities(new AppRoleAuthoritiesConverter());
    }

    private static MockMultipartFile pdf() throws Exception {
        return new MockMultipartFile("file", "dokument.pdf",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, TestDocuments.pdf(1));
    }
}
