package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MapRouteIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired DeviceKeyService keys;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    String device() {
        String id = "M-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, "map", null, false);
        return id;
    }

    void event(String d, String eid, String t, Double lat, Double lon) {
        jdbc.sql("""
                INSERT INTO device_events (device_pk, event_id, event_type, event_time, received_at, gps_lat, gps_lon, raw_payload)
                VALUES ((SELECT id FROM devices WHERE device_id=:d), :e, 'MULTI_RIDE', :t, :t, :lat, :lon, '{}'::jsonb)""")
                .param("d", d).param("e", eid).param("t", Timestamp.from(Instant.parse(t))).param("lat", lat).param("lon", lon).update();
    }

    void ping(String d, String t, double lat, double lon) {
        jdbc.sql("""
                INSERT INTO device_locations (device_pk, recorded_at, received_at, lat, lon, source)
                VALUES ((SELECT id FROM devices WHERE device_id=:d), :t, :t, :lat, :lon, 'DEMO_GPS_PING')""")
                .param("d", d).param("t", Timestamp.from(Instant.parse(t))).param("lat", lat).param("lon", lon).update();
    }

    JsonNode json(String s) { return mapper.readTree(s); }

    @Test
    void mapEventsExcludeNullGpsButCountThem() {
        String d = device();
        event(d, "A", "2026-10-01T01:00:00Z", 37.45, 126.65);
        event(d, "B", "2026-10-01T02:00:00Z", null, null);
        event(d, "C", "2026-10-01T03:00:00Z", null, null);
        var c = AdminClient.loggedIn(rest);
        JsonNode r = json(c.get("/api/v1/admin/map/events?device_id=" + d).getBody());
        assertThat(r.get("points")).hasSize(1);
        assertThat(r.get("points").get(0).get("point_type").asString()).isEqualTo("VIOLATION");
        assertThat(r.get("missing_gps_count").asLong()).isEqualTo(2);
        // the table side still has all three rows
        assertThat(json(c.get("/api/v1/admin/events?device_id=" + d).getBody()).get("total").asLong()).isEqualTo(3);
    }

    @Test
    void mapEventsRespectDeviceAndPeriodFilters() {
        String a = device(), b = device();
        event(a, "A1", "2026-10-01T01:00:00Z", 37.1, 126.1);
        event(b, "B1", "2026-10-01T01:00:00Z", 37.2, 126.2);
        event(a, "A2", "2026-10-09T01:00:00Z", 37.3, 126.3);
        var c = AdminClient.loggedIn(rest);
        assertThat(json(c.get("/api/v1/admin/map/events?device_id=" + a).getBody()).get("points")).hasSize(2);
        assertThat(json(c.get("/api/v1/admin/map/events?device_id=" + a + "&from=2026-10-09&to=2026-10-09").getBody()).get("points")).hasSize(1);
        assertThat(c.get("/api/v1/admin/map/events?from=2026-10-09&to=2026-10-01").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void routeIsNeverInferredFromViolationEvents() {
        String d = device();
        for (int i = 0; i < 5; i++) event(d, "V" + i, "2026-10-01T0" + i + ":00:00Z", 37.40 + i * 0.001, 126.60);
        var c = AdminClient.loggedIn(rest);
        JsonNode r = json(c.get("/api/v1/admin/devices/" + d + "/route").getBody());
        assertThat(r.get("sufficient").asBoolean()).isFalse();
        assertThat(r.get("points")).isEmpty();
        assertThat(r.get("disclaimer").asString()).contains("실제 도로");
    }

    @Test
    void singlePingIsNotARoute() {
        String d = device();
        ping(d, "2026-10-01T01:00:00Z", 37.4, 126.6);
        var r = json(AdminClient.loggedIn(rest).get("/api/v1/admin/devices/" + d + "/route").getBody());
        assertThat(r.get("points")).hasSize(1);
        assertThat(r.get("sufficient").asBoolean()).isFalse();
    }

    @Test
    void routeUsesLatestDayByDefaultMarksGapsAndKeepsOtherDevicesOut() {
        String d = device(), other = device();
        ping(d, "2026-09-30T01:00:00Z", 37.0, 126.0);                       // older day: excluded by default
        ping(d, "2026-10-01T00:00:00Z", 37.40, 126.60);
        ping(d, "2026-10-01T00:15:00Z", 37.41, 126.61);
        ping(d, "2026-10-01T02:00:00Z", 37.42, 126.62);                      // 105 min later -> gap
        ping(other, "2026-10-01T00:30:00Z", 38.0, 127.0);
        var r = json(AdminClient.loggedIn(rest).get("/api/v1/admin/devices/" + d + "/route").getBody());
        assertThat(r.get("sufficient").asBoolean()).isTrue();
        assertThat(r.get("points")).hasSize(3);
        assertThat(r.get("gaps").asLong()).isEqualTo(1);
        assertThat(r.get("points").get(2).get("gap_before").asBoolean()).isTrue();
        assertThat(r.get("points").get(1).get("gap_before").asBoolean()).isFalse();
        assertThat(r.get("points").get(0).get("point_type").asString()).isEqualTo("PERIODIC");
        assertThat(r.get("range").get("from").asString()).isEqualTo("2026-10-01");
        assertThat(r.get("days_available")).hasSize(2);
        assertThat(r.toString()).doesNotContain("38.0");                     // other device's ping not leaked
        var wide = json(AdminClient.loggedIn(rest).get("/api/v1/admin/devices/" + d + "/route?from=2026-09-30&to=2026-10-01").getBody());
        assertThat(wide.get("points")).hasSize(4);
    }

    @Test
    void unknownDeviceIs404() {
        assertThat(AdminClient.loggedIn(rest).get("/api/v1/admin/devices/NOPE-X/route").getStatusCode().value()).isEqualTo(404);
    }
}
