package com.learning.docai.api;

import java.util.List;

import com.learning.docai.validation.ValidationIssue;

/**
 * The response envelope shared by endpoints 2-4 (SPEC §3.1). Missing fields are not an error:
 * `daten` is always present on 200, single fields inside it may be null, and what is wrong
 * with them is in `probleme`.
 *
 * @param <T> the endpoint's extraction record
 */
public record ExtraktionResponse<T>(
        String requestId,
        Extraktionstyp dokumenttyp,
        T daten,
        List<ValidationIssue> probleme,
        boolean manuellePruefung,
        Metadaten metadaten) {

    /**
     * `manuellePruefung` is not a judgement of its own: any finding, warning or error, means
     * a person looks at the document (SPEC §3.1).
     */
    public static <T> ExtraktionResponse<T> of(String requestId, Extraktionstyp dokumenttyp,
            T daten, List<ValidationIssue> probleme, Metadaten metadaten) {

        return new ExtraktionResponse<>(requestId, dokumenttyp, daten, List.copyOf(probleme),
                !probleme.isEmpty(), metadaten);
    }
}
