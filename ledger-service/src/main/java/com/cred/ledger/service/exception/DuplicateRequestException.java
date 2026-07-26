package com.cred.ledger.service.exception;

import com.cred.ledger.domain.LedgerEntry;

/**
 * Thrown when a request replays an idempotencyKey that was already processed.
 * Carries the original entry so the caller can return it instead of erroring --
 * this is what makes the API safe to retry.
 */
public class DuplicateRequestException extends RuntimeException {
    private final LedgerEntry existingEntry;

    public DuplicateRequestException(LedgerEntry existingEntry) {
        super("Request with idempotencyKey [" + existingEntry.getIdempotencyKey() + "] already processed");
        this.existingEntry = existingEntry;
    }

    public LedgerEntry getExistingEntry() {
        return existingEntry;
    }
}
