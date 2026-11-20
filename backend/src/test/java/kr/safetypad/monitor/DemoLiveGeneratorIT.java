package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Dev-only live demo generator: random, lease-bound, written through the real ingestion path. */
@TestPropertySource(properties = {
        "safetypad.demo.live.min-delay-ms=20",
        "safetypad.demo.live.max-delay-ms=60",
        "safetypad.demo.live.lease-seconds=2"})
class DemoLiveGeneratorIT extends AbstractPostgresIT {
    static final String STATUS = "/api/v1/admin/dev/live-generator";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;
    @Autowired DeviceKeyService keys;
    @Autowired ObjectMapper mapper;

    long liveEvents() {
        return jdbc.sql("SELECT count(*) FROM device_events WHERE event_id LIKE '%-LIVE-%'").query(Long.class).single();
    }

    JsonNode set(AdminClient c, boolean enabled) {
        var r = c.post(STATUS, "{\"enabled\":" + enabled + "}");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return mapper.readTree(r.getBody());
    }

    @Test
    void requiresAnAdminSession() {
        assertThat(new AdminClient(rest).get(STATUS).getStatusCode().value()).isEqualTo(401);
        var anon = new AdminClient(rest);
        anon.csrf();
        assertThat(anon.post(STATUS, "{\"enabled\":true}").getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void rejectsAMissingFlag() {
        var c = AdminClient.loggedIn(rest);
        assertThat(c.post(STATUS, "{}").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void generatesRandomEventsOnlyForActiveSimulatedDevicesThroughTheRealIngestPath() throws Exception {
        var c = AdminClient.loggedIn(rest);
        c.post("/api/v1/admin/dev/import-demo", "{}");
        String real = "REAL-" + UUID.randomUUID().toString().substring(0, 6);
        keys.registerDevice(real, "real", null, false);
        // only PAD-0001 stays eligible, so every generated event must belong to it
        jdbc.sql("UPDATE devices SET active = false WHERE simulated AND device_id <> 'PAD-0001'").update();
        try {
            long before = liveEvents();
            assertThat(set(c, true).get("running").asBoolean()).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                set(c, true);                                                               // heartbeat keeps the lease alive
                assertThat(liveEvents() - before).isGreaterThanOrEqualTo(5);
            });
            JsonNode off = set(c, false);
            assertThat(off.get("running").asBoolean()).isFalse();

            var rows = jdbc.sql("""
                    SELECT d.device_id, d.simulated, e.event_id, e.event_time, e.received_at, e.gps_lat, e.gps_lon, e.battery_level,
                           e.raw_payload->>'simulated' AS flag
                    FROM device_events e JOIN devices d ON d.id = e.device_pk
                    WHERE e.event_id LIKE '%-LIVE-%' ORDER BY e.id DESC LIMIT 5
                    """).query().listOfRows();
            assertThat(rows).isNotEmpty();
            for (var r : rows) {
                assertThat(r.get("device_id")).isEqualTo("PAD-0001");
                assertThat(r.get("simulated")).isEqualTo(true);
                assertThat(r.get("flag")).isEqualTo("true");                                // visibly marked as simulated
                assertThat(((java.sql.Timestamp) r.get("event_time")).toInstant())
                        .isBeforeOrEqualTo(((java.sql.Timestamp) r.get("received_at")).toInstant());
                var bat = (Integer) r.get("battery_level");
                assertThat(bat).isBetween(0, 100);
            }
            assertThat(jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id=e.device_pk WHERE d.device_id=:d")
                    .param("d", real).query(Long.class).single()).isZero();                 // real devices are never touched

            long stopped = liveEvents();
            Thread.sleep(400);
            assertThat(liveEvents()).as("no events after stop").isEqualTo(stopped);
            assertThat(jdbc.sql("SELECT last_received_at IS NOT NULL FROM devices WHERE device_id='PAD-0001'").query(Boolean.class).single()).isTrue();
        } finally {
            set(c, false);
            jdbc.sql("UPDATE devices SET active = true WHERE simulated").update();
        }
    }

    @Test
    void generationStopsByItselfWhenTheClientStopsRenewing() throws Exception {
        var c = AdminClient.loggedIn(rest);
        c.post("/api/v1/admin/dev/import-demo", "{}");
        set(c, true);
        await().atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                assertThat(mapper.readTree(c.get(STATUS).getBody()).get("running").asBoolean()).isFalse());
        long stopped = liveEvents();
        Thread.sleep(400);
        assertThat(liveEvents()).isEqualTo(stopped);
    }

    @Test
    void onlyStartAndStopTransitionsAreAudited() {
        var c = AdminClient.loggedIn(rest);
        c.post("/api/v1/admin/dev/import-demo", "{}");
        long before = jdbc.sql("SELECT count(*) FROM audit_logs WHERE action IN ('DEMO_LIVE_START','DEMO_LIVE_STOP')").query(Long.class).single();
        set(c, true);
        set(c, true);                                                                       // heartbeat, not a new start
        set(c, true);
        set(c, false);
        set(c, false);                                                                      // already stopped
        long after = jdbc.sql("SELECT count(*) FROM audit_logs WHERE action IN ('DEMO_LIVE_START','DEMO_LIVE_STOP')").query(Long.class).single();
        assertThat(after - before).isEqualTo(2);
    }
}
