package com.learning.docai.validation;

/**
 * One finding of the deterministic rules (SPEC §3.1, §4). Severity and message come from the
 * code, so the same code always reads the same way.
 *
 * @param feld the field it is about, named as in the response `daten`
 */
public record ValidationIssue(String feld, IssueCode code, Schweregrad schweregrad, String meldung) {

    public static ValidationIssue of(String feld, IssueCode code) {
        return new ValidationIssue(feld, code, code.schweregrad(), code.meldung());
    }
}
