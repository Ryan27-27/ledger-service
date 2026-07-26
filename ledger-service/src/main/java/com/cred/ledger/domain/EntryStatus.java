package com.cred.ledger.domain;

/**
 * POSTED    -> entry is final and counted in balance (ledger is append-only,
 *              so a mistaken entry is never deleted or updated - it is reversed
 *              by inserting a compensating entry instead)
 * REVERSED  -> a compensating entry was posted against this one
 */
public enum EntryStatus {
    POSTED,
    REVERSED
}
