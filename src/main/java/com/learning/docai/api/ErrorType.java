package com.learning.docai.api;

import java.net.URI;

import org.springframework.http.HttpStatus;

/**
 * Error catalogue per SPEC §6. Callers rely on the slug, not on the message.
 */
public enum ErrorType {

    MISSING_FILE(HttpStatus.BAD_REQUEST, "missing-file",
            "Es wurde keine Datei uebermittelt."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large",
            "Die Datei ueberschreitet die maximal zulaessige Groesse."),
    UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-type",
            "Nicht unterstuetzter Dateityp. Erlaubt sind PDF, PNG und JPEG."),
    UNREADABLE_DOCUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "unreadable-document",
            "Das Dokument konnte nicht gelesen werden."),
    MODEL_ERROR(HttpStatus.BAD_GATEWAY, "model-error",
            "Das Modell lieferte kein verwertbares Ergebnis."),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "model-unavailable",
            "Der Modelldienst ist derzeit nicht erreichbar."),
    MODEL_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "model-timeout",
            "Die Auswertung durch das Modell hat zu lange gedauert."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
            "Unerwarteter Fehler bei der Verarbeitung.");

    private static final String TYPE_PREFIX = "https://doc-ai/errors/";

    private final HttpStatus status;
    private final String slug;
    private final String meldung;

    ErrorType(HttpStatus status, String slug, String meldung) {
        this.status = status;
        this.slug = slug;
        this.meldung = meldung;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String meldung() {
        return meldung;
    }

    public URI type() {
        return URI.create(TYPE_PREFIX + slug);
    }
}
