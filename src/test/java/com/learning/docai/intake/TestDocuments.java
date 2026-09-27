package com.learning.docai.intake;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Synthetic documents for intake tests. No fixture files on disk (SPEC §11).
 */
public final class TestDocuments {

    private TestDocuments() {
    }

    public static byte[] pdf(int pageCount) throws IOException {
        try (PDDocument document = newDocument(pageCount)) {
            return save(document);
        }
    }

    /**
     * A one-page PDF with the given page size in points. Used for the extremes: PDF allows
     * up to 14400 pt (200 inch) per edge, which at the configured DPI would be a 30000 px
     * raster from a file of a few hundred bytes.
     */
    public static byte[] pdfWithPageSize(float widthPoints, float heightPoints)
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(widthPoints, heightPoints)));
            return save(document);
        }
    }

    /** Encrypted with a user password: PDFBox cannot open it at all. */
    public static byte[] pdfWithUserPassword() throws IOException {
        try (PDDocument document = newDocument(1)) {
            document.protect(new StandardProtectionPolicy("owner-pw", "user-pw",
                    new AccessPermission()));
            return save(document);
        }
    }

    /** Owner password only: PDFBox opens it, but it is still encrypted. */
    public static byte[] pdfWithOwnerPasswordOnly() throws IOException {
        try (PDDocument document = newDocument(1)) {
            document.protect(new StandardProtectionPolicy("owner-pw", "", new AccessPermission()));
            return save(document);
        }
    }

    public static byte[] png(int width, int height) throws IOException {
        return encode(image(width, height), "png");
    }

    /**
     * A PNG that declares huge dimensions but stays tiny on the wire - a uniform image
     * deflates to almost nothing. Written by streaming the scanlines, so the test itself
     * never holds the full raster.
     */
    public static byte[] oversizedPng(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, width);
        writeInt(header, height);
        header.write(new byte[] { 8, 0, 0, 0, 0 }); // 8-bit greyscale, no interlace
        writeChunk(out, "IHDR", header.toByteArray());

        writeChunk(out, "IDAT", deflateBlankScanlines(width, height));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    public static byte[] jpeg(int width, int height) throws IOException {
        return encode(image(width, height), "jpg");
    }

    /**
     * A JPEG with an EXIF APP1 segment carrying the given orientation tag (1-8).
     */
    public static byte[] jpegWithOrientation(int width, int height, int orientation) throws IOException {
        byte[] jpeg = jpeg(width, height);
        byte[] app1 = exifApp1Segment(orientation);

        byte[] result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
        return result;
    }

    private static byte[] exifApp1Segment(int orientation) {
        // Big-endian TIFF with a single IFD0 entry for tag 0x0112 (orientation).
        byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1,
                (byte) ((orientation >> 8) & 0xFF), (byte) (orientation & 0xFF), 0, 0,
                0, 0, 0, 0 };

        int segmentLength = 2 + 6 + tiff.length;
        byte[] segment = new byte[2 + segmentLength];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) ((segmentLength >> 8) & 0xFF);
        segment[3] = (byte) (segmentLength & 0xFF);
        System.arraycopy(new byte[] { 'E', 'x', 'i', 'f', 0, 0 }, 0, segment, 4, 6);
        System.arraycopy(tiff, 0, segment, 10, tiff.length);
        return segment;
    }

    private static byte[] deflateBlankScanlines(int width, int height) throws IOException {
        byte[] scanline = new byte[width + 1]; // filter byte 0 + white-ish row
        Arrays.fill(scanline, 1, scanline.length, (byte) 0xF0);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            for (int row = 0; row < height; row++) {
                deflater.write(scanline);
            }
        }
        return compressed.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data)
            throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static PDDocument newDocument(int pageCount) throws IOException {
        PDDocument document = new PDDocument();
        for (int page = 0; page < pageCount; page++) {
            PDPage pdPage = new PDPage(PDRectangle.A4);
            document.addPage(pdPage);
            try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Seite " + (page + 1));
                content.endText();
            }
        }
        return document;
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.drawString("Testdokument", 10, Math.min(20, height - 1));
        graphics.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
