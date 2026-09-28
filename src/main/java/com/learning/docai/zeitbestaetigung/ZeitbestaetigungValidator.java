package com.learning.docai.zeitbestaetigung;

import java.util.List;

import org.springframework.stereotype.Component;

import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.IssueCollector;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.validation.Teilnehmerhinweis;
import com.learning.docai.validation.ValidationIssue;

import lombok.RequiredArgsConstructor;

/**
 * The rules of SPEC §4.3 on top of the shared ones. An excuse note carries no SVNR, so that
 * rule is not applied here.
 */
@RequiredArgsConstructor
@Component
public class ZeitbestaetigungValidator {

    private final SharedRules shared;

    public List<ValidationIssue> pruefe(ZeitbestaetigungDaten daten, Teilnehmerhinweis hinweis) {
        IssueCollector issues = new IssueCollector();

        shared.pflichtfeld(issues, "vorname", daten.vorname());
        shared.pflichtfeld(issues, "familienname", daten.familienname());
        shared.pflichtfeld(issues, "datumVon", daten.datumVon());

        shared.name(issues, "vorname", daten.vorname(), hinweis.vorname());
        shared.name(issues, "familienname", daten.familienname(), hinweis.familienname());

        shared.datumsgrenzen(issues, "datumVon", daten.datumVon());

        if (daten.datumBis() != null && daten.datumVon() != null
                && daten.datumBis().isBefore(daten.datumVon())) {
            issues.add("datumBis", IssueCode.ENDE_VOR_BEGINN);
        }

        uhrzeiten(issues, daten);
        return issues.toList();
    }

    /**
     * One issue per missing time, so a reviewer sees which end of the appointment is open.
     * The order check only applies within one day: over several days an end time before the
     * start time is normal (SPEC §4.3).
     */
    private void uhrzeiten(IssueCollector issues, ZeitbestaetigungDaten daten) {
        if (daten.zeitVon() == null) {
            issues.add("zeitVon", IssueCode.UHRZEIT_FEHLT);
        }
        if (daten.zeitBis() == null) {
            issues.add("zeitBis", IssueCode.UHRZEIT_FEHLT);
        }
        if (daten.zeitVon() == null || daten.zeitBis() == null) {
            return;
        }
        boolean derselbeTag = daten.datumBis() == null || daten.datumBis().equals(daten.datumVon());
        if (derselbeTag && !daten.zeitBis().isAfter(daten.zeitVon())) {
            issues.add("zeitBis", IssueCode.UHRZEIT_REIHENFOLGE);
        }
    }
}
