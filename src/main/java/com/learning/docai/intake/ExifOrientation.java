package com.learning.docai.intake;

import java.awt.image.BufferedImage;

/**
 * Minimal EXIF orientation support for JPEG (SPEC §2 step 1).
 * Only tag 0x0112 in IFD0 is read; anything unparsable is treated as "normal".
 */
final class ExifOrientation {

    static final int NORMAL = 1;

    private static final int APP1 = 0xE1;
    private static final int SOS = 0xDA;
    private static final int EOI = 0xD9;
    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {
    }

    /**
     * Reads the orientation value (1-8) from a JPEG byte stream, or 1 if absent.
     */
    static int read(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) {
            return NORMAL;
        }
        int i = 2;
        while (i + 4 <= jpeg.length) {
            if (u8(jpeg, i) != 0xFF) {
                return NORMAL;
            }
            int marker = u8(jpeg, i + 1);
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                i += 2;
                continue;
            }
            if (marker == SOS || marker == EOI) {
                return NORMAL;
            }
            int segmentLength = (u8(jpeg, i + 2) << 8) | u8(jpeg, i + 3);
            if (segmentLength < 2 || i + 2 + segmentLength > jpeg.length) {
                return NORMAL;
            }
            if (marker == APP1 && isExifHeader(jpeg, i + 4)) {
                return readTiffOrientation(jpeg, i + 10);
            }
            i += 2 + segmentLength;
        }
        return NORMAL;
    }

    /**
     * Applies an orientation to an image, returning a new image when a transform is needed.
     */
    static BufferedImage apply(BufferedImage source, int orientation) {
        if (orientation <= NORMAL || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapAxes = orientation >= 5;
        BufferedImage target = new BufferedImage(
                swapAxes ? height : width, swapAxes ? width : height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> { targetX = width - 1 - x; targetY = y; }
                    case 3 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
                    case 4 -> { targetX = x; targetY = height - 1 - y; }
                    case 5 -> { targetX = y; targetY = x; }
                    case 6 -> { targetX = height - 1 - y; targetY = x; }
                    case 7 -> { targetX = height - 1 - y; targetY = width - 1 - x; }
                    case 8 -> { targetX = y; targetY = width - 1 - x; }
                    default -> { targetX = x; targetY = y; }
                }
                target.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return target;
    }

    private static boolean isExifHeader(byte[] bytes, int offset) {
        return offset + 6 <= bytes.length
                && bytes[offset] == 'E' && bytes[offset + 1] == 'x' && bytes[offset + 2] == 'i'
                && bytes[offset + 3] == 'f' && bytes[offset + 4] == 0 && bytes[offset + 5] == 0;
    }

    private static int readTiffOrientation(byte[] bytes, int tiffStart) {
        if (tiffStart + 8 > bytes.length) {
            return NORMAL;
        }
        boolean littleEndian;
        if (bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return NORMAL;
        }
        if (u16(bytes, tiffStart + 2, littleEndian) != 42) {
            return NORMAL;
        }
        int ifdOffset = u32(bytes, tiffStart + 4, littleEndian);
        int ifdStart = tiffStart + ifdOffset;
        if (ifdOffset < 8 || ifdStart + 2 > bytes.length) {
            return NORMAL;
        }
        int entryCount = u16(bytes, ifdStart, littleEndian);
        for (int entry = 0; entry < entryCount; entry++) {
            int entryStart = ifdStart + 2 + entry * 12;
            if (entryStart + 12 > bytes.length) {
                return NORMAL;
            }
            if (u16(bytes, entryStart, littleEndian) == ORIENTATION_TAG) {
                int value = u16(bytes, entryStart + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }

    private static int u8(byte[] bytes, int index) {
        return bytes[index] & 0xFF;
    }

    private static int u16(byte[] bytes, int index, boolean littleEndian) {
        int first = u8(bytes, index);
        int second = u8(bytes, index + 1);
        return littleEndian ? (second << 8) | first : (first << 8) | second;
    }

    private static int u32(byte[] bytes, int index, boolean littleEndian) {
        int a = u8(bytes, index);
        int b = u8(bytes, index + 1);
        int c = u8(bytes, index + 2);
        int d = u8(bytes, index + 3);
        return littleEndian
                ? (d << 24) | (c << 16) | (b << 8) | a
                : (a << 24) | (b << 16) | (c << 8) | d;
    }
}
