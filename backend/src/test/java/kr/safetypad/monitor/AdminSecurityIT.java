package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

class AdminSecurityIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired DeviceKeyService keys;
    @Autowired JdbcClient jdbc;

    @Test
    void unauthenticatedAdminCallsAreRejected() {
        var c = new AdminClient(rest);
        for (String p : new String[]{"/api/v1/admin/events", "/api/v1/admin/devices", "/api/v1/admin/summary",
                "/api/v1/admin/events/export.csv", "/api/v1/admin/me"}) {
            assertThat(c.get(p).getStatusCode().value()).as(p).isEqualTo(401);
        }
    }

    @Test
    void deviceApiKeyDoesNotOpenAdminApi() {
        String id = "AS-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, null, null, false);
        String key = keys.issue(id).plaintextKey();
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", key);
        var res = rest.exchange("/api/v1/admin/events", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void loginRequiresCsrfTokenAndRejectsBadCredentialsUniformly() {
        var c = new AdminClient(rest);
        assertThat(c.postWithoutCsrf("/api/v1/admin/login", "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}")
                .getStatusCode().value()).isEqualTo(403);
        c.csrf();
        var wrongPw = c.post("/api/v1/admin/login", "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"wrong-password-x\"}");
        var noUser = c.post("/api/v1/admin/login", "{\"username\":\"nobody-here\",\"password\":\"wrong-password-x\"}");
        assertThat(wrongPw.getStatusCode().value()).isEqualTo(401);
        assertThat(noUser.getStatusCode().value()).isEqualTo(401);
        assertThat(wrongPw.getBody().replaceAll("\"request_id\":\"[^\"]*\"", "")).isEqualTo(noUser.getBody().replaceAll("\"request_id\":\"[^\"]*\"", ""));
    }

    @Test
    void sessionCookieIsHttpOnlyAndLogoutEndsSession() {
        var c = new AdminClient(rest).login(ADMIN_USER, ADMIN_PASSWORD);
        assertThat(c.lastLogin.getStatusCode().value()).isEqualTo(200);
        String sessionCookie = c.setCookieHeaders(c.lastLogin).stream().filter(s -> s.startsWith("SPSESSION=")).findFirst().orElseThrow();
        assertThat(sessionCookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(c.get("/api/v1/admin/me").getStatusCode().value()).isEqualTo(200);
        assertThat(c.postWithoutCsrf("/api/v1/admin/logout", "{}").getStatusCode().value()).isEqualTo(403);
        assertThat(c.post("/api/v1/admin/logout", "{}").getStatusCode().value()).isEqualTo(200);
        assertThat(c.get("/api/v1/admin/me").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void mutationsWithoutCsrfAreRejectedEvenWhenLoggedIn() {
        var c = AdminClient.loggedIn(rest);
        assertThat(c.postWithoutCsrf("/api/v1/admin/devices", "{\"device_id\":\"NOCSRF-1\"}").getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM devices WHERE device_id='NOCSRF-1'").query(Long.class).single()).isZero();
    }

    @Test
    void passwordHashIsStoredNotPlaintext() {
        String hash = jdbc.sql("SELECT password_hash FROM admin_users WHERE username=:u").param("u", ADMIN_USER).query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(ADMIN_PASSWORD);
    }

    @Test
    void loginAttemptsAreThrottled() throws InterruptedException {
        // The limiter uses fixed one-minute windows; start early in a window so the 14 attempts cannot straddle a reset.
        while (java.time.LocalTime.now().getSecond() > 40) Thread.sleep(500);
        var c = new AdminClient(rest);
        c.csrf();
        int throttled = 0;
        for (int i = 0; i < 14; i++) {
            var r = c.post("/api/v1/admin/login", "{\"username\":\"brute-target\",\"password\":\"guess-number-" + i + "\"}");
            if (r.getStatusCode().value() == 429) throttled++;
        }
        assertThat(throttled).isGreaterThanOrEqualTo(3);
    }

    @Test
    void wrongContentTypeOnIngestionIs415NotServerError() {
        String id = "CT-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, null, null, false);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        h.set("X-API-Key", keys.issue(id).plaintextKey());
        var res = rest.postForEntity("/api/v1/device-events", new HttpEntity<>("hello", h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(415);
    }
}
