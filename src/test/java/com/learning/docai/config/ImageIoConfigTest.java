package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Uploads must never touch the disk, and the ImageIO default would cache streams in
 * {@code java.io.tmpdir} - inside PDFBox as well as in our own code.
 */
@SpringBootTest
class ImageIoConfigTest {

    @Test
    void disablesTheImageIoDiskCache() {
        assertThat(ImageIO.getUseCache()).isFalse();
    }
}
