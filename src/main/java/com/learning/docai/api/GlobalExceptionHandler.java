package com.learning.docai.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single source of RFC 7807 responses (SPEC §6). Bodies never contain document content.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that Spring MVC's own exceptions keep
 * their status: a wrong verb stays a 405, a failed {@code @Valid} stays a 400. A bare
 * {@code @ExceptionHandler(Exception.class)} would match those first and report every client
 * mistake as a 500. The catch-all below therefore only ever sees genuinely unexpected
 * failures. Exceptions from the error catalogue are mapped by overriding the specific hooks -
 * declaring our own {@code @ExceptionHandler} for those types would be an ambiguous mapping.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DocAiException.class)
    public ResponseEntity<Object> handleDocAi(DocAiException ex, WebRequest request) {
        return catalogued(ex.errorType(), ex, HttpHeaders.EMPTY, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return catalogued(ErrorType.FILE_TOO_LARGE, ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(
            MissingServletRequestPartException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return catalogued(ErrorType.MISSING_FILE, ex, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return catalogued(ErrorType.MISSING_FILE, ex, headers, request);
    }

    /** Wrong request content type - the same 415 as an upload we cannot decode. */
    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return catalogued(ErrorType.UNSUPPORTED_TYPE, ex, headers, request);
    }

    /**
     * Left to Spring Security's {@code ExceptionTranslationFilter}, which knows whether the
     * caller is unauthenticated (401) or merely lacks the role (403). Rethrowing the same
     * instance makes the resolver stand down without logging a handler failure.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void rethrowAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception on {} [{}]", endpoint(request),
                ex.getClass().getName(), ex);
        return catalogued(ErrorType.INTERNAL_ERROR, ex, HttpHeaders.EMPTY, request);
    }

    private ResponseEntity<Object> catalogued(ErrorType errorType, Exception ex,
            HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(errorType.status());
        problem.setType(errorType.type());
        problem.setTitle(errorType.slug());
        problem.setDetail(errorType.meldung());
        return handleExceptionInternal(ex, problem, headers, errorType.status(), request);
    }

    /**
     * Every problem body passes through here - ours and the framework's - so the requestId
     * is always present (SPEC §6) and every failed request is logged the same way.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        if (body instanceof ProblemDetail problem) {
            String requestId = RequestIdFilter.currentRequestId(request);
            problem.setProperty(RequestIdFilter.ATTRIBUTE, requestId);
            log.warn("Request failed: endpoint={} status={} code={} requestId={}",
                    endpoint(request), statusCode.value(), problem.getTitle(), requestId);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static String endpoint(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : "";
    }
}
