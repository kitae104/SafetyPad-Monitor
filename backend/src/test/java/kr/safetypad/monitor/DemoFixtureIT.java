package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.safetypad.monitor.demo.DemoController;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class DemoFixtureIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired DeviceKeyService keys;
    @Autowired ObjectMapper mapper;

    @Test
    void importIsIdempotentShiftedToTodayFlaggedSimulatedAndResetOnlyTouchesSimulated() {
        String real = "REAL-" + UUID.randomUUID().toString().substring(0, 6);
        keys.registerDevice(real, "real", null, false);
        jdbc.sql("""
                INSERT INTO device_events (device_pk, event_id, event_type, event_time, received_at, raw_payload)
                VALUES ((SELECT id FROM devices WHERE device_id=:d), 'KEEP', 'MULTI_RIDE', now(), now(), '{}'::jsonb)
                """).param("d", real).update();

        var c = AdminClient.loggedIn(rest);
        var first = mapper.readTree(c.post("/api/v1/admin/dev/import-demo", "{}").getBody());
        assertThat(first.get("devices").asInt()).isEqualTo(50);
        assertThat(first.get("events_inserted").asInt()).isEqualTo(178);
        assertThat(first.get("label").asString()).isEqualTo("가상 데이터");
        var second = mapper.readTree(c.post("/api/v1/admin/dev/import-demo", "{}").getBody());
        assertThat(second.get("events_inserted").asInt()).isZero();                        // idempotent
        assertThat(jdbc.sql("SELECT count(*) FROM devices WHERE simulated AND device_id LIKE 'PAD-%'").query(Long.class).single()).isGreaterThanOrEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM device_locations").query(Long.class).single()).isEqualTo(2250);

        var sum = mapper.readTree(c.get("/api/v1/admin/summary").getBody());
        assertThat(sum.get("simulated_data_present").asBoolean()).isTrue();
        assertThat(sum.get("violations_today").asLong()).isGreaterThan(0);                 // shift put seed data on today (KST)

        assertThat(c.post("/api/v1/admin/dev/reset-demo", "{\"confirm\":\"nope\"}").getStatusCode().value()).isEqualTo(400);
        var reset = c.post("/api/v1/admin/dev/reset-demo", "{\"confirm\":\"" + DemoController.RESET_CONFIRMATION + "\"}");
        assertThat(reset.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id=e.device_pk WHERE d.simulated").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id=e.device_pk WHERE d.device_id=:d").param("d", real).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action IN ('DEMO_IMPORT','DEMO_RESET')").query(Long.class).single()).isGreaterThanOrEqualTo(3);
        // the delete trigger is closed again outside the reset transaction
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM device_events").update()).hasMessageContaining("immutable");
    }
}
