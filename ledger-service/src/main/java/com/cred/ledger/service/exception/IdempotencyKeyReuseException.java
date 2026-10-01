package com.cred.ledger.service.exception;

/** The same idempotency key was replayed with a different request (type/amount). */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("Idempotency key [" + key + "] was already used for a different request");
    }
}
