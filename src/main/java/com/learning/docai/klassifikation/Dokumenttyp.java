package com.learning.docai.klassifikation;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Document classes endpoint 1 can report (SPEC §3.2). Anything not clearly assignable is
 * UNBEKANNT, which is what sets `manuellePruefung` - guessing is worse than handing over.
 */
public enum Dokumenttyp {

    KRANKENSTANDSBESTAETIGUNG,
    ZEITBESTAETIGUNG,
    UNBEKANNT;

    /**
     * The constants reach the model as the JSON schema's enum, but a model does not always
     * answer with one of them verbatim: lower case, quoted, or spelt with the umlaut
     * ("KRANKENSTANDSBESTÄTIGUNG") are the same answer and are accepted here. Anything else
     * becomes UNBEKANNT, which hands the document to a person - without this, Jackson would
     * reject the answer and the request would end as a 502 instead.
     */
    @JsonCreator
    public static Dokumenttyp fromModelAnswer(String answer) {
        if (answer == null) {
            return UNBEKANNT;
        }
        String normalised = normalise(answer);
        for (Dokumenttyp typ : values()) {
            if (typ.name().equals(normalised)) {
                return typ;
            }
        }
        return UNBEKANNT;
    }

    /** Upper case folds ß to SS by itself; the umlauts need the Austrian spelling rule. */
    private static String normalise(String answer) {
        return answer.toUpperCase(Locale.ROOT)
                .replace("Ä", "AE")
                .replace("Ö", "OE")
                .replace("Ü", "UE")
                .replaceAll("[^A-Z]", "");
    }
}
