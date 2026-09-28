package com.learning.docai.kompetenz;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * One row of a competency table (SPEC §3.5). Both components are nullable on purpose: a row
 * the model could only half read is reported as an issue (§4.4), not silently dropped.
 */
public record Kompetenz(

        @JsonPropertyDescription("""
                Bezeichnung der Kompetenz, genau so wie sie in der Zeile steht. \
                null, wenn in der Zeile keine Bezeichnung steht.""")
        String bezeichnung,

        @JsonPropertyDescription("""
                Wert der Kompetenz als ganze Zahl von 0 bis 100, aus derselben Zeile wie die \
                Bezeichnung. null, wenn in der Zeile kein Wert steht.""")
        Integer score) {

    /** A row with neither of the two carries nothing; §3.5 asks the model to leave it out. */
    boolean istLeer() {
        return (bezeichnung == null || bezeichnung.isBlank()) && score == null;
    }
}
