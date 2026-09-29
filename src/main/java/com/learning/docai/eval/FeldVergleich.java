package com.learning.docai.eval;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Compares an extraction against a reference file, field by field (SPEC §12).
 *
 * <p>The reference may be a whole response envelope or just the `daten` object: an evaluator
 * usually writes the expected result by copying a good response and correcting it, and being
 * strict about which of the two it is would only produce empty comparisons.
 *
 * <p>Only the fields the reference names are compared. A reference that fixes two dates and
 * leaves the rest out asks about those two dates, not about everything else.
 */
final class FeldVergleich {

    private FeldVergleich() {
    }

    static Map<String, Boolean> vergleiche(ObjectMapper mapper, Object daten, JsonNode erwartet) {
        JsonNode soll = erwartet.has("daten") ? erwartet.get("daten") : erwartet;
        JsonNode ist = mapper.valueToTree(daten);

        Map<String, Boolean> treffer = new LinkedHashMap<>();
        soll.fieldNames().forEachRemaining(feld ->
                treffer.put(feld, gleich(soll.get(feld), ist.get(feld))));
        return treffer;
    }

    /**
     * Jackson's own equality, which compares whole subtrees, so a competency list counts as one
     * field and matches only when every row does. A field the extraction left out is a miss
     * unless the reference expects it to be null.
     */
    private static boolean gleich(JsonNode soll, JsonNode ist) {
        JsonNode links = soll == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : soll;
        JsonNode rechts = ist == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : ist;
        return links.equals(rechts) || (links.isNull() && rechts.isNull());
    }
}
