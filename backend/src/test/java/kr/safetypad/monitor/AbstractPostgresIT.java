package kr.safetypad.monitor;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One shared real PostgreSQL for all integration tests (no mocked persistence). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
public abstract class AbstractPostgresIT {

    public static final String ADMIN_USER = "test-admin";
    public static final String ADMIN_PASSWORD = "test-admin-password-123";

    @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("safetypad.ingest.device-key-pepper", () -> "test-pepper-test-pepper-test-pepper-0123456789");
        r.add("safetypad.admin.bootstrap-username", () -> ADMIN_USER);
        r.add("safetypad.admin.bootstrap-password", () -> ADMIN_PASSWORD);
        r.add("safetypad.demo.data-dir", () -> "../data");
        r.add("safetypad.metrics.username", () -> "metrics-test");
        r.add("safetypad.metrics.password", () -> "metrics-test-password-123456");
    }
}
