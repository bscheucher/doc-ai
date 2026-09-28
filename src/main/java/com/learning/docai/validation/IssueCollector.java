package com.learning.docai.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the findings of one document while the rules run. Deliberately mutable and
 * short-lived: a rule adds what it found and never looks at what another rule added, so the
 * order of the list is the order of the rules in the validator.
 */
public class IssueCollector {

    private final List<ValidationIssue> issues = new ArrayList<>();

    public void add(String feld, IssueCode code) {
        issues.add(ValidationIssue.of(feld, code));
    }

    public List<ValidationIssue> toList() {
        return List.copyOf(issues);
    }
}
