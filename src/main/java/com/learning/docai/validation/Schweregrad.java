package com.learning.docai.validation;

/**
 * Severity of a validation issue (SPEC §4). Both levels set `manuellePruefung`; the
 * difference is what a reviewer sees first, not whether the document is reviewed.
 */
public enum Schweregrad {

    FEHLER,
    WARNUNG
}
