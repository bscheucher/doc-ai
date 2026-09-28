package com.learning.docai.config;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;

/**
 * SPEC §3.4 promises times as HH:mm; Jackson writes LocalTime as HH:mm:ss by default.
 *
 * <p>This only affects what we send: the model's answer is parsed by Spring AI's own
 * ObjectMapper, which this customizer never touches, so a model that writes "10:30:00" is
 * still understood.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer uhrzeitOhneSekunden() {
        return builder -> builder.serializerByType(LocalTime.class, new LocalTimeSerializer(HH_MM));
    }
}
