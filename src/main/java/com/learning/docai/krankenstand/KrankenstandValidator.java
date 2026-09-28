package com.learning.docai.krankenstand;

import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.learning.docai.config.ValidationProperties;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.IssueCollector;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.validation.Teilnehmerhinweis;
import com.learning.docai.validation.ValidationIssue;

import lombok.RequiredArgsConstructor;

/**
 * The rules of SPEC §4.2 on top of the shared ones. Nothing here rejects a document: the
 * findings decide whether a person looks at it.
 */
@RequiredArgsConstructor
@Component
public class KrankenstandValidator {

    private final SharedRules shared;
    private final ValidationProperties properties;

    public List<ValidationIssue> pruefe(KrankenstandDaten daten, Teilnehmerhinweis hinweis) {
        IssueCollector issues = new IssueCollector();

        shared.pflichtfeld(issues, "vorname", daten.vorname());
        shared.pflichtfeld(issues, "familienname", daten.familienname());
        shared.pflichtfeld(issues, "arbeitsunfaehigVon", daten.arbeitsunfaehigVon());

        shared.versicherungsnummer(issues, "versicherungsnummer", daten.versicherungsnummer(),
                hinweis.svnr(), true);
        shared.name(issues, "vorname", daten.vorname(), hinweis.vorname());
        shared.name(issues, "familienname", daten.familienname(), hinweis.familienname());

        shared.datumsgrenzen(issues, "arbeitsunfaehigVon", daten.arbeitsunfaehigVon());

        zeitraum(issues, daten);
        return issues.toList();
    }

    /**
     * The end of the absence is reported against `letzterTagArbeitsunfaehigkeit`, because
     * that is the value a reviewer has to check on the document - the beginning is usually
     * the one that is right.
     */
    private void zeitraum(IssueCollector issues, KrankenstandDaten daten) {
        if (daten.letzterTagArbeitsunfaehigkeit() == null) {
            // Common and legitimate: many notes state only the first day (SPEC §4.2).
            issues.add("letzterTagArbeitsunfaehigkeit", IssueCode.ENDE_FEHLT);
            return;
        }
        if (daten.arbeitsunfaehigVon() == null) {
            return;
        }
        if (daten.letzterTagArbeitsunfaehigkeit().isBefore(daten.arbeitsunfaehigVon())) {
            issues.add("letzterTagArbeitsunfaehigkeit", IssueCode.ENDE_VOR_BEGINN);
            return;
        }
        // Both days count as days of absence, so a note for a single day has duration 1.
        long tage = ChronoUnit.DAYS.between(daten.arbeitsunfaehigVon(),
                daten.letzterTagArbeitsunfaehigkeit()) + 1;
        if (tage > properties.maxKrankenstandDays()) {
            issues.add("letzterTagArbeitsunfaehigkeit", IssueCode.DAUER_UNGEWOEHNLICH);
        }
    }
}
