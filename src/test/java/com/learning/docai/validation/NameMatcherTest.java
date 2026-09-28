package com.learning.docai.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SPEC §4.6, with the test data given there.
 */
class NameMatcherTest {

    @Test
    void ignoresDiacriticsAndSeparators() {
        assertThat(NameMatcher.matches("Müller-Lüdenscheid", "Muller Ludenscheid")).isTrue();
    }

    @Test
    void treatsTheDotlessTurkishIAsAnI() {
        assertThat(NameMatcher.matches("Yilmaz", "Yılmaz")).isTrue();
    }

    @Test
    void doesNotMatchADifferentName() {
        assertThat(NameMatcher.matches("Fischer", "Fisher-Meier")).isFalse();
    }

    @Test
    void acceptsADoubleNameThatContainsTheOther() {
        assertThat(NameMatcher.matches("Fischer", "Fischer-Meier")).isTrue();
    }

    @Test
    void foldsTheSharpS() {
        assertThat(NameMatcher.matches("Strauß", "Strauss")).isTrue();
    }

    @Test
    void comparesNothingWhenOneSideIsMissing() {
        // The missing side is PFLICHTFELD_FEHLT's business, not a name mismatch.
        assertThat(NameMatcher.matches(null, "Müller")).isTrue();
        assertThat(NameMatcher.matches("  ", "Müller")).isTrue();
    }
}
