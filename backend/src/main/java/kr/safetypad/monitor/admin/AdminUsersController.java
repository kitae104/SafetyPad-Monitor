package kr.safetypad.monitor.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.safetypad.monitor.audit.AuditService;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.ingestion.ApiException;
import kr.safetypad.monitor.security.FixedWindowRateLimiter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal admin-account management (single ADMIN role). Password hashes are never returned and never audited.
 * There is no e-mail infrastructure, so a forgotten password is reset by another admin.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminUsersController {
    record CreateRequest(String username, String password) {}
    record EnabledRequest(Boolean enabled) {}
    record ResetRequest(String newPassword) {}
    record ChangeRequest(String currentPassword, String newPassword) {}

    static final int MIN_PASSWORD = AdminBootstrap.MIN_PASSWORD_LENGTH;
    static final int MAX_PASSWORD = 128;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final AuditService audit;
    private final FixedWindowRateLimiter limiter;

    public AdminUsersController(JdbcClient jdbc, PasswordEncoder encoder, AuthenticationManager authManager,
                                AuditService audit, FixedWindowRateLimiter limiter) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.authManager = authManager;
        this.audit = audit;
        this.limiter = limiter;
    }

    @GetMapping("/users")
    List<Map<String, Object>> list() {
        return jdbc.sql("SELECT username, role, enabled, created_at, last_login_at FROM admin_users ORDER BY username")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", rs.getString("username"));
                    m.put("role", rs.getString("role"));
                    m.put("enabled", rs.getBoolean("enabled"));
                    m.put("created_at", Kst.format(rs.getTimestamp("created_at")));
                    m.put("last_login_at", Kst.format(rs.getTimestamp("last_login_at")));
                    return m;
                }).list();
    }

    @PostMapping("/users")
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest r, HttpServletRequest req) {
        if (r == null || r.username() == null || !r.username().matches("[A-Za-z0-9._-]{3,64}")) {
            throw ApiException.unprocessable("username must be 3-64 chars of [A-Za-z0-9._-]");
        }
        checkPassword(r.password(), r.username());
        try {
            jdbc.sql("INSERT INTO admin_users (username, password_hash) VALUES (:u, :p)")
                    .param("u", r.username()).param("p", encoder.encode(r.password())).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "CONFLICT", "Username already exists");
        }
        audit.record("USER_CREATE", r.username(), null, req);
        return ResponseEntity.status(201).body(Map.of("username", r.username()));
    }

    /** An admin cannot lock themselves out, and at least one enabled admin must always remain. */
    @PostMapping("/users/{username}/enabled")
    @Transactional
    Map<String, Object> setEnabled(@PathVariable String username, @RequestBody EnabledRequest e, Authentication me, HttpServletRequest req) {
        if (e == null || e.enabled() == null) throw ApiException.badRequest("enabled is required");
        if (!e.enabled() && username.equals(me.getName())) {
            throw new ApiException(409, "CONFLICT", "You cannot disable your own account");
        }
        if (!e.enabled()) {
            long others = jdbc.sql("SELECT count(*) FROM admin_users WHERE enabled AND username <> :u")
                    .param("u", username).query(Long.class).single();
            if (others == 0) throw new ApiException(409, "CONFLICT", "At least one enabled admin must remain");
        }
        int n = jdbc.sql("UPDATE admin_users SET enabled = :e WHERE username = :u").param("e", e.enabled()).param("u", username).update();
        if (n == 0) throw new ApiException(404, "NOT_FOUND", "User not found");
        audit.record(e.enabled() ? "USER_ENABLE" : "USER_DISABLE", username, null, req);
        return Map.of("success", true, "enabled", e.enabled());
    }

    @PostMapping("/users/{username}/reset-password")
    Map<String, Object> reset(@PathVariable String username, @RequestBody ResetRequest r, Authentication me, HttpServletRequest req) {
        if (r == null) throw ApiException.badRequest("new_password is required");
        if (username.equals(me.getName())) throw new ApiException(409, "CONFLICT", "Use change-password for your own account");
        checkPassword(r.newPassword(), username);
        int n = jdbc.sql("UPDATE admin_users SET password_hash = :p WHERE username = :u")
                .param("p", encoder.encode(r.newPassword())).param("u", username).update();
        if (n == 0) throw new ApiException(404, "NOT_FOUND", "User not found");
        audit.record("USER_PASSWORD_RESET", username, null, req);
        return Map.of("success", true);
    }

    @PostMapping("/me/password")
    Map<String, Object> change(@RequestBody ChangeRequest r, Authentication me, HttpServletRequest req) {
        if (r == null || r.currentPassword() == null || r.currentPassword().isEmpty()) throw ApiException.badRequest("current_password is required");
        String key = "pwchange-fail:" + me.getName();
        if (limiter.peek(key) >= 5) throw new ApiException(429, "TOO_MANY_REQUESTS", "Too many failed attempts, retry later");
        try {
            authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(me.getName(), r.currentPassword()));
        } catch (AuthenticationException ex) {
            limiter.tryAcquire(key, Integer.MAX_VALUE);
            throw new ApiException(403, "INVALID_CREDENTIALS", "Current password is incorrect");
        }
        checkPassword(r.newPassword(), me.getName());
        if (r.newPassword().equals(r.currentPassword())) throw ApiException.unprocessable("New password must differ from the current one");
        jdbc.sql("UPDATE admin_users SET password_hash = :p WHERE username = :u")
                .param("p", encoder.encode(r.newPassword())).param("u", me.getName()).update();
        audit.record("PASSWORD_CHANGE", me.getName(), null, req);
        return Map.of("success", true);
    }

    private static void checkPassword(String pw, String username) {
        if (pw == null || pw.length() < MIN_PASSWORD || pw.length() > MAX_PASSWORD) {
            throw ApiException.unprocessable("Password must be " + MIN_PASSWORD + "-" + MAX_PASSWORD + " characters");
        }
        if (pw.equalsIgnoreCase(username)) throw ApiException.unprocessable("Password must not equal the username");
    }
}
