package kr.safetypad.monitor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A session stores the authentication from login time. Without this check, disabling (or deleting) an admin would
 * not end sessions that are already open. Admin traffic is tiny, so one indexed lookup per request is fine.
 */
public class AdminSessionRevalidationFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;

    public AdminSessionRevalidationFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken)) {
            boolean enabled = jdbc.sql("SELECT enabled FROM admin_users WHERE username = :u")
                    .param("u", a.getName()).query(Boolean.class).optional().orElse(false);
            if (!enabled) {
                HttpSession s = req.getSession(false);
                if (s != null) s.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
