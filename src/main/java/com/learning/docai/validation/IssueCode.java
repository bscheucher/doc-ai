package com.learning.docai.validation;

/**
 * The issue catalogue of SPEC §4.1-4.4. Each code carries its severity and its German
 * message, so a rule only has to name the code: callers rely on the code, never on the text.
 */
public enum IssueCode {

    PFLICHTFELD_FEHLT(Schweregrad.FEHLER,
            "Pflichtfeld fehlt oder ist leer."),
    SVNR_FEHLT(Schweregrad.WARNUNG,
            "Versicherungsnummer wurde nicht gefunden."),
    SVNR_PRUEFZIFFER_UNGUELTIG(Schweregrad.FEHLER,
            "Pruefziffer der Versicherungsnummer ungueltig."),
    SVNR_WEICHT_AB(Schweregrad.FEHLER,
            "Versicherungsnummer weicht vom uebergebenen Wert ab."),
    NAME_WEICHT_AB(Schweregrad.WARNUNG,
            "Name weicht vom uebergebenen Wert ab."),
    DATUM_ZUKUNFT(Schweregrad.WARNUNG,
            "Datum liegt zu weit in der Zukunft."),
    DATUM_ALT(Schweregrad.WARNUNG,
            "Datum liegt zu weit in der Vergangenheit."),
    ENDE_FEHLT(Schweregrad.WARNUNG,
            "Letzter Tag der Arbeitsunfaehigkeit fehlt."),
    ENDE_VOR_BEGINN(Schweregrad.FEHLER,
            "Das Enddatum liegt vor dem Beginn."),
    DAUER_UNGEWOEHNLICH(Schweregrad.WARNUNG,
            "Die Dauer ist ungewoehnlich lang."),
    UHRZEIT_FEHLT(Schweregrad.WARNUNG,
            "Uhrzeit fehlt."),
    UHRZEIT_REIHENFOLGE(Schweregrad.FEHLER,
            "Die Endzeit liegt nicht nach der Startzeit."),
    KEINE_KOMPETENZEN(Schweregrad.WARNUNG,
            "Es wurden keine fachlichen Kompetenzen gefunden."),
    SCORE_OHNE_BEZEICHNUNG(Schweregrad.FEHLER,
            "Zu diesem Wert fehlt die Bezeichnung der Kompetenz."),
    BEZEICHNUNG_OHNE_SCORE(Schweregrad.WARNUNG,
            "Zu dieser Kompetenz fehlt der Wert."),
    SCORE_AUSSERHALB(Schweregrad.FEHLER,
            "Der Wert der Kompetenz liegt ausserhalb von 0 bis 100.");

    private final Schweregrad schweregrad;
    private final String meldung;

    IssueCode(Schweregrad schweregrad, String meldung) {
        this.schweregrad = schweregrad;
        this.meldung = meldung;
    }

    public Schweregrad schweregrad() {
        return schweregrad;
    }

    public String meldung() {
        return meldung;
    }
}
