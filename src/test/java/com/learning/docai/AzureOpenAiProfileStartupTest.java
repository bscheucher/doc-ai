package com.learning.docai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The azure-openai profile is the default and must start without a reachable Azure OpenAI
 * resource: the client connects on the first call, not at startup.
 */
@SpringBootTest(properties = {
        "spring.ai.azure.openai.endpoint=https://doc-ai-test.openai.azure.com/",
        "spring.ai.azure.openai.api-key=test-key-not-used"
})
@ActiveProfiles("azure-openai")
class AzureOpenAiProfileStartupTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ChatModel chatModel;

    @Test
    void isTheDefaultProfile() {
        // Tests name their profile explicitly, so this reads the setting rather than relying on it.
        assertThat(environment.getProperty("spring.profiles.default")).isEqualTo("azure-openai");
    }

    @Test
    void startsWithAzureOpenAiWithoutAReachableResource() {
        assertThat(chatModel).isInstanceOf(AzureOpenAiChatModel.class);
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("azure-openai");
        assertThat(environment.getProperty("spring.ai.azure.openai.chat.options.deployment-name"))
                .isEqualTo("gpt-4.1");
    }
}
