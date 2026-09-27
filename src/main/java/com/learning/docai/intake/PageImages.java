package com.learning.docai.intake;

import java.util.List;

/**
 * Pages of one document as PNG bytes, in document order (SPEC §2 step 1).
 */
public record PageImages(List<byte[]> pages) {

    public PageImages {
        pages = List.copyOf(pages);
    }

    public int pageCount() {
        return pages.size();
    }
}
