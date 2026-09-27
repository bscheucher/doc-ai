package com.learning.docai.api;

/**
 * Carries an {@link ErrorType} from anywhere in the pipeline to the exception handler.
 * The message is the catalogue message; it never contains document content.
 */
public class DocAiException extends RuntimeException {

    private final ErrorType errorType;

    public DocAiException(ErrorType errorType) {
        super(errorType.meldung());
        this.errorType = errorType;
    }

    public DocAiException(ErrorType errorType, Throwable cause) {
        super(errorType.meldung(), cause);
        this.errorType = errorType;
    }

    public ErrorType errorType() {
        return errorType;
    }
}
