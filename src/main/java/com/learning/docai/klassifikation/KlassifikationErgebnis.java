package com.learning.docai.klassifikation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What the model fills in (SPEC §3.2). The descriptions travel to the model as the JSON schema
 * for structured output, so they are instructions, not documentation for us.
 */
public record KlassifikationErgebnis(

        @JsonPropertyDescription("""
                Art des Dokuments. KRANKENSTANDSBESTAETIGUNG bei Krankmeldung oder \
                Arbeitsunfaehigkeitsmeldung, ZEITBESTAETIGUNG bei Bestaetigung einer \
                Anwesenheit zu einem Termin, UNBEKANNT wenn keine Art klar zutrifft.""")
        Dokumenttyp typ,

        @JsonPropertyDescription("""
                Ein kurzer Satz auf Deutsch, der die Einordnung begruendet, ohne Diagnose, \
                ohne Krankheitsbezeichnung und ohne Personennamen.""")
        String begruendung) {
}
