package com.learning.docai.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.config.IntakeProperties;

class DocumentIntakeServiceTest {

    private static final IntakeProperties PROPERTIES = new IntakeProperties(150, 1600, 5);

    private final DocumentIntakeService service = new DocumentIntakeService(PROPERTIES);

    @Test
    void rendersSinglePagePdf() throws IOException {
        PageImages pages = service.toPageImages(TestDocuments.pdf(1));

        assertThat(pages.pageCount()).isEqualTo(1);
        assertThat(DocumentType.detect(pages.pages().get(0))).contains(DocumentType.PNG);
    }

    @Test
    void rendersEveryPageOfAMultiPagePdf() throws IOException {
        PageImages pages = service.toPageImages(TestDocuments.pdf(3));

        assertThat(pages.pageCount()).isEqualTo(3);
        assertThat(pages.pages()).allSatisfy(
                page -> assertThat(DocumentType.detect(page)).contains(DocumentType.PNG));
    }

    @Test
    void rejectsPdfWithTooManyPages() throws IOException {
        byte[] pdf = TestDocuments.pdf(PROPERTIES.maxPages() + 1);

        assertThatThrownBy(() -> service.toPageImages(pdf))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.UNREADABLE_DOCUMENT);
    }

    @Test
    void rejectsPdfProtectedByUserPassword() throws IOException {
        byte[] pdf = TestDocuments.pdfWithUserPassword();

        assertThatThrownBy(() -> service.toPageImages(pdf))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.UNREADABLE_DOCUMENT);
    }

    @Test
    void rejectsEncryptedPdfEvenWhenItCanBeOpened() throws IOException {
        byte[] pdf = TestDocuments.pdfWithOwnerPasswordOnly();

        assertThatThrownBy(() -> service.toPageImages(pdf))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.UNREADABLE_DOCUMENT);
    }

    @Test
    void rejectsCorruptPdf() {
        byte[] corrupt = "%PDF-1.7 not really a pdf".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> service.toPageImages(corrupt))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.UNREADABLE_DOCUMENT);
    }

    @Test
    void rejectsUnsupportedTypeByMagicBytes() {
        byte[] gif = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00 };

        assertThatThrownBy(() -> service.toPageImages(gif))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.UNSUPPORTED_TYPE);
    }

    @Test
    void rejectsDeclaredPngThatCannotBeDecoded() {
        byte[] broken = new byte[64];
        System.arraycopy(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A },
                0, broken, 0, 8);

        assertThatThrownBy(() -> service.toPageImages(broken))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.UNSUPPORTED_TYPE);
    }

    @Test
    void rejectsMissingFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "leer.pdf",
                "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.toPageImages(empty))
                .isInstanceOf(DocAiException.class)
                .extracting(ex -> ((DocAiException) ex).errorType())
                .isEqualTo(ErrorType.MISSING_FILE);
    }

    @Test
    void keepsSmallImagesAtTheirOriginalSize() throws IOException {
        PageImages pages = service.toPageImages(TestDocuments.png(800, 600));

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(result.getWidth()).isEqualTo(800);
        assertThat(result.getHeight()).isEqualTo(600);
    }

    @Test
    void scalesOversizedImagesToTheLongestEdgeLimit() throws IOException {
        PageImages pages = service.toPageImages(TestDocuments.png(4000, 1000));

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(Math.max(result.getWidth(), result.getHeight()))
                .isEqualTo(PROPERTIES.maxImageEdge());
        assertThat(result.getWidth()).isEqualTo(1600);
        assertThat(result.getHeight()).isEqualTo(400);
    }

    @Test
    void appliesExifOrientationToJpeg() throws IOException {
        // Orientation 6 = rotate 90° clockwise, so the edges swap.
        PageImages pages = service.toPageImages(TestDocuments.jpegWithOrientation(200, 100, 6));

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(200);
    }

    @Test
    void leavesJpegWithNormalOrientationUnrotated() throws IOException {
        PageImages pages = service.toPageImages(TestDocuments.jpegWithOrientation(200, 100, 1));

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(result.getWidth()).isEqualTo(200);
        assertThat(result.getHeight()).isEqualTo(100);
    }

    @Test
    void acceptsJpegWithoutExifData() throws IOException {
        PageImages pages = service.toPageImages(TestDocuments.jpeg(300, 200));

        assertThat(pages.pageCount()).isEqualTo(1);
        assertThat(decode(pages.pages().get(0)).getWidth()).isEqualTo(300);
    }

    @Test
    void rendersPdfPagesAtTheConfiguredDpi() throws IOException {
        // A4 at 150 dpi is roughly 1240x1754, so the height hits the 1600 px limit.
        PageImages pages = service.toPageImages(TestDocuments.pdf(1));

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(Math.max(result.getWidth(), result.getHeight()))
                .isEqualTo(PROPERTIES.maxImageEdge());
    }

    @Test
    void clampsRenderDpiForOversizedPdfPages() throws IOException {
        // 14400 pt is the largest page PDF allows; at 150 dpi that would be 30000 px square,
        // 3.6 GB of raster from a file of a few hundred bytes.
        byte[] pdf = TestDocuments.pdfWithPageSize(14400f, 14400f);
        assertThat(pdf.length).isLessThan(2000);

        PageImages pages = service.toPageImages(pdf);

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(Math.max(result.getWidth(), result.getHeight()))
                .isEqualTo(PROPERTIES.maxImageEdge());
    }

    @Test
    void decodesOversizedImagesWithoutAllocatingTheFullRaster() throws IOException {
        // 20000 x 20000 is 400 MP - 1.6 GB of raster from well under a megabyte on the wire.
        byte[] png = TestDocuments.oversizedPng(20000, 20000);
        assertThat(png.length).isLessThan(1_000_000);

        PageImages pages = service.toPageImages(png);

        BufferedImage result = decode(pages.pages().get(0));
        assertThat(Math.max(result.getWidth(), result.getHeight()))
                .isEqualTo(PROPERTIES.maxImageEdge());
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
