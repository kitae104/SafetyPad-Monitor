package kr.safetypad.monitor.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Append-only audit trail for admin changes, exports and logins. Never put secrets in {@code detail}. */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String action, String target, Map<String, Object> detail, HttpServletRequest req) {
        record(currentActor(), action, target, detail, req == null ? null : req.getRemoteAddr());
    }

    public void record(String actor, String action, String target, Map<String, Object> detail, String remoteAddr) {
        jdbc.sql("""
                INSERT INTO audit_logs (actor, action, target, detail, remote_addr)
                VALUES (:actor, :action, :target, CAST(:detail AS jsonb), :addr)
                """)
                .param("actor", truncate(actor, 64))
                .param("action", action)
                .param("target", truncate(target, 128))
                .param("detail", detail == null ? null : mapper.writeValueAsString(detail))
                .param("addr", truncate(remoteAddr, 64))
                .update();
    }

    private static String currentActor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !a.isAuthenticated() ? "anonymous" : a.getName();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
