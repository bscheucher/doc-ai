package com.learning.docai.validation;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.learning.docai.config.ValidationProperties;

import lombok.RequiredArgsConstructor;

/**
 * The rules of SPEC §4.1, which every extraction endpoint shares. "Heute" is read from the
 * injected {@link Clock} (Europe/Vienna), so a test can put the document in any season.
 */
@RequiredArgsConstructor
@Component
public class SharedRules {

    private final Clock clock;
    private final ValidationProperties properties;

    /** A required field that the model could not find at all (SPEC §4.1). */
    public void pflichtfeld(IssueCollector issues, String feld, String wert) {
        if (wert == null || wert.isBlank()) {
            issues.add(feld, IssueCode.PFLICHTFELD_FEHLT);
        }
    }

    public void pflichtfeld(IssueCollector issues, String feld, LocalDate wert) {
        if (wert == null) {
            issues.add(feld, IssueCode.PFLICHTFELD_FEHLT);
        }
    }

    /**
     * A date far outside the window in which an absence document reaches us is more likely a
     * misread year than a real one, so it is a warning rather than a rejection.
     */
    public void datumsgrenzen(IssueCollector issues, String feld, LocalDate datum) {
        if (datum == null) {
            return;
        }
        LocalDate heute = LocalDate.now(clock);
        if (datum.isAfter(heute.plusDays(properties.maxDaysInFuture()))) {
            issues.add(feld, IssueCode.DATUM_ZUKUNFT);
        }
        if (datum.isBefore(heute.minusDays(properties.maxDaysInPast()))) {
            issues.add(feld, IssueCode.DATUM_ALT);
        }
    }

    /**
     * SVNR rules of SPEC §4.1. The checksum and the comparison with the hint are independent:
     * a number that is both misread and not the expected one reports both.
     *
     * @param erwartet whether the endpoint expects an SVNR at all (endpoint 2 does)
     */
    public void versicherungsnummer(IssueCollector issues, String feld, String extrahiert,
            String hinweis, boolean erwartet) {

        String gefunden = SvnrValidator.normalise(extrahiert);
        if (gefunden == null || gefunden.isBlank()) {
            if (erwartet) {
                issues.add(feld, IssueCode.SVNR_FEHLT);
            }
            return;
        }
        if (!SvnrValidator.isValid(gefunden)) {
            issues.add(feld, IssueCode.SVNR_PRUEFZIFFER_UNGUELTIG);
        }

        String erwarteteNummer = SvnrValidator.normalise(hinweis);
        if (erwarteteNummer != null && !erwarteteNummer.isBlank()
                && !erwarteteNummer.equals(gefunden)) {
            issues.add(feld, IssueCode.SVNR_WEICHT_AB);
        }
    }

    /** One issue per name field, so a reviewer sees which of the two disagrees (SPEC §4.1). */
    public void name(IssueCollector issues, String feld, String extrahiert, String hinweis) {
        if (hinweis == null || hinweis.isBlank() || extrahiert == null || extrahiert.isBlank()) {
            return;
        }
        if (!NameMatcher.matches(extrahiert, hinweis)) {
            issues.add(feld, IssueCode.NAME_WEICHT_AB);
        }
    }
}
