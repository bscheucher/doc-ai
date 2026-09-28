package com.learning.docai.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.learning.docai.ai.DocumentAiClient;

/**
 * The health probes are permitted by endpoint, not by path (SPEC §7). Moving the actuator
 * must not quietly turn the platform's unauthenticated readiness probe into a 401.
 */
@SpringBootTest(properties = "management.endpoints.web.base-path=/manage")
@AutoConfigureMockMvc
class MovedActuatorSecurityTest {

    @MockitoBean
    private DocumentAiClient aiClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void keepsTheHealthProbesOpenWhereverTheActuatorSits() throws Exception {
        mockMvc.perform(get("/manage/health")).andExpect(status().isOk());
        mockMvc.perform(get("/manage/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void stillSecuresTheRestOfTheMovedActuator() throws Exception {
        mockMvc.perform(get("/manage/metrics")).andExpect(status().isUnauthorized());
    }
}
