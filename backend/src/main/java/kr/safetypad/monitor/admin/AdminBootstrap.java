package kr.safetypad.monitor.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the FIRST admin from operator-supplied environment variables, only when no admin exists.
 * There is no built-in default account or password. The password is never logged.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AdminBootstrap(JdbcClient jdbc, PasswordEncoder encoder,
                          @Value("${safetypad.admin.bootstrap-username:}") String username,
                          @Value("${safetypad.admin.bootstrap-password:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existing = jdbc.sql("SELECT count(*) FROM admin_users").query(Long.class).single();
        if (existing > 0) return;
        if (username.isBlank() || password.isBlank()) {
            log.warn("No admin user exists and ADMIN_BOOTSTRAP_USERNAME/PASSWORD are not set: nobody can log in");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH || !username.matches("[A-Za-z0-9._-]{3,64}")) {
            log.error("Admin bootstrap skipped: username must be 3-64 safe chars and password at least {} chars", MIN_PASSWORD_LENGTH);
            return;
        }
        jdbc.sql("INSERT INTO admin_users (username, password_hash) VALUES (:u, :p)")
                .param("u", username).param("p", encoder.encode(password)).update();
        log.info("Bootstrap admin '{}' created", username);
    }
}
