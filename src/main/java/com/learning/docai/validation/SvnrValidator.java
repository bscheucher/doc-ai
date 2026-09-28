package com.learning.docai.validation;

/**
 * Austrian Versicherungsnummer per SPEC §4.5: ten digits `LLLC DDMMYY`, where the fourth
 * digit is the check digit over the other nine.
 */
public final class SvnrValidator {

    /** Position 3 carries weight 0: the check digit does not check itself. */
    private static final int[] WEIGHTS = { 3, 7, 9, 0, 5, 8, 4, 2, 1, 6 };

    private static final int PRUEFZIFFER_POSITION = 3;

    private SvnrValidator() {
    }

    /**
     * Whitespace is allowed on the document and in the hint from ibosNG ("4568 150392"), so
     * it is removed before anything is compared or checked.
     */
    public static String normalise(String svnr) {
        return svnr == null ? null : svnr.replaceAll("\\s", "");
    }

    public static boolean isValid(String svnr) {
        String digits = normalise(svnr);
        if (digits == null || digits.length() != 10 || !digits.chars().allMatch(Character::isDigit)
                || digits.charAt(0) == '0') {
            return false;
        }

        int sum = 0;
        for (int position = 0; position < WEIGHTS.length; position++) {
            sum += Character.getNumericValue(digits.charAt(position)) * WEIGHTS[position];
        }

        int rest = sum % 11;
        // 10 cannot be written as a single digit, so such a number is never issued.
        return rest != 10 && rest == Character.getNumericValue(digits.charAt(PRUEFZIFFER_POSITION));
    }
}
