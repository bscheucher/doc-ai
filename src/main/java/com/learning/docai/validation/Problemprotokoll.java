package com.learning.docai.validation;

import java.util.List;

/**
 * Turns findings into something that may be logged. Only the codes are safe: `feld` and
 * `meldung` are harmless too, but the values behind them are not, and a log line that grows
 * a value later is the failure this class exists to prevent (CLAUDE.md hard rules).
 */
public final class Problemprotokoll {

    private Problemprotokoll() {
    }

    public static List<IssueCode> codes(List<ValidationIssue> probleme) {
        return probleme.stream().map(ValidationIssue::code).toList();
    }
}
