package kr.safetypad.monitor.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import kr.safetypad.monitor.audit.AuditService;
import kr.safetypad.monitor.ingestion.ApiException;
import kr.safetypad.monitor.security.FixedWindowRateLimiter;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAuthController {
    record LoginRequest(String username, String password) {}

    private static final int LOGIN_FAILURES_PER_MINUTE = 10;

    private final AuthenticationManager authManager;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final FixedWindowRateLimiter limiter;
    private final SecurityContextRepository contextRepo = new HttpSessionSecurityContextRepository();

    public AdminAuthController(AuthenticationManager authManager, AuditService audit, JdbcClient jdbc, Clock clock,
                               FixedWindowRateLimiter limiter) {
        this.authManager = authManager;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.limiter = limiter;
    }

    /** Touching the token makes the CSRF cookie available to the SPA, which echoes it in X-XSRF-TOKEN. */
    @GetMapping("/api/v1/admin/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("header_name", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/api/v1/admin/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String username = body == null || body.username() == null ? "" : body.username().trim();
        String password = body == null || body.password() == null ? "" : body.password();
        if (username.isEmpty() || password.isEmpty() || username.length() > 64 || password.length() > 200) {
            throw ApiException.badRequest("username and password are required");
        }
        // Only FAILED attempts consume the budget, so a legitimate admin is never locked out by successful logins.
        String throttleKey = "login-fail:" + req.getRemoteAddr() + ":" + username.toLowerCase();
        if (limiter.peek(throttleKey) >= LOGIN_FAILURES_PER_MINUTE) {
            throw new ApiException(429, "TOO_MANY_REQUESTS", "Too many failed login attempts, retry later");
        }
        Authentication auth;
        try {
            auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException e) {
            limiter.tryAcquire(throttleKey, Integer.MAX_VALUE);
            audit.record(safe(username), "LOGIN_FAILED", null, null, req.getRemoteAddr());
            // Identical answer for unknown user, wrong password and disabled account.
            throw new ApiException(401, "INVALID_CREDENTIALS", "Invalid username or password");
        }
        HttpSession existing = req.getSession(false);
        if (existing != null) req.changeSessionId();   // session fixation defence
        else req.getSession(true);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, req, res);
        jdbc.sql("UPDATE admin_users SET last_login_at = :now WHERE username = :u")
                .param("now", Timestamp.from(clock.instant())).param("u", auth.getName()).update();
        audit.record(auth.getName(), "LOGIN", null, null, req.getRemoteAddr());
        return ResponseEntity.ok(Map.of("username", auth.getName(), "role", "ADMIN"));
    }

    @PostMapping("/api/v1/admin/logout")
    Map<String, Object> logout(HttpServletRequest req) {
        String who = SecurityContextHolder.getContext().getAuthentication() == null ? "unknown"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        audit.record(who, "LOGOUT", null, null, req.getRemoteAddr());
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("success", true);
    }

    @GetMapping("/api/v1/admin/me")
    Map<String, Object> me(Authentication auth) {
        return Map.of("username", auth.getName(), "role", "ADMIN");
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._@-]", "?");
    }
}
