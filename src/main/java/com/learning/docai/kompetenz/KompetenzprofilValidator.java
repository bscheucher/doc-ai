package com.learning.docai.kompetenz;

import java.util.List;

import org.springframework.stereotype.Component;

import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.IssueCollector;
import com.learning.docai.validation.SharedRules;
import com.learning.docai.validation.ValidationIssue;

import lombok.RequiredArgsConstructor;

/**
 * The rules of SPEC §4.4 on top of the shared ones. Two deliberate differences to endpoints
 * 2 and 3: ibosNG passes no participant hints here (SPEC §3), so no name is compared, and the
 * only date on the document is the date of birth, which the window of §4.1 would always flag.
 */
@RequiredArgsConstructor
@Component
public class KompetenzprofilValidator {

    private final SharedRules shared;

    /**
     * Takes the model output as it comes. Normalising here rather than trusting the caller
     * costs nothing - it is idempotent, and the service has already done it - but it keeps a
     * list the model omitted from turning a KEINE_KOMPETENZEN warning into a 500.
     */
    public List<ValidationIssue> pruefe(KompetenzprofilDaten roh) {
        KompetenzprofilDaten daten = roh.normalisiert();
        IssueCollector issues = new IssueCollector();

        shared.pflichtfeld(issues, "vorname", daten.vorname());
        shared.pflichtfeld(issues, "nachname", daten.nachname());

        // The profile carries an SVNR only sometimes, so a missing one is not worth reporting;
        // one that is there still has to survive the checksum (SPEC §4.4).
        shared.versicherungsnummer(issues, "versicherungsnummer", daten.versicherungsnummer(),
                null, false);

        if (daten.fachlich().isEmpty()) {
            issues.add("fachlich", IssueCode.KEINE_KOMPETENZEN);
        }

        kompetenzen(issues, "fachlich", daten.fachlich());
        kompetenzen(issues, "ueberfachlich", daten.ueberfachlich());

        return issues.toList();
    }

    /**
     * A half-read row is the failure natif showed on the sample document: a score of 95 with
     * no competency next to it. Each row is reported under its own index so a reviewer can go
     * straight to it.
     */
    private void kompetenzen(IssueCollector issues, String liste, List<Kompetenz> zeilen) {
        for (int i = 0; i < zeilen.size(); i++) {
            Kompetenz zeile = zeilen.get(i);
            String feld = liste + "[" + i + "]";
            boolean bezeichnung = zeile.bezeichnung() != null && !zeile.bezeichnung().isBlank();

            if (zeile.score() != null && !bezeichnung) {
                issues.add(feld, IssueCode.SCORE_OHNE_BEZEICHNUNG);
            }
            if (bezeichnung && zeile.score() == null) {
                issues.add(feld, IssueCode.BEZEICHNUNG_OHNE_SCORE);
            }
            if (zeile.score() != null && (zeile.score() < 0 || zeile.score() > 100)) {
                issues.add(feld, IssueCode.SCORE_AUSSERHALB);
            }
        }
    }
}
