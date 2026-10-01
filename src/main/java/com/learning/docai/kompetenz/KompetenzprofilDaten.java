package com.learning.docai.kompetenz;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What endpoint 4 extracts from an AMS-Kompetenzprofil (SPEC §3.5). The descriptions travel
 * to the model as the JSON schema, so they are instructions, not documentation for us.
 *
 * <p>Note {@code nachname} rather than {@code familienname}: SPEC §3.5 names the field that
 * way, as natif did, and the contract is what the caller parses.
 */
public record KompetenzprofilDaten(

        @JsonPropertyDescription("""
                Vorname der Person, zu der das Kompetenzprofil gehoert, ohne Titel. \
                null, wenn nicht lesbar.""")
        String vorname,

        @JsonPropertyDescription("""
                Nachname der Person, zu der das Kompetenzprofil gehoert, ohne Titel. \
                null, wenn nicht lesbar.""")
        String nachname,

        @JsonPropertyDescription("""
                Geburtsdatum der Person. null, wenn nicht angegeben.""")
        LocalDate geburtsdatum,

        @JsonPropertyDescription("""
                Oesterreichische Versicherungsnummer, zehn Ziffern ohne Leerzeichen. \
                null, wenn keine auf dem Dokument steht.""")
        String versicherungsnummer,

        @JsonPropertyDescription("""
                Fachliche Kompetenzen, ein Eintrag je Zeile der Tabelle, in der Reihenfolge \
                des Dokuments. Leere Zeilen laesst du weg.""")
        List<Kompetenz> fachlich,

        @JsonPropertyDescription("""
                Ueberfachliche Kompetenzen, ein Eintrag je Zeile der Tabelle, in der \
                Reihenfolge des Dokuments. Leere Zeilen laesst du weg.""")
        List<Kompetenz> ueberfachlich,

        @JsonPropertyDescription("""
                Zertifikate, so wie sie aufgelistet sind. Leere Liste, wenn keine dastehen.""")
        List<String> zertifikate,

        @JsonPropertyDescription("""
                Interessengebiete, so wie sie aufgelistet sind. Leere Liste, wenn keine \
                dastehen.""")
        List<String> interessengebiete) {

    /**
     * The lists as SPEC §3.5 promises them: empty rather than null, and without the rows that
     * carry nothing - natif returned three of those for `ueberfachlich` on the sample
     * document, and a model may do the same despite the instruction. Dropping them before the
     * rules run keeps the index in `fachlich[2]` pointing at the entry the caller received.
     */
    public KompetenzprofilDaten normalisiert() {
        return new KompetenzprofilDaten(vorname, nachname, geburtsdatum, versicherungsnummer,
                kompetenzen(fachlich), kompetenzen(ueberfachlich),
                texte(zertifikate), texte(interessengebiete));
    }

    private static List<Kompetenz> kompetenzen(List<Kompetenz> zeilen) {
        return zeilen == null ? List.of()
                : zeilen.stream().filter(z -> z != null && !z.istLeer()).toList();
    }

    private static List<String> texte(List<String> eintraege) {
        return eintraege == null ? List.of()
                : eintraege.stream().filter(e -> e != null && !e.isBlank()).toList();
    }
}
