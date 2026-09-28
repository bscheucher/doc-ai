package com.learning.docai.kompetenz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.learning.docai.config.ValidationProperties;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.Schweregrad;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.validation.ValidationIssue;

/**
 * Every rule of SPEC §4.4, plus the shared ones as endpoint 4 applies them (§4.1).
 */
class KompetenzprofilValidatorTest {

    private static final LocalDate GEBURTSDATUM = LocalDate.of(1985, 7, 13);
    private static final List<Kompetenz> FACHLICH =
            List.of(new Kompetenz("Buero Verwaltung", 70), new Kompetenz("Berufserfahrung", 90));

    private final KompetenzprofilValidator validator = new KompetenzprofilValidator(
            new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                    ZoneId.of("Europe/Vienna")), new ValidationProperties(90, 14, 60)));

    @Test
    void reportsNothingForACompleteProfile() {
        assertThat(pruefe(vollstaendig())).isEmpty();
    }

    @Test
    void reportsEveryMissingRequiredField() {
        KompetenzprofilDaten daten = new KompetenzprofilDaten(null, "  ", GEBURTSDATUM, null,
                FACHLICH, List.of(), List.of(), List.of());

        assertThat(pruefe(daten))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("vorname", IssueCode.PFLICHTFELD_FEHLT),
                        tuple("nachname", IssueCode.PFLICHTFELD_FEHLT));
    }

    @Test
    void doesNotAskForAnSvnrThatTheProfileNeedNotCarry() {
        // SPEC §4.4: optional here, unlike endpoint 2 - a missing one is not a finding.
        assertThat(pruefe(mitSvnr(null))).isEmpty();
    }

    @Test
    void checksTheSvnrChecksumWhenOneIsThere() {
        assertThat(pruefe(mitSvnr("1121300795")))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("versicherungsnummer",
                        IssueCode.SVNR_PRUEFZIFFER_UNGUELTIG));

        assertThat(pruefe(mitSvnr("1237010180"))).isEmpty();
    }

    @Test
    void warnsWhenNoProfessionalCompetencyWasFound() {
        KompetenzprofilDaten daten = profil(List.of(), List.of(new Kompetenz("Teamarbeit", 80)));

        assertThat(pruefe(daten))
                .extracting(ValidationIssue::feld, ValidationIssue::code,
                        ValidationIssue::schweregrad)
                .containsExactly(
                        tuple("fachlich", IssueCode.KEINE_KOMPETENZEN, Schweregrad.WARNUNG));
    }

    @Test
    void reportsAScoreWithoutACompetency() {
        // The natif failure case: score 95 with nothing next to it, in the first row.
        KompetenzprofilDaten daten = profil(
                Arrays.asList(new Kompetenz(null, 95), new Kompetenz("Berufserfahrung", 90)),
                List.of());

        assertThat(pruefe(daten))
                .extracting(ValidationIssue::feld, ValidationIssue::code,
                        ValidationIssue::schweregrad)
                .containsExactly(tuple("fachlich[0]", IssueCode.SCORE_OHNE_BEZEICHNUNG,
                        Schweregrad.FEHLER));
    }

    @Test
    void reportsACompetencyWithoutAScore() {
        KompetenzprofilDaten daten = profil(
                Arrays.asList(new Kompetenz("Buero Verwaltung", 70),
                        new Kompetenz("Berufserfahrung", null)),
                List.of());

        assertThat(pruefe(daten))
                .extracting(ValidationIssue::feld, ValidationIssue::code,
                        ValidationIssue::schweregrad)
                .containsExactly(tuple("fachlich[1]", IssueCode.BEZEICHNUNG_OHNE_SCORE,
                        Schweregrad.WARNUNG));
    }

    @Test
    void reportsAScoreOutsideTheScale() {
        KompetenzprofilDaten daten = profil(
                List.of(new Kompetenz("Buero Verwaltung", 120), new Kompetenz("Deutsch", -1)),
                List.of());

        assertThat(pruefe(daten))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("fachlich[0]", IssueCode.SCORE_AUSSERHALB),
                        tuple("fachlich[1]", IssueCode.SCORE_AUSSERHALB));
    }

    @Test
    void acceptsTheEndsOfTheScale() {
        assertThat(pruefe(profil(List.of(new Kompetenz("Deutsch", 0),
                new Kompetenz("Bilanzbuchhalterpruefung", 100)), List.of()))).isEmpty();
    }

    @Test
    void namesTheListAndTheRowOfATransversalCompetency() {
        KompetenzprofilDaten daten = profil(FACHLICH,
                Arrays.asList(new Kompetenz("Teamarbeit", 80), new Kompetenz(null, 60)));

        assertThat(pruefe(daten))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("ueberfachlich[1]", IssueCode.SCORE_OHNE_BEZEICHNUNG));
    }

    @Test
    void treatsListsTheModelOmittedAsEmptyInsteadOfFailing() {
        // A caller that hands over raw model output - as the sibling services do - must get
        // the warning, not an NPE.
        KompetenzprofilDaten roh = new KompetenzprofilDaten("Amira", "Ahmed", GEBURTSDATUM,
                null, null, null, null, null);

        assertThat(validator.pruefe(roh))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("fachlich", IssueCode.KEINE_KOMPETENZEN));
    }

    @Test
    void doesNotApplyTheDateWindowToTheDateOfBirth() {
        // Every date of birth is decades old; §4.4 asks for no date check here, and DATUM_ALT
        // would otherwise fire on every single profile.
        assertThat(pruefe(vollstaendig()))
                .extracting(ValidationIssue::code)
                .doesNotContain(IssueCode.DATUM_ALT, IssueCode.DATUM_ZUKUNFT);
    }

    private List<ValidationIssue> pruefe(KompetenzprofilDaten daten) {
        return validator.pruefe(daten);
    }

    private static KompetenzprofilDaten vollstaendig() {
        return profil(FACHLICH, List.of(new Kompetenz("Teamarbeit", 80)));
    }

    private static KompetenzprofilDaten mitSvnr(String svnr) {
        return new KompetenzprofilDaten("Amira", "Ahmed", GEBURTSDATUM, svnr, FACHLICH,
                List.of(), List.of(), List.of());
    }

    private static KompetenzprofilDaten profil(List<Kompetenz> fachlich,
            List<Kompetenz> ueberfachlich) {
        return new KompetenzprofilDaten("Amira", "Ahmed", GEBURTSDATUM, null, fachlich,
                ueberfachlich, List.of("Staplerschein"), List.of("Logistik"));
    }
}
