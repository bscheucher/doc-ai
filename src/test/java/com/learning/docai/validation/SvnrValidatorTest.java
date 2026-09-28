package com.learning.docai.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC §4.5, including the sample numbers from the natif responses.
 */
class SvnrValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = { "1237010180", "4568 150392", "4568150392" })
    void acceptsAValidNumber(String svnr) {
        assertThat(SvnrValidator.isValid(svnr)).isTrue();
    }

    @Test
    void rejectsTheNatifSampleWithAWrongCheckDigit() {
        // natif returned this one with confidence 0.998 - the checksum is what catches it.
        assertThat(SvnrValidator.isValid("1121300795")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "123701018", "12370101801", "0237010180", "12370101AB", "" })
    void rejectsAnythingThatIsNotTenDigitsStartingAboveZero(String svnr) {
        assertThat(SvnrValidator.isValid(svnr)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(SvnrValidator.isValid(null)).isFalse();
    }

    @Test
    void removesWhitespaceBeforeAnythingElse() {
        assertThat(SvnrValidator.normalise("  4568 1503 92 ")).isEqualTo("4568150392");
        // A hint copied out of a web form carries these, and they are not matched by "\\s".
        assertThat(SvnrValidator.normalise("4568\u00a0150392")).isEqualTo("4568150392");
        assertThat(SvnrValidator.normalise("4568\u202f150392")).isEqualTo("4568150392");
        assertThat(SvnrValidator.normalise(null)).isNull();
    }
}
