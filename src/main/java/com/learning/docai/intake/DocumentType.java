package com.learning.docai.intake;

import java.util.Optional;

/**
 * Accepted upload types (SPEC §5), detected by magic bytes rather than Content-Type.
 */
public enum DocumentType {

    PDF,
    PNG,
    JPEG;

    private static final byte[] PDF_MAGIC = { 0x25, 0x50, 0x44, 0x46 };
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

    public static Optional<DocumentType> detect(byte[] bytes) {
        if (startsWith(bytes, PDF_MAGIC)) {
            return Optional.of(PDF);
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        if (startsWith(bytes, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes == null || bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
