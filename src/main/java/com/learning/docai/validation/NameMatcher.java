package com.learning.docai.validation;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Name comparison per SPEC §4.6. Scanned documents and the ibosNG record disagree about
 * diacritics, hyphens and double names far more often than about who the person is, so the
 * comparison is deliberately forgiving: it only has to be good enough to decide whether a
 * reviewer needs to look.
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    /**
     * True if both names are the same after normalising, or if one contains the other -
     * "Fischer" against "Fischer-Meier" is the same person under a double name, while
     * "Fischer" against "Fisher-Meier" is not. A name that is there but survives normalising
     * as nothing does not match: see below.
     */
    public static boolean matches(String links, String rechts) {
        if (fehlt(links) || fehlt(rechts)) {
            // Nothing to compare: the missing side is somebody else's rule (PFLICHTFELD_FEHLT).
            return true;
        }
        String a = normalise(links);
        String b = normalise(rechts);
        if (a.isEmpty() || b.isEmpty()) {
            // Written, but nothing left to compare with: another script, or OCR noise such as
            // "----". Treating that as a match would wave through somebody else's document,
            // and no other rule would catch it, so it goes to a reviewer.
            return false;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static boolean fehlt(String name) {
        return name == null || name.isBlank();
    }

    /**
     * Decomposes to base letters and drops everything else: "Müller-Lüdenscheid" and
     * "Muller Ludenscheid" both become "mullerludenscheid". The dotless Turkish "ı" has no
     * decomposition, so it is mapped by hand, and "ß" is replaced outright - after the lower
     * casing, so that the capital "ẞ" is folded too.
     */
    static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("ß", "ss")
                .replace("ı", "i");

        return decomposed.replaceAll("[^a-z]", "");
    }
}
