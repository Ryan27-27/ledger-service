package com.cred.ledger.controller;

import com.cred.ledger.config.ErrorBody;
import com.cred.ledger.service.exception.AccessDeniedException;
import com.cred.ledger.service.exception.AccountNotFoundException;
import com.cred.ledger.service.exception.IdempotencyKeyReuseException;
import com.cred.ledger.service.exception.InsufficientBalanceException;
import com.cred.ledger.service.exception.InvalidCredentialsException;
import com.cred.ledger.service.exception.InvalidRefreshTokenException;
import com.cred.ledger.service.exception.InvalidReversalException;
import com.cred.ledger.service.exception.RateLimitExceededException;
import com.cred.ledger.service.exception.UsernameAlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps every failure to the one JSON shape defined by {@link ErrorBody}.
 * Extends ResponseEntityExceptionHandler so Spring's own MVC errors (bad
 * method, unsupported media type, missing param, unknown route...) get the
 * same treatment instead of Spring's default ProblemDetail/HTML.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(AccountNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientBalance(InsufficientBalanceException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE", e.getMessage());
    }

    @ExceptionHandler(InvalidReversalException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidReversal(InvalidReversalException e) {
        return build(HttpStatus.CONFLICT, "INVALID_REVERSAL", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyReuse(IdempotencyKeyReuseException e) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSE", e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(ErrorBody.of(429, HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(), "RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameExists(UsernameAlreadyExistsException e) {
        return build(HttpStatus.CONFLICT, "USERNAME_TAKEN", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", e.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRefresh(InvalidRefreshTokenException e) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", e.getMessage());
    }

    /**
     * Lost an optimistic-lock race (after the controller's retries) or hit a
     * lock timeout / deadlock. Safe for the client to retry with the same key.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrency(ConcurrencyFailureException e) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Concurrent update detected, please retry");
    }

    /** Unique / FK / CHECK violations that slipped past application checks (e.g. a registration race). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data");
    }

    /** Last resort: never leak internals, but always log the stack trace with the request id. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        String message = fieldErrors.entrySet().stream().findFirst()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .orElse("Validation failed");

        Map<String, Object> body = ErrorBody.of(400, "Bad Request", "VALIDATION_ERROR", message);
        body.put("fieldErrors", fieldErrors);
        return new ResponseEntity<>(body, headers, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return new ResponseEntity<>(
                ErrorBody.of(400, "Bad Request", "MALFORMED_REQUEST", "Malformed or unreadable request body"),
                headers, HttpStatus.BAD_REQUEST);
    }

    /** Any other framework-raised MVC error (405, 415, 404 route, bad path variable...) in our shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : "Error";
        return new ResponseEntity<>(
                ErrorBody.of(statusCode.value(), reason, "HTTP_" + statusCode.value(), e.getMessage()),
                headers, statusCode);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ErrorBody.of(status.value(), status.getReasonPhrase(), code, message));
    }
}
