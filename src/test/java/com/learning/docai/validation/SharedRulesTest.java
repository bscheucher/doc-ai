package com.learning.docai.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.learning.docai.config.ValidationProperties;

/**
 * The shared rules of SPEC §4.1. "Heute" comes from the injected clock, so the thresholds can
 * be walked to their exact edges instead of being probed with round numbers.
 */
class SharedRulesTest {

    private static final ZoneId WIEN = ZoneId.of("Europe/Vienna");
    private static final LocalDate HEUTE = LocalDate.of(2026, 3, 15);

    private final SharedRules rules = new SharedRules(
            Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), WIEN),
            new ValidationProperties(90, 14, 60));

    @Test
    void reportsAMissingRequiredField() {
        assertThat(codes(collect(issues -> rules.pflichtfeld(issues, "vorname", (String) null))))
                .containsExactly(IssueCode.PFLICHTFELD_FEHLT);
        assertThat(codes(collect(issues -> rules.pflichtfeld(issues, "vorname", "   "))))
                .containsExactly(IssueCode.PFLICHTFELD_FEHLT);
        assertThat(codes(collect(issues -> rules.pflichtfeld(issues, "vorname", "Hans"))))
                .isEmpty();
    }

    @Test
    void reportsAMissingRequiredDate() {
        assertThat(codes(collect(issues -> rules.pflichtfeld(issues, "datumVon", (LocalDate) null))))
                .containsExactly(IssueCode.PFLICHTFELD_FEHLT);
        assertThat(codes(collect(issues -> rules.pflichtfeld(issues, "datumVon", HEUTE))))
                .isEmpty();
    }

    @Test
    void acceptsADateOnBothEdgesOfTheWindow() {
        assertThat(codes(datum(HEUTE.plusDays(14)))).isEmpty();
        assertThat(codes(datum(HEUTE.minusDays(90)))).isEmpty();
    }

    @Test
    void warnsOneDayBeyondEachEdge() {
        assertThat(codes(datum(HEUTE.plusDays(15)))).containsExactly(IssueCode.DATUM_ZUKUNFT);
        assertThat(codes(datum(HEUTE.minusDays(91)))).containsExactly(IssueCode.DATUM_ALT);
    }

    @Test
    void checksNoDateThatWasNotFound() {
        assertThat(codes(datum(null))).isEmpty();
    }

    @Test
    void reportsAMissingSvnrOnlyWhereOneIsExpected() {
        assertThat(codes(svnr(null, null, true))).containsExactly(IssueCode.SVNR_FEHLT);
        assertThat(codes(svnr(null, null, false))).isEmpty();
    }

    @Test
    void reportsAWrongCheckDigit() {
        assertThat(codes(svnr("1121300795", null, true)))
                .containsExactly(IssueCode.SVNR_PRUEFZIFFER_UNGUELTIG);
    }

    @Test
    void comparesWithTheHintIgnoringWhitespace() {
        assertThat(codes(svnr("4568150392", "4568 150392", true))).isEmpty();
        assertThat(codes(svnr("4568150392", "1237010180", true)))
                .containsExactly(IssueCode.SVNR_WEICHT_AB);
    }

    @Test
    void reportsBothWhenTheNumberIsMisreadAndUnexpected() {
        // Independent rules: the checksum says it cannot be an SVNR, the hint says it is not
        // this person's. A reviewer needs to know both.
        assertThat(codes(svnr("1121300795", "1237010180", true)))
                .containsExactly(IssueCode.SVNR_PRUEFZIFFER_UNGUELTIG, IssueCode.SVNR_WEICHT_AB);
    }

    @Test
    void comparesANameOnlyWhenBothSidesAreThere() {
        assertThat(codes(name("Müller", "Muller"))).isEmpty();
        assertThat(codes(name("Müller", null))).isEmpty();
        assertThat(codes(name(null, "Müller"))).isEmpty();
        assertThat(codes(name("Fischer", "Fisher"))).containsExactly(IssueCode.NAME_WEICHT_AB);
    }

    private List<ValidationIssue> datum(LocalDate wert) {
        return collect(issues -> rules.datumsgrenzen(issues, "datumVon", wert));
    }

    private List<ValidationIssue> svnr(String extrahiert, String hinweis, boolean erwartet) {
        return collect(issues -> rules.versicherungsnummer(issues, "versicherungsnummer",
                extrahiert, hinweis, erwartet));
    }

    private List<ValidationIssue> name(String extrahiert, String hinweis) {
        return collect(issues -> rules.name(issues, "vorname", extrahiert, hinweis));
    }

    private static List<ValidationIssue> collect(java.util.function.Consumer<IssueCollector> rule) {
        IssueCollector issues = new IssueCollector();
        rule.accept(issues);
        return issues.toList();
    }

    private static List<IssueCode> codes(List<ValidationIssue> issues) {
        return Problemprotokoll.codes(issues);
    }
}
