package com.learning.docai.zeitbestaetigung;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.learning.docai.config.ValidationProperties;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.Problemprotokoll;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.validation.Teilnehmerhinweis;
import com.learning.docai.validation.ValidationIssue;

/**
 * Every rule of SPEC §4.3, plus the shared ones as endpoint 3 applies them (§4.1).
 */
class ZeitbestaetigungValidatorTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);
    private static final LocalTime VON = LocalTime.of(10, 30);
    private static final LocalTime BIS = LocalTime.of(11, 15);

    private final ZeitbestaetigungValidator validator = new ZeitbestaetigungValidator(
            new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                    ZoneId.of("Europe/Vienna")), new ValidationProperties(90, 14, 60)));

    @Test
    void reportsNothingForACompleteDocument() {
        assertThat(pruefe(vollstaendig(), hinweis("Hans", "Müller"))).isEmpty();
    }

    @Test
    void reportsEveryMissingRequiredField() {
        ZeitbestaetigungDaten daten = new ZeitbestaetigungDaten(null, "  ", null, null,
                VON, BIS, HEUTE, "Arzttermin", "Ordination Dr. Muster");

        assertThat(pruefe(daten, ohneHinweis()))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .contains(tuple("vorname", IssueCode.PFLICHTFELD_FEHLT),
                        tuple("familienname", IssueCode.PFLICHTFELD_FEHLT),
                        tuple("datumVon", IssueCode.PFLICHTFELD_FEHLT));
    }

    @Test
    void reportsANameThatDisagreesWithTheHint() {
        assertThat(pruefe(vollstaendig(), hinweis("Hans", "Fischer")))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("familienname", IssueCode.NAME_WEICHT_AB));
    }

    @Test
    void warnsAboutADateFarOutsideTheUsualWindow() {
        assertThat(codes(pruefe(termin(HEUTE.plusDays(15), null), ohneHinweis())))
                .contains(IssueCode.DATUM_ZUKUNFT);
        assertThat(codes(pruefe(termin(HEUTE.minusDays(91), null), ohneHinweis())))
                .contains(IssueCode.DATUM_ALT);
    }

    @Test
    void reportsALastDayBeforeTheFirst() {
        assertThat(pruefe(termin(HEUTE, HEUTE.minusDays(1)), ohneHinweis()))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("datumBis", IssueCode.ENDE_VOR_BEGINN));
    }

    @Test
    void reportsEachMissingTimeSeparately() {
        ZeitbestaetigungDaten daten = new ZeitbestaetigungDaten("Hans", "Müller", HEUTE, null,
                null, null, HEUTE, "Arzttermin", "Ordination Dr. Muster");

        assertThat(pruefe(daten, ohneHinweis()))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("zeitVon", IssueCode.UHRZEIT_FEHLT),
                        tuple("zeitBis", IssueCode.UHRZEIT_FEHLT));
    }

    @Test
    void reportsAnEndTimeThatIsNotAfterTheStart() {
        assertThat(codes(pruefe(zeiten(HEUTE, null, VON, VON), ohneHinweis())))
                .containsExactly(IssueCode.UHRZEIT_REIHENFOLGE);
        assertThat(codes(pruefe(zeiten(HEUTE, null, BIS, VON), ohneHinweis())))
                .containsExactly(IssueCode.UHRZEIT_REIHENFOLGE);
    }

    @Test
    void acceptsAnEarlierEndTimeOnALaterDay() {
        // A confirmation over two days that ends in the morning is not a contradiction.
        assertThat(codes(pruefe(zeiten(HEUTE, HEUTE.plusDays(1), BIS, VON), ohneHinweis())))
                .isEmpty();
    }

    private List<ValidationIssue> pruefe(ZeitbestaetigungDaten daten, Teilnehmerhinweis hinweis) {
        return validator.pruefe(daten, hinweis);
    }

    private static List<IssueCode> codes(List<ValidationIssue> issues) {
        return Problemprotokoll.codes(issues);
    }

    private static ZeitbestaetigungDaten vollstaendig() {
        return zeiten(HEUTE, null, VON, BIS);
    }

    private static ZeitbestaetigungDaten termin(LocalDate von, LocalDate bis) {
        return zeiten(von, bis, VON, BIS);
    }

    private static ZeitbestaetigungDaten zeiten(LocalDate datumVon, LocalDate datumBis,
            LocalTime zeitVon, LocalTime zeitBis) {

        return new ZeitbestaetigungDaten("Hans", "Müller", datumVon, datumBis, zeitVon, zeitBis,
                HEUTE, "Arzttermin", "Ordination Dr. Muster");
    }

    private static Teilnehmerhinweis hinweis(String vorname, String familienname) {
        return new Teilnehmerhinweis(vorname, familienname, null);
    }

    private static Teilnehmerhinweis ohneHinweis() {
        return Teilnehmerhinweis.ohne();
    }
}
