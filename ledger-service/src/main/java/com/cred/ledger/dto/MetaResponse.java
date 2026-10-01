package com.cred.ledger.dto;

/** Public, non-sensitive runtime info the UI needs before login. */
public record MetaResponse(boolean demoMode, String version) {
}
