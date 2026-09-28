package com.learning.docai.krankenstand;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What endpoint 2 extracts from a Krankenstandsbestaetigung (SPEC §3.3). The descriptions
 * travel to the model as the JSON schema, so they are instructions, not documentation for us.
 *
 * <p>natif's `grund_der_arbeitsunfaehigkeit` is intentionally absent: the reason for the
 * inability to work is a diagnosis, and we do not extract it (SPEC §3.3, CLAUDE.md).
 */
public record KrankenstandDaten(

        @JsonPropertyDescription("""
                Vorname der arbeitsunfaehigen Person, ohne Titel und ohne Anrede. \
                null, wenn nicht lesbar.""")
        String vorname,

        @JsonPropertyDescription("""
                Familienname der arbeitsunfaehigen Person, ohne Titel. \
                null, wenn nicht lesbar.""")
        String familienname,

        @JsonPropertyDescription("""
                Oesterreichische Versicherungsnummer der Person: genau zehn Ziffern, \
                ohne Leerzeichen und ohne Trennzeichen. null, wenn nicht angegeben.""")
        String versicherungsnummer,

        @JsonPropertyDescription("""
                Adresse, an der sich die Person waehrend des Krankenstands aufhaelt, \
                als eine Zeile. null, wenn keine angegeben ist.""")
        String krankenstandsadresse,

        @JsonPropertyDescription("""
                Erster Tag der Arbeitsunfaehigkeit. null, wenn nicht angegeben.""")
        LocalDate arbeitsunfaehigVon,

        @JsonPropertyDescription("""
                Letzter Tag der Arbeitsunfaehigkeit, genau wie auf dem Dokument angegeben. \
                Rechne kein Ende aus und uebernimm kein voraussichtliches Ende, das nicht \
                dasteht. null, wenn kein Ende angegeben ist.""")
        LocalDate letzterTagArbeitsunfaehigkeit,

        @JsonPropertyDescription("""
                Ausstellungsdatum des Dokuments. null, wenn nicht angegeben.""")
        LocalDate ausstellungsdatum) {
}
