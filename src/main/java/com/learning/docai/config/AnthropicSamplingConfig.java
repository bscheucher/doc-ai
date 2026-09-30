package com.learning.docai.config;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Claude Sonnet 5 and the rest of that generation dropped the sampling parameters. A request
 * carrying {@code temperature} is answered
 * {@code 400 invalid_request_error - `temperature` is deprecated for this model}, so every
 * extraction fails before the model ever sees the document. SPEC §2 asks for temperature 0;
 * that is no longer expressible, and a model that cannot be sampled is deterministic enough
 * for extraction without it.
 *
 * <p>Leaving the option out of application.yml does not do it. Spring AI's
 * {@code AnthropicChatProperties} seeds its options with
 * {@code AnthropicChatModel.DEFAULT_TEMPERATURE} (0.8) in the field initialiser, and an absent
 * - or empty - property leaves that default in place instead of binding null. The value has to
 * be cleared after binding and before the chat model reads the options, which is what this
 * post-processor does.
 *
 * <p>Only the anthropic profile: Ollama still accepts {@code temperature} and the ollama
 * profile still sets it to 0.
 */
@Slf4j
@Profile("anthropic")
@Configuration(proxyBeanMethods = false)
public class AnthropicSamplingConfig {

    /**
     * Static so the post-processor is created without instantiating the rest of this
     * configuration, and so it is in place before the chat model is built.
     */
    @Bean
    static BeanPostProcessor clearAnthropicTemperature() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof AnthropicChatProperties properties
                        && properties.getOptions().getTemperature() != null) {
                    properties.getOptions().setTemperature(null);
                    log.info("Removed the Anthropic temperature option: the model rejects it");
                }
                return bean;
            }
        };
    }
}
