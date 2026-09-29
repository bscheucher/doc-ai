package com.learning.docai.eval;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * A file on disk presented as a {@link MultipartFile}, so the evaluation tool (SPEC §12) can
 * drive the very same services the endpoints use instead of a second code path that would drift
 * from them.
 *
 * <p>The bytes are read once into memory, like an upload: the tool processes documents of the
 * same size as the API, and intake needs the content twice (type detection, then rendering).
 */
final class DateiMultipartFile implements MultipartFile {

    private final String name;
    private final byte[] inhalt;

    private DateiMultipartFile(String name, byte[] inhalt) {
        this.name = name;
        this.inhalt = inhalt;
    }

    static DateiMultipartFile von(Path datei) throws IOException {
        return new DateiMultipartFile(datei.getFileName().toString(), Files.readAllBytes(datei));
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return StringUtils.cleanPath(name);
    }

    @Override
    public String getContentType() {
        // Intake decides by magic bytes (SPEC §5), so an honest "unknown" is better than a guess.
        return null;
    }

    @Override
    public boolean isEmpty() {
        return inhalt.length == 0;
    }

    @Override
    public long getSize() {
        return inhalt.length;
    }

    @Override
    public byte[] getBytes() {
        return inhalt;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(inhalt);
    }

    /**
     * Never used: SPEC §5 forbids writing an upload to disk, and the evaluation tool reads its
     * input from there anyway.
     */
    @Override
    public void transferTo(java.io.File ziel) {
        throw new UnsupportedOperationException("Uploads are never written to disk (SPEC §5)");
    }
}
