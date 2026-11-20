package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class AdminUsersIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;

    static String name() { return "u" + UUID.randomUUID().toString().substring(0, 8); }
    static String body(String u, String p) { return "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"; }

    @Test
    void createdAdminCanLogInAndHashIsNeverExposedOrAudited() {
        var admin = AdminClient.loggedIn(rest);
        String u = name(), pw = "first-password-123";
        assertThat(admin.post("/api/v1/admin/users", body(u, pw)).getStatusCode().value()).isEqualTo(201);
        var list = admin.get("/api/v1/admin/users").getBody();
        assertThat(list).contains(u).doesNotContain("password").doesNotContain("bcrypt");
        assertThat(new AdminClient(rest).login(u, pw).lastLogin.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action='USER_CREATE' AND target=:t").param("t", u).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT coalesce(string_agg(detail::text, ''), '') FROM audit_logs WHERE target=:t").param("t", u).query(String.class).single()).doesNotContain(pw);
    }

    @Test
    void validationConflictsAndAuthorization() {
        var admin = AdminClient.loggedIn(rest);
        String u = name();
        assertThat(admin.post("/api/v1/admin/users", body(u, "short")).getStatusCode().value()).isEqualTo(422);
        assertThat(admin.post("/api/v1/admin/users", body("bad name!", "long-enough-password")).getStatusCode().value()).isEqualTo(422);
        assertThat(admin.post("/api/v1/admin/users", body(u, u + u)).getStatusCode().value()).isEqualTo(201);
        assertThat(admin.post("/api/v1/admin/users", body(u, "another-long-password")).getStatusCode().value()).isEqualTo(409);
        assertThat(new AdminClient(rest).post("/api/v1/admin/users", body(name(), "long-enough-password")).getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void disabledAdminCannotLogInAndYouCannotLockYourselfOut() {
        var admin = AdminClient.loggedIn(rest);
        String u = name(), pw = "disable-me-password-1";
        admin.post("/api/v1/admin/users", body(u, pw));
        assertThat(admin.post("/api/v1/admin/users/" + ADMIN_USER + "/enabled", "{\"enabled\":false}").getStatusCode().value()).isEqualTo(409);
        assertThat(admin.post("/api/v1/admin/users/" + u + "/enabled", "{\"enabled\":false}").getStatusCode().value()).isEqualTo(200);
        assertThat(new AdminClient(rest).login(u, pw).lastLogin.getStatusCode().value()).isEqualTo(401);
        assertThat(admin.post("/api/v1/admin/users/" + u + "/enabled", "{\"enabled\":true}").getStatusCode().value()).isEqualTo(200);
        assertThat(new AdminClient(rest).login(u, pw).lastLogin.getStatusCode().value()).isEqualTo(200);
        assertThat(admin.post("/api/v1/admin/users/nobody-xyz/enabled", "{\"enabled\":false}").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void changeOwnPasswordNeedsCurrentPasswordAndOldOneStopsWorking() {
        var admin = AdminClient.loggedIn(rest);
        String u = name(), pw = "original-password-1", pw2 = "replacement-password-2";
        admin.post("/api/v1/admin/users", body(u, pw));
        var me = new AdminClient(rest).login(u, pw);
        assertThat(me.post("/api/v1/admin/me/password", "{\"current_password\":\"wrong-current-pw\",\"new_password\":\"" + pw2 + "\"}").getStatusCode().value()).isEqualTo(403);
        assertThat(me.post("/api/v1/admin/me/password", "{\"current_password\":\"" + pw + "\",\"new_password\":\"short\"}").getStatusCode().value()).isEqualTo(422);
        assertThat(me.post("/api/v1/admin/me/password", "{\"current_password\":\"" + pw + "\",\"new_password\":\"" + pw + "\"}").getStatusCode().value()).isEqualTo(422);
        assertThat(me.post("/api/v1/admin/me/password", "{\"current_password\":\"" + pw + "\",\"new_password\":\"" + pw2 + "\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(new AdminClient(rest).login(u, pw).lastLogin.getStatusCode().value()).isEqualTo(401);
        assertThat(new AdminClient(rest).login(u, pw2).lastLogin.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void adminCanResetAnotherPasswordButNotItsOwnThroughThatRoute() {
        var admin = AdminClient.loggedIn(rest);
        String u = name();
        admin.post("/api/v1/admin/users", body(u, "forgotten-password-1"));
        assertThat(admin.post("/api/v1/admin/users/" + u + "/reset-password", "{\"new_password\":\"reset-by-admin-pw-9\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(new AdminClient(rest).login(u, "reset-by-admin-pw-9").lastLogin.getStatusCode().value()).isEqualTo(200);
        assertThat(admin.post("/api/v1/admin/users/" + ADMIN_USER + "/reset-password", "{\"new_password\":\"whatever-long-password\"}").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void disablingAnAdminEndsTheirAlreadyOpenSession() {
        var admin = AdminClient.loggedIn(rest);
        String u = name(), pw = "live-session-password-1";
        admin.post("/api/v1/admin/users", body(u, pw));
        var victim = new AdminClient(rest).login(u, pw);
        assertThat(victim.get("/api/v1/admin/me").getStatusCode().value()).isEqualTo(200);
        admin.post("/api/v1/admin/users/" + u + "/enabled", "{\"enabled\":false}");
        assertThat(victim.get("/api/v1/admin/me").getStatusCode().value()).isEqualTo(401);     // no waiting for session expiry
        assertThat(victim.get("/api/v1/admin/events").getStatusCode().value()).isEqualTo(401);
    }
}
