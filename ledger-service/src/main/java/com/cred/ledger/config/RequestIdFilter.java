package com.cred.ledger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id (honouring a sane inbound X-Request-Id),
 * puts it in the logging MDC and echoes it in the response, and writes one
 * access-log line per request. Support can grep a single id across all logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger accessLog = LoggerFactory.getLogger("access");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches()
                ? inbound : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String path = request.getRequestURI();
            if (!path.startsWith("/actuator")) {
                accessLog.info("{} {} -> {} ({} ms)", request.getMethod(), path, response.getStatus(), ms);
            }
            MDC.remove(MDC_KEY);
        }
    }
}
