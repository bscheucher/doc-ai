package com.learning.docai.config;

import javax.imageio.ImageIO;

import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * ImageIO caches its streams in {@code java.io.tmpdir} by default, so reading or writing an
 * image leaves document bytes on disk - and they survive a crash. Uploads are never
 * persisted (SPEC §5), so the disk cache is switched off for the whole process. The intake
 * service passes memory-backed streams explicitly; this also covers the ImageIO calls
 * PDFBox makes while decoding images embedded in a PDF.
 */
@Configuration
public class ImageIoConfig {

    @PostConstruct
    void disableDiskCache() {
        ImageIO.setUseCache(false);
    }
}
