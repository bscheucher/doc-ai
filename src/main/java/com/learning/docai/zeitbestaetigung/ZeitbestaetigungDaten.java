package com.learning.docai.zeitbestaetigung;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What endpoint 3 extracts from a Zeitbestaetigung (SPEC §3.4). The descriptions travel to
 * the model as the JSON schema, so they are instructions, not documentation for us.
 */
public record ZeitbestaetigungDaten(

        @JsonPropertyDescription("""
                Vorname der Person, deren Anwesenheit bestaetigt wird, ohne Titel und ohne \
                Anrede. null, wenn nicht lesbar.""")
        String vorname,

        @JsonPropertyDescription("""
                Familienname der Person, deren Anwesenheit bestaetigt wird, ohne Titel. \
                null, wenn nicht lesbar.""")
        String familienname,

        @JsonPropertyDescription("""
                Datum des Termins. Erstreckt sich die Bestaetigung ueber mehrere Tage, \
                der erste Tag. Nicht das Ausstellungsdatum. null, wenn nicht angegeben.""")
        LocalDate datumVon,

        @JsonPropertyDescription("""
                Letzter Tag, nur wenn sich die Bestaetigung ueber mehrere Tage erstreckt. \
                Sonst null.""")
        LocalDate datumBis,

        @JsonPropertyDescription("""
                Beginn der Anwesenheit als Uhrzeit. Steht nur eine einzige Uhrzeit auf dem \
                Dokument, gehoert sie hierher. null, wenn keine angegeben ist.""")
        LocalTime zeitVon,

        @JsonPropertyDescription("""
                Ende der Anwesenheit als Uhrzeit. Das ist ein eigener Wert, nicht derselbe \
                wie der Beginn. null, wenn kein Ende angegeben ist.""")
        LocalTime zeitBis,

        @JsonPropertyDescription("""
                Ausstellungsdatum des Dokuments. null, wenn nicht angegeben.""")
        LocalDate ausstellungsdatum,

        @JsonPropertyDescription("""
                Art des Termins, so wie sie auf dem Dokument steht, etwa Arzttermin oder \
                Behoerdentermin. Keine Diagnose und keine Krankheitsbezeichnung. \
                null, wenn nichts dazu dasteht.""")
        String grundDerAbwesenheit,

        @JsonPropertyDescription("""
                Ordination, Ambulanz, Behoerde oder Stelle, die das Dokument ausgestellt hat. \
                null, wenn nicht erkennbar.""")
        String aussteller) {
}
