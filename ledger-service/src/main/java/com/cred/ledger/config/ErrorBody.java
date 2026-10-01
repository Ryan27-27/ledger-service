package com.cred.ledger.config;

import org.slf4j.MDC;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One JSON error shape for every failure -- controller exceptions, security
 * rejections and framework errors -- so clients parse exactly one format.
 * {@code code} is stable and machine-readable; {@code message} is for humans.
 */
public final class ErrorBody {

    private ErrorBody() {
    }

    public static Map<String, Object> of(int status, String error, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("code", code);
        body.put("message", message);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }
}
