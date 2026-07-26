package com.cred.ledger.controller;

import com.cred.ledger.service.exception.AccessDeniedException;
import com.cred.ledger.service.exception.AccountNotFoundException;
import com.cred.ledger.service.exception.InsufficientBalanceException;
import com.cred.ledger.service.exception.InvalidCredentialsException;
import com.cred.ledger.service.exception.InvalidReversalException;
import com.cred.ledger.service.exception.RateLimitExceededException;
import com.cred.ledger.service.exception.UsernameAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(AccountNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientBalance(InsufficientBalanceException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(InvalidReversalException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidReversal(InvalidReversalException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameExists(UsernameAlreadyExistsException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Thrown when Account's @Version check fails -- another transaction updated
     * the balance between our read and write. Surface as 409 so the client
     * knows to retry, rather than a generic 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return build(HttpStatus.CONFLICT, "Concurrent update detected, please retry");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
