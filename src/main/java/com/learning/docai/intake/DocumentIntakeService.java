package com.learning.docai.intake;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.config.IntakeProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Validates an upload (SPEC §5) and converts it to page images (SPEC §2 step 1).
 * Nothing is written to disk; the document exists only as bytes in memory.
 *
 * <p>Every raster is bounded <em>before</em> it is allocated: a page's own geometry decides
 * the render DPI, and an image is decoded subsampled. Without that a few hundred bytes of
 * valid input (a 14400 pt page, a PNG declaring huge dimensions) would allocate gigabytes.
 */
@Slf4j
@Service
public class DocumentIntakeService {

    private static final float POINTS_PER_INCH = 72f;

    private final IntakeProperties properties;

    public DocumentIntakeService(IntakeProperties properties) {
        this.properties = properties;
    }

    public PageImages toPageImages(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocAiException(ErrorType.MISSING_FILE);
        }
        try {
            return toPageImages(file.getBytes());
        } catch (IOException e) {
            throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT, e);
        }
    }

    public PageImages toPageImages(byte[] bytes) {
        DocumentType type = DocumentType.detect(bytes)
                .orElseThrow(() -> new DocAiException(ErrorType.UNSUPPORTED_TYPE));

        PageImages pageImages = switch (type) {
            case PDF -> new PageImages(renderPdf(bytes));
            case PNG, JPEG -> new PageImages(List.of(renderImage(bytes, type)));
        };
        log.debug("Intake complete: type={} pages={}", type, pageImages.pageCount());
        return pageImages;
    }

    private List<byte[]> renderPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT);
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0 || pageCount > properties.maxPages()) {
                log.warn("Rejected PDF: pages={} max={}", pageCount, properties.maxPages());
                throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> pages = new ArrayList<>(pageCount);
            for (int page = 0; page < pageCount; page++) {
                float dpi = renderDpiFor(document.getPage(page));
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                pages.add(encodePng(scaleToLimit(image)));
            }
            return pages;
        } catch (IOException e) {
            throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT, e);
        }
    }

    /**
     * The configured DPI, lowered where a page is large enough that rendering at it would
     * exceed {@code docai.intake.max-image-edge}. Those pixels would be thrown away by
     * {@link #scaleToLimit} anyway, so nothing is lost - but allocating them first is what
     * lets a tiny PDF with a 14400 pt page exhaust the heap.
     */
    private float renderDpiFor(PDPage page) {
        PDRectangle box = page.getCropBox();
        float longestEdgePoints = Math.max(box.getWidth(), box.getHeight());
        if (!Float.isFinite(longestEdgePoints) || longestEdgePoints <= 0f) {
            log.warn("Rejected PDF: unusable page geometry");
            throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT);
        }
        // One pixel of headroom: PDFBox floors the pixel size, so aiming exactly at the
        // limit can land a pixel short. scaleToLimit trims the overshoot.
        float dpiForMaxEdge =
                (properties.maxImageEdge() + 1) * POINTS_PER_INCH / longestEdgePoints;
        return Math.min(properties.renderDpi(), dpiForMaxEdge);
    }

    private byte[] renderImage(byte[] bytes, DocumentType type) {
        BufferedImage image = scaleToLimit(decodeImage(bytes));
        if (type == DocumentType.JPEG) {
            image = ExifOrientation.apply(image, ExifOrientation.read(bytes));
        }
        return encodePng(image);
    }

    /**
     * Decodes subsampled so the raster stays within roughly twice
     * {@code docai.intake.max-image-edge} however large the source claims to be;
     * {@link #scaleToLimit} then does the quality-preserving step down. Reading the header
     * first keeps a decompression bomb from allocating its full raster.
     */
    private BufferedImage decodeImage(byte[] bytes) {
        try (ImageInputStream input =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new DocAiException(ErrorType.UNSUPPORTED_TYPE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int subsampling = subsamplingFor(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                if (decoded == null) {
                    throw new DocAiException(ErrorType.UNSUPPORTED_TYPE);
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new DocAiException(ErrorType.UNSUPPORTED_TYPE, e);
        }
    }

    private int subsamplingFor(int width, int height) {
        int longestEdge = Math.max(width, height);
        if (longestEdge <= 0) {
            log.warn("Rejected image: unusable dimensions");
            throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT);
        }
        return Math.max(1, longestEdge / properties.maxImageEdge());
    }

    /**
     * Scales so the longest edge is at most {@code docai.intake.max-image-edge} and
     * normalises to RGB. Smaller images are converted but never upscaled.
     */
    private BufferedImage scaleToLimit(BufferedImage source) {
        int maxEdge = properties.maxImageEdge();
        int longestEdge = Math.max(source.getWidth(), source.getHeight());
        double factor = longestEdge > maxEdge ? (double) maxEdge / longestEdge : 1.0;

        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * Encodes through an explicit in-memory stream: the ImageIO default would spill the
     * page to {@code java.io.tmpdir}.
     */
    private byte[] encodePng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOut = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", imageOut)) {
                throw new DocAiException(ErrorType.INTERNAL_ERROR);
            }
        } catch (IOException e) {
            throw new DocAiException(ErrorType.INTERNAL_ERROR, e);
        }
        return out.toByteArray();
    }
}
