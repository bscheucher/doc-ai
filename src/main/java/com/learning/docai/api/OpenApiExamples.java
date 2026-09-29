package com.learning.docai.api;

/**
 * Response examples for the OpenAPI document (SPEC §10). Constants rather than annotation
 * literals so the controllers stay HTTP mapping plus metadata, with nothing to read past.
 *
 * <p>All data here is invented. The Versicherungsnummern carry a correct check digit, so an
 * example pasted into a request does not come back with SVNR_PRUEFZIFFER_UNGUELTIG and send
 * the reader looking for a fault that is in the example.
 */
public final class OpenApiExamples {

    private OpenApiExamples() {
    }

    public static final String KLASSIFIKATION_200 = """
            {
              "requestId": "3f1c9a52-1b7e-4f1e-9a1d-2c1b8e4f7a10",
              "typ": "KRANKENSTANDSBESTAETIGUNG",
              "begruendung": "OEGK-Formular Arbeitsunfaehigkeitsmeldung mit Zeitraum der Arbeitsunfaehigkeit.",
              "manuellePruefung": false,
              "metadaten": {
                "provider": "anthropic",
                "modell": "claude-sonnet-5",
                "seiten": 1,
                "inputTokens": 1834,
                "outputTokens": 156,
                "dauerMs": 3120
              }
            }""";

    public static final String KRANKENSTAND_200 = """
            {
              "requestId": "3f1c9a52-1b7e-4f1e-9a1d-2c1b8e4f7a10",
              "dokumenttyp": "KRANKENSTAND",
              "daten": {
                "vorname": "Max",
                "familienname": "Mustermann",
                "versicherungsnummer": "1238010190",
                "krankenstandsadresse": "Musterweg 1, 1010 Wien",
                "arbeitsunfaehigVon": "2026-09-21",
                "letzterTagArbeitsunfaehigkeit": "2026-09-25",
                "ausstellungsdatum": "2026-09-21"
              },
              "probleme": [],
              "manuellePruefung": false,
              "metadaten": {
                "provider": "anthropic",
                "modell": "claude-sonnet-5",
                "seiten": 1,
                "inputTokens": 1834,
                "outputTokens": 156,
                "dauerMs": 3120
              }
            }""";

    /** The same endpoint with a finding: the caller gets the document and a reason to look. */
    public static final String KRANKENSTAND_200_MIT_PROBLEM = """
            {
              "requestId": "3f1c9a52-1b7e-4f1e-9a1d-2c1b8e4f7a10",
              "dokumenttyp": "KRANKENSTAND",
              "daten": {
                "vorname": "Max",
                "familienname": "Mustermann",
                "versicherungsnummer": "1238010190",
                "krankenstandsadresse": null,
                "arbeitsunfaehigVon": "2026-09-21",
                "letzterTagArbeitsunfaehigkeit": null,
                "ausstellungsdatum": "2026-09-21"
              },
              "probleme": [
                {
                  "feld": "letzterTagArbeitsunfaehigkeit",
                  "code": "ENDE_FEHLT",
                  "schweregrad": "WARNUNG",
                  "meldung": "Letzter Tag der Arbeitsunfaehigkeit fehlt"
                }
              ],
              "manuellePruefung": true,
              "metadaten": {
                "provider": "anthropic",
                "modell": "claude-sonnet-5",
                "seiten": 1,
                "inputTokens": 1790,
                "outputTokens": 148,
                "dauerMs": 2980
              }
            }""";

    public static final String ZEITBESTAETIGUNG_200 = """
            {
              "requestId": "3f1c9a52-1b7e-4f1e-9a1d-2c1b8e4f7a10",
              "dokumenttyp": "ZEITBESTAETIGUNG",
              "daten": {
                "vorname": "Anna",
                "familienname": "Beispiel",
                "datumVon": "2026-09-22",
                "datumBis": null,
                "zeitVon": "09:00",
                "zeitBis": "11:30",
                "ausstellungsdatum": "2026-09-22",
                "grundDerAbwesenheit": "Arzttermin",
                "aussteller": "Ordination Dr. Muster"
              },
              "probleme": [],
              "manuellePruefung": false,
              "metadaten": {
                "provider": "anthropic",
                "modell": "claude-sonnet-5",
                "seiten": 1,
                "inputTokens": 1520,
                "outputTokens": 132,
                "dauerMs": 2740
              }
            }""";

    public static final String KOMPETENZPROFIL_200 = """
            {
              "requestId": "3f1c9a52-1b7e-4f1e-9a1d-2c1b8e4f7a10",
              "dokumenttyp": "KOMPETENZPROFIL",
              "daten": {
                "vorname": "Johanna",
                "nachname": "Beispielhuber",
                "geburtsdatum": "1985-07-22",
                "versicherungsnummer": "7895220785",
                "fachlich": [
                  { "bezeichnung": "Buchhaltung", "score": 80 },
                  { "bezeichnung": "Microsoft Excel", "score": 65 }
                ],
                "ueberfachlich": [
                  { "bezeichnung": "Teamfaehigkeit", "score": 90 }
                ],
                "zertifikate": [ "ECDL Advanced" ],
                "interessengebiete": [ "Rechnungswesen" ]
              },
              "probleme": [],
              "manuellePruefung": false,
              "metadaten": {
                "provider": "anthropic",
                "modell": "claude-sonnet-5",
                "seiten": 2,
                "inputTokens": 3210,
                "outputTokens": 410,
                "dauerMs": 5120
              }
            }""";

    /** RFC 7807, as every failure is reported (SPEC §6). */
    public static final String PROBLEM = """
            {
              "type": "https://doc-ai/errors/unreadable-document",
              "title": "unreadable-document",
              "status": 422,
              "detail": "Das Dokument konnte nicht gelesen werden.",
              "requestId": "3f1c9a52-1b7e-4f1e-9a1d-2c1b8e4f7a10"
            }""";
}
