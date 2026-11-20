package kr.safetypad.monitor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a server-generated request id (never trusted from the client), exposes it in the
 * X-Request-Id response header and MDC, and writes one access line.
 * Headers (X-API-Key, cookies) and bodies are deliberately never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String MDC_KEY = "requestId";
    private static final Logger ACCESS = LoggerFactory.getLogger("safetypad.access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            ACCESS.info("{} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(), res.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
            MDC.remove(MDC_KEY);
        }
    }
}
