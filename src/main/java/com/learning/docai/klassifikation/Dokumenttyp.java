package com.learning.docai.klassifikation;

/**
 * Document classes endpoint 1 can report (SPEC §3.2). Anything not clearly assignable is
 * UNBEKANNT, which is what sets `manuellePruefung` - guessing is worse than handing over.
 */
public enum Dokumenttyp {

    KRANKENSTANDSBESTAETIGUNG,
    ZEITBESTAETIGUNG,
    UNBEKANNT
}
