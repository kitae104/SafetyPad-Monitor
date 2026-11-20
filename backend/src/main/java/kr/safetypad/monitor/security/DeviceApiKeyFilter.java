package kr.safetypad.monitor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import kr.safetypad.monitor.config.IngestProperties;
import kr.safetypad.monitor.device.DeviceKeyService;
import kr.safetypad.monitor.device.DeviceKeyService.AuthenticatedDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates device ingestion calls by the X-API-Key header.
 * Order: cheap size check, per-IP limit, key lookup (401), disabled device (403),
 * per-key limit (429), body size cap (413). The key is never logged or echoed.
 */
public class DeviceApiKeyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DeviceApiKeyFilter.class);
    private static final int MAX_KEY_LENGTH = 128;

    private final DeviceKeyService keys;
    private final IngestProperties props;
    private final FixedWindowRateLimiter limiter;

    public DeviceApiKeyFilter(DeviceKeyService keys, IngestProperties props, FixedWindowRateLimiter limiter) {
        this.keys = keys;
        this.props = props;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getContentLengthLong() > props.maxBodyBytes()) {
            error(res, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds " + props.maxBodyBytes() + " bytes");
            return;
        }
        if (!limiter.tryAcquire("ip:" + req.getRemoteAddr(), props.perIpPerMinute())) {
            tooMany(res);
            return;
        }
        String key = req.getHeader("X-API-Key");
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            error(res, 401, "UNAUTHORIZED", "Missing or invalid API key");
            return;
        }
        Optional<AuthenticatedDevice> device;
        try {
            device = keys.authenticate(key);
        } catch (DataAccessException e) {
            log.error("Credential lookup failed: {}", e.getClass().getSimpleName());
            res.setHeader("Retry-After", "30");
            error(res, 503, "SERVICE_UNAVAILABLE", "Temporarily unavailable, retry safely");
            return;
        }
        if (device.isEmpty()) {
            log.warn("Rejected ingestion call with unknown or revoked key from {}", req.getRemoteAddr());
            error(res, 401, "UNAUTHORIZED", "Missing or invalid API key");
            return;
        }
        AuthenticatedDevice d = device.get();
        if (!d.deviceActive()) {
            error(res, 403, "DEVICE_DISABLED", "Device is disabled");
            return;
        }
        if (!limiter.tryAcquire("cred:" + d.credentialId(), props.perKeyPerMinute())) {
            tooMany(res);
            return;
        }
        byte[] body = req.getInputStream().readNBytes(props.maxBodyBytes() + 1);
        if (body.length > props.maxBodyBytes()) {
            error(res, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds " + props.maxBodyBytes() + " bytes");
            return;
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(d, null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(new CachedBodyRequest(req, body), res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void tooMany(HttpServletResponse res) throws IOException {
        res.setHeader("Retry-After", "60");
        error(res, 429, "TOO_MANY_REQUESTS", "Rate limit exceeded");
    }

    public static void error(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        String rid = MDC.get(RequestIdFilter.MDC_KEY);
        res.getWriter().write("{\"success\":false,\"error\":\"" + code + "\",\"message\":\"" + message
                + "\",\"request_id\":\"" + (rid == null ? "" : rid) + "\"}");
    }

    /** Replays an already-read (size-capped) body to downstream consumers. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
