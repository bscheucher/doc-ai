package com.learning.docai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocAiApplication.class, args);
    }

}
