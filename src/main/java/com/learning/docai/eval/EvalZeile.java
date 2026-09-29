package com.learning.docai.eval;

import java.util.List;
import java.util.Map;

/**
 * One row of `summary.csv` (SPEC §12): what came out for one input document.
 *
 * @param datei          file name as it lies in eval/input
 * @param endpunkt       the endpoint directory it was taken from
 * @param typ            the classified type (endpoint 1) or the dokumenttyp (endpoints 2-4),
 *                       or the error slug of SPEC §6 when the run failed
 * @param manuellePruefung as the response reported it; null when the run failed
 * @param codes          issue codes, in the order the validator produced them
 * @param inputTokens    null when the provider does not report them
 * @param outputTokens   null when the provider does not report them
 * @param dauerMs        duration of the model call, or null when there was none
 * @param feldTreffer    per-field comparison against eval/expected, empty when none exists
 */
record EvalZeile(
        String datei,
        String endpunkt,
        String typ,
        Boolean manuellePruefung,
        List<String> codes,
        Integer inputTokens,
        Integer outputTokens,
        Long dauerMs,
        Map<String, Boolean> feldTreffer) {
}
