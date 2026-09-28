package com.learning.docai.krankenstand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.learning.docai.config.ValidationProperties;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.Problemprotokoll;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.validation.SvnrValidator;
import com.learning.docai.validation.Teilnehmerhinweis;
import com.learning.docai.validation.ValidationIssue;

/**
 * Every rule of SPEC §4.2, plus the shared ones as endpoint 2 applies them (§4.1).
 */
class KrankenstandValidatorTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);
    private static final String GUELTIGE_SVNR = "1237010180";

    private final ValidationProperties properties = new ValidationProperties(90, 14, 60);

    private final KrankenstandValidator validator = new KrankenstandValidator(
            new SharedRules(Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"),
                    ZoneId.of("Europe/Vienna")), properties),
            properties);

    @Test
    void reportsNothingForACompleteDocument() {
        assertThat(pruefe(vollstaendig(), hinweis("Hans", "Müller", GUELTIGE_SVNR))).isEmpty();
    }

    @Test
    void reportsEveryMissingRequiredField() {
        KrankenstandDaten daten = new KrankenstandDaten(null, "   ", GUELTIGE_SVNR,
                "Musterweg 1, 1010 Wien", null, HEUTE, HEUTE);

        assertThat(pruefe(daten, ohneHinweis()))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .contains(tuple("vorname", IssueCode.PFLICHTFELD_FEHLT),
                        tuple("familienname", IssueCode.PFLICHTFELD_FEHLT),
                        tuple("arbeitsunfaehigVon", IssueCode.PFLICHTFELD_FEHLT));
    }

    @Test
    void expectsAnSvnrOnThisEndpoint() {
        assertThat(codes(pruefe(mitSvnr(null), ohneHinweis()))).contains(IssueCode.SVNR_FEHLT);
    }

    @Test
    void reportsAnSvnrThatFailsTheChecksum() {
        assertThat(codes(pruefe(mitSvnr("1121300795"), ohneHinweis())))
                .contains(IssueCode.SVNR_PRUEFZIFFER_UNGUELTIG);
    }

    @Test
    void reportsAnSvnrThatIsNotTheExpectedPersons() {
        assertThat(codes(pruefe(vollstaendig(), hinweis("Hans", "Müller", "4568 150392"))))
                .contains(IssueCode.SVNR_WEICHT_AB);
    }

    @Test
    void acceptsTheHintWrittenWithSpaces() {
        KrankenstandDaten daten = mitSvnr(SvnrValidator.normalise("4568 150392"));

        assertThat(codes(pruefe(daten, hinweis("Hans", "Müller", "4568 150392"))))
                .doesNotContain(IssueCode.SVNR_WEICHT_AB);
    }

    @Test
    void reportsEachNameThatDisagreesWithTheHint() {
        assertThat(pruefe(vollstaendig(), hinweis("Johann", "Fischer", GUELTIGE_SVNR)))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("vorname", IssueCode.NAME_WEICHT_AB),
                        tuple("familienname", IssueCode.NAME_WEICHT_AB));
    }

    @Test
    void warnsAboutADateFarOutsideTheUsualWindow() {
        assertThat(codes(pruefe(zeitraum(HEUTE.plusDays(15), HEUTE.plusDays(20)), ohneHinweis())))
                .contains(IssueCode.DATUM_ZUKUNFT);
        assertThat(codes(pruefe(zeitraum(HEUTE.minusDays(91), HEUTE.minusDays(90)), ohneHinweis())))
                .contains(IssueCode.DATUM_ALT);
    }

    @Test
    void reportsAMissingEnd() {
        assertThat(pruefe(zeitraum(HEUTE, null), ohneHinweis()))
                .extracting(ValidationIssue::feld, ValidationIssue::code)
                .containsExactly(tuple("letzterTagArbeitsunfaehigkeit", IssueCode.ENDE_FEHLT));
    }

    @Test
    void reportsAnEndBeforeTheBeginning() {
        assertThat(codes(pruefe(zeitraum(HEUTE, HEUTE.minusDays(1)), ohneHinweis())))
                .containsExactly(IssueCode.ENDE_VOR_BEGINN);
    }

    @Test
    void acceptsAnAbsenceOfExactlyTheConfiguredLength() {
        // Both days count, so 60 days is von plus 59.
        assertThat(codes(pruefe(zeitraum(HEUTE, HEUTE.plusDays(59)), ohneHinweis()))).isEmpty();
    }

    @Test
    void warnsAboutAnUnusuallyLongAbsence() {
        assertThat(codes(pruefe(zeitraum(HEUTE, HEUTE.plusDays(60)), ohneHinweis())))
                .containsExactly(IssueCode.DAUER_UNGEWOEHNLICH);
    }

    private List<ValidationIssue> pruefe(KrankenstandDaten daten, Teilnehmerhinweis hinweis) {
        return validator.pruefe(daten, hinweis);
    }

    private static List<IssueCode> codes(List<ValidationIssue> issues) {
        return Problemprotokoll.codes(issues);
    }

    private static KrankenstandDaten vollstaendig() {
        return new KrankenstandDaten("Hans", "Müller", GUELTIGE_SVNR, "Musterweg 1, 1010 Wien",
                HEUTE, HEUTE.plusDays(3), HEUTE);
    }

    private static KrankenstandDaten mitSvnr(String svnr) {
        return new KrankenstandDaten("Hans", "Müller", svnr, "Musterweg 1, 1010 Wien",
                HEUTE, HEUTE.plusDays(3), HEUTE);
    }

    private static KrankenstandDaten zeitraum(LocalDate von, LocalDate bis) {
        return new KrankenstandDaten("Hans", "Müller", GUELTIGE_SVNR, "Musterweg 1, 1010 Wien",
                von, bis, HEUTE);
    }

    private static Teilnehmerhinweis hinweis(String vorname, String familienname, String svnr) {
        return new Teilnehmerhinweis(vorname, familienname, svnr);
    }

    private static Teilnehmerhinweis ohneHinweis() {
        return Teilnehmerhinweis.ohne();
    }
}
