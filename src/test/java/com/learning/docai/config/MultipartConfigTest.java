package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Uploads are processed in memory only (SPEC §5, CLAUDE.md hard rules). Spring Boot's default
 * file-size-threshold of 0 bytes makes Tomcat spool every part to {@code java.io.tmpdir}
 * before any of our code runs, which would leave scans on disk - and they survive a crash.
 */
@SpringBootTest
class MultipartConfigTest {

    @Autowired
    private MultipartProperties multipart;

    @Test
    void neverSpoolsAnAcceptedUploadToDisk() {
        assertThat(multipart.getFileSizeThreshold().toBytes())
                .isGreaterThanOrEqualTo(multipart.getMaxFileSize().toBytes());
    }
}
