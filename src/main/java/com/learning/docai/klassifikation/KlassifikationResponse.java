package com.learning.docai.klassifikation;

import com.learning.docai.api.Metadaten;

/**
 * Response of endpoint 1 (SPEC §3.2). Flat on purpose: unlike endpoints 2-4 there is nothing
 * to validate, so there is no `daten` wrapper and no `probleme` list.
 */
public record KlassifikationResponse(
        String requestId,
        Dokumenttyp typ,
        String begruendung,
        boolean manuellePruefung,
        Metadaten metadaten) {
}
