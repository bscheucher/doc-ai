package com.learning.docai.api;

/**
 * The four endpoints as a bounded set of metric tag values (SPEC §10).
 *
 * <p>An enum rather than the request path on purpose: the `endpoint` tag is derived from the URI
 * for failures that never reach a controller, and a stray call to /api/v1/tippfehler would
 * otherwise open a new time series per path. Anything unrecognised is folded into
 * {@link #UNBEKANNT}.
 */
public enum ApiEndpoint {

    KLASSIFIKATION("klassifikation", "/api/v1/klassifikation"),
    KRANKENSTAND("krankenstand", "/api/v1/extraktion/krankenstand"),
    ZEITBESTAETIGUNG("zeitbestaetigung", "/api/v1/extraktion/zeitbestaetigung"),
    KOMPETENZPROFIL("kompetenzprofil", "/api/v1/extraktion/kompetenzprofil"),
    UNBEKANNT("unbekannt", null);

    private final String tag;
    private final String path;

    ApiEndpoint(String tag, String path) {
        this.tag = tag;
        this.path = path;
    }

    /** The value of the `endpoint` tag (SPEC §10). */
    public String tag() {
        return tag;
    }

    /**
     * The endpoint a request URI belongs to. Matched by prefix so that a trailing slash or a
     * path the container normalised still lands on the right one.
     */
    public static ApiEndpoint fromPath(String uri) {
        if (uri == null) {
            return UNBEKANNT;
        }
        ApiEndpoint treffer = UNBEKANNT;
        for (ApiEndpoint kandidat : values()) {
            // The extraction paths share a prefix with nothing, but the longest match is taken
            // anyway so adding a nested path later cannot quietly change the tag.
            if (kandidat.path != null && uri.startsWith(kandidat.path)
                    && (treffer.path == null || kandidat.path.length() > treffer.path.length())) {
                treffer = kandidat;
            }
        }
        return treffer;
    }
}
