package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import kr.safetypad.monitor.device.DeviceKeyService;
import kr.safetypad.monitor.query.SummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AdminQueryIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired DeviceKeyService keys;
    @Autowired JdbcClient jdbc;
    @Autowired SummaryService summary;
    @Autowired ObjectMapper mapper;

    String newDevice(String alias) {
        String id = "Q-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, alias, "zone", false);
        return id;
    }

    /** Direct insert is allowed (only UPDATE/DELETE are blocked); lets tests pin exact instants. */
    void insertEvent(String deviceId, String eventId, String eventTimeUtc, String receivedUtc, Double lat, Double lon, Integer battery) {
        jdbc.sql("""
                INSERT INTO device_events (device_pk, event_id, event_type, event_time, received_at, gps_lat, gps_lon, battery_level, raw_payload)
                VALUES ((SELECT id FROM devices WHERE device_id=:d), :e, 'MULTI_RIDE', :et, :rt, :lat, :lon, :b, '{}'::jsonb)
                """).param("d", deviceId).param("e", eventId)
                .param("et", Timestamp.from(Instant.parse(eventTimeUtc))).param("rt", Timestamp.from(Instant.parse(receivedUtc)))
                .param("lat", lat).param("lon", lon).param("b", battery).update();
    }

    JsonNode json(String body) { return mapper.readTree(body); }

    @Test
    void deviceListCanHideInactiveDevices() {
        String tag = "HIDE-" + UUID.randomUUID().toString().substring(0, 6);
        keys.registerDevice(tag + "-ON", "on", null, false);
        keys.registerDevice(tag + "-OFF", "off", null, false);
        jdbc.sql("UPDATE devices SET active = false WHERE device_id = :d").param("d", tag + "-OFF").update();
        var c = AdminClient.loggedIn(rest);

        JsonNode all = json(c.get("/api/v1/admin/devices?q=" + tag).getBody());
        assertThat(all.get("total").asInt()).isEqualTo(2);                                  // default: nothing is hidden
        JsonNode activeOnly = json(c.get("/api/v1/admin/devices?q=" + tag + "&active=true").getBody());
        assertThat(activeOnly.get("total").asInt()).isEqualTo(1);
        assertThat(activeOnly.get("items").get(0).get("device_id").asString()).isEqualTo(tag + "-ON");
        assertThat(activeOnly.get("items").get(0).get("active").asBoolean()).isTrue();
        JsonNode inactiveOnly = json(c.get("/api/v1/admin/devices?q=" + tag + "&active=false").getBody());
        assertThat(inactiveOnly.get("total").asInt()).isEqualTo(1);
        assertThat(inactiveOnly.get("items").get(0).get("device_id").asString()).isEqualTo(tag + "-OFF");
    }

    @Test
    void deviceListMatchesTheDashboardCardsLowBatteryAndRecentlyReceived() {
        String tag = "CARD-" + UUID.randomUUID().toString().substring(0, 6);
        for (String s : new String[] {"-LOW", "-OK", "-OLD", "-OFFLOW"}) keys.registerDevice(tag + s, s, null, false);
        jdbc.sql("UPDATE devices SET last_battery_level = 10, last_received_at = now() WHERE device_id = :d").param("d", tag + "-LOW").update();
        jdbc.sql("UPDATE devices SET last_battery_level = 80, last_received_at = now() WHERE device_id = :d").param("d", tag + "-OK").update();
        jdbc.sql("UPDATE devices SET last_battery_level = 90, last_received_at = now() - interval '3 days' WHERE device_id = :d").param("d", tag + "-OLD").update();
        jdbc.sql("UPDATE devices SET active = false, last_battery_level = 5, last_received_at = now() WHERE device_id = :d").param("d", tag + "-OFFLOW").update();
        var c = AdminClient.loggedIn(rest);

        // Same rule as the card: active AND last reported level <= threshold (inactive devices are not counted)
        JsonNode low = json(c.get("/api/v1/admin/devices?q=" + tag + "&low_battery=true").getBody());
        assertThat(low.get("total").asInt()).isEqualTo(1);
        assertThat(low.get("items").get(0).get("device_id").asString()).isEqualTo(tag + "-LOW");

        // Same rule as the card: last_received_at within 24h (no online/offline claim)
        JsonNode recent = json(c.get("/api/v1/admin/devices?q=" + tag + "&received_within_hours=24").getBody());
        assertThat(recent.get("total").asInt()).isEqualTo(3);
        JsonNode wide = json(c.get("/api/v1/admin/devices?q=" + tag + "&received_within_hours=96").getBody());
        assertThat(wide.get("total").asInt()).isEqualTo(4);

        assertThat(c.get("/api/v1/admin/devices?received_within_hours=0").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void dateFilterUsesSeoulCalendarDaysAndOutputIsKst() {
        String d = newDevice("tz");
        // 2026-11-05T14:30Z = 23:30 KST on 11-05 ; 2026-11-05T15:30Z = 00:30 KST on 11-06
        insertEvent(d, "A", "2026-11-05T14:30:00Z", "2026-11-05T14:30:05Z", 37.45, 126.65, 50);
        insertEvent(d, "B", "2026-11-05T15:30:00Z", "2026-11-05T15:30:05Z", 37.45, 126.65, 50);
        var c = AdminClient.loggedIn(rest);
        JsonNode day5 = json(c.get("/api/v1/admin/events?device_id=" + d + "&from=2026-11-05&to=2026-11-05").getBody());
        JsonNode day6 = json(c.get("/api/v1/admin/events?device_id=" + d + "&from=2026-11-06&to=2026-11-06").getBody());
        assertThat(day5.get("total").asLong()).isEqualTo(1);
        assertThat(day5.get("items").get(0).get("event_id").asString()).isEqualTo("A");
        assertThat(day5.get("items").get(0).get("event_time").asString()).isEqualTo("2026-11-05T23:30:00+09:00");
        assertThat(day6.get("total").asLong()).isEqualTo(1);
        assertThat(day6.get("items").get(0).get("event_id").asString()).isEqualTo("B");
        assertThat(day6.get("items").get(0).get("received_at").asString()).isEqualTo("2026-11-06T00:30:05+09:00");
    }

    @Test
    void filtersPaginationAndSortingAreAccurate() {
        String d = newDevice("flt");
        for (int i = 0; i < 5; i++) {
            insertEvent(d, "P" + i, "2026-10-0" + (i + 1) + "T01:00:00Z", "2026-10-0" + (i + 1) + "T01:0" + (i == 4 ? "9" : "0") + ":0" + i + "Z",
                    i % 2 == 0 ? 37.4 : null, i % 2 == 0 ? 126.6 : null, 40);
        }
        var c = AdminClient.loggedIn(rest);
        String base = "/api/v1/admin/events?device_id=" + d;
        JsonNode p0 = json(c.get(base + "&size=2&page=0").getBody());
        JsonNode p2 = json(c.get(base + "&size=2&page=2").getBody());
        assertThat(p0.get("total").asLong()).isEqualTo(5);
        assertThat(p0.get("items")).hasSize(2);
        assertThat(p0.get("items").get(0).get("event_id").asString()).isEqualTo("P4");   // default desc by event_time
        assertThat(p2.get("items")).hasSize(1);
        assertThat(json(c.get(base + "&dir=asc&size=1").getBody()).get("items").get(0).get("event_id").asString()).isEqualTo("P0");
        assertThat(json(c.get(base + "&has_gps=true").getBody()).get("total").asLong()).isEqualTo(3);
        assertThat(json(c.get(base + "&has_gps=false").getBody()).get("total").asLong()).isEqualTo(2);
        JsonNode late = json(c.get(base + "&min_lag_seconds=300").getBody());
        assertThat(late.get("total").asLong()).isEqualTo(1);
        assertThat(late.get("items").get(0).get("lag_seconds").asLong()).isGreaterThanOrEqualTo(540);
    }

    @Test
    void invalidParametersAre400() {
        var c = AdminClient.loggedIn(rest);
        assertThat(c.get("/api/v1/admin/events?from=not-a-date").getStatusCode().value()).isEqualTo(400);
        assertThat(c.get("/api/v1/admin/events?from=2026-11-06&to=2026-11-01").getStatusCode().value()).isEqualTo(400);
        assertThat(c.get("/api/v1/admin/events?size=100000").getStatusCode().value()).isEqualTo(400);
        assertThat(c.get("/api/v1/admin/events?sort=password_hash").getStatusCode().value()).isEqualTo(400);
        assertThat(c.get("/api/v1/admin/events?device_id=%27%3B%20DROP%20TABLE%20devices%3B--").getStatusCode().value()).isEqualTo(200);
        assertThat(c.get("/api/v1/admin/events/99999999").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void clockSkewIsFlaggedWhenReceivedBeforeEventTime() {
        String d = newDevice("skew");
        insertEvent(d, "S", "2026-10-01T10:10:00Z", "2026-10-01T10:00:00Z", null, null, null);
        var c = AdminClient.loggedIn(rest);
        JsonNode row = json(c.get("/api/v1/admin/events?device_id=" + d).getBody()).get("items").get(0);
        assertThat(row.get("clock_skew_suspected").asBoolean()).isTrue();
        assertThat(row.get("lag_seconds").asLong()).isEqualTo(-600);
    }

    @Test
    void csvHasBomKstTimesAndNeutralisesFormulas() {
        String d = newDevice("=HYPERLINK(\"http://evil\",\"x\")");
        insertEvent(d, "C1", "2026-11-05T14:30:00Z", "2026-11-05T14:30:07Z", 37.451448, 126.651542, 33);
        var c = AdminClient.loggedIn(rest);
        var res = c.get("/api/v1/admin/events/export.csv?device_id=" + d);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("text/csv");
        String body = res.getBody();
        assertThat(body).startsWith("﻿\"event_id\"");
        assertThat(body).contains("'=HYPERLINK(");                       // neutralised: leading quote inside the cell
        assertThat(body).doesNotContain("\"=HYPERLINK");
        assertThat(body).contains("2026-11-05T23:30:00+09:00").contains("37.451448");
        assertThat(body).contains("\r\n");
    }

    @Test
    void csvSanitizerCoversAllFormulaPrefixes() {
        for (String bad : new String[]{"=1+1", "+1", "-1", "@SUM(A1)", "\t=1", "\r=1", " =1"}) {
            assertThat(kr.safetypad.monitor.query.CsvWriter.sanitize(bad)).startsWith("'");
        }
        assertThat(kr.safetypad.monitor.query.CsvWriter.sanitize("PAD-0001")).isEqualTo("PAD-0001");
        assertThat(kr.safetypad.monitor.query.CsvWriter.textCell("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test
    void exportAndAdminChangesAreAuditedAndAuditIsAppendOnly() {
        String d = newDevice("aud");
        var c = AdminClient.loggedIn(rest);
        c.get("/api/v1/admin/events/export.csv?device_id=" + d);
        var created = c.post("/api/v1/admin/devices", "{\"device_id\":\"AUD-" + d + "\",\"alias\":\"x\"}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action='EXPORT_CSV' AND actor=:a").param("a", ADMIN_USER).query(Long.class).single())
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action='DEVICE_CREATE' AND target=:t").param("t", "AUD-" + d).query(Long.class).single())
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_logs SET actor='x'").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_logs").update()).hasMessageContaining("append-only");
    }

    @Test
    void keyIssueIsConfirmedAuditedShownOnceAndUsable() {
        String d = newDevice("key");
        var c = AdminClient.loggedIn(rest);
        assertThat(c.post("/api/v1/admin/devices/" + d + "/credentials", "{}").getStatusCode().value()).isEqualTo(400);
        var issued = c.post("/api/v1/admin/devices/" + d + "/credentials", "{\"confirm\":true}");
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        String key = json(issued.getBody()).get("api_key").asString();
        assertThat(key).startsWith("spk_");
        String detail = c.get("/api/v1/admin/devices/" + d).getBody();
        assertThat(detail).doesNotContain(key).doesNotContain("key_digest");
        assertThat(detail).contains("key_prefix");
        assertThat(jdbc.sql("SELECT detail::text FROM audit_logs WHERE action='KEY_ISSUE' AND target=:t").param("t", d).query(String.class).single())
                .doesNotContain(key);
        assertThat(keys.authenticate(key)).isPresent();
        long credId = json(issued.getBody()).get("credential_id").asLong();
        assertThat(c.post("/api/v1/admin/devices/" + d + "/credentials/" + credId + "/revoke", "{\"confirm\":true}").getStatusCode().value()).isEqualTo(200);
        assertThat(keys.authenticate(key)).isEmpty();
    }

    @Test
    void summaryCountsDaysInSeoulTimeAndDoesNotClaimOnline() {
        String d = newDevice("sum");
        // Pin "now" = 2030-01-07T03:00Z = 12:00 KST on 2030-01-07. Window is far from other tests' data.
        insertEvent(d, "T1", "2030-01-06T14:59:00Z", "2030-01-06T14:59:03Z", 37.4, 126.6, 10);  // 23:59 KST 01-06
        insertEvent(d, "T2", "2030-01-06T15:00:00Z", "2030-01-06T15:00:30Z", 37.4, 126.6, 10);  // 00:00 KST 01-07 (today)
        insertEvent(d, "T3", "2030-01-07T01:00:00Z", "2030-01-07T01:20:00Z", null, null, 10);    // today, 20 min late
        var s = mapper.valueToTree(summary.summary(Instant.parse("2030-01-07T03:00:00Z")));
        assertThat(s.get("violations_today").asLong()).isEqualTo(2);
        JsonNode trend = s.get("daily_trend");
        assertThat(trend).hasSize(7);
        assertThat(trend.get(6).get("date").asString()).isEqualTo("2030-01-07");
        assertThat(trend.get(6).get("count").asLong()).isEqualTo(2);
        assertThat(trend.get(5).get("date").asString()).isEqualTo("2030-01-06");
        assertThat(trend.get(5).get("count").asLong()).isEqualTo(1);
        assertThat(s.get("top_devices").get(0).get("device_id").asString()).isEqualTo(d);
        assertThat(s.get("lag_distribution").get("over_10m").asLong()).isEqualTo(1);
        assertThat(s.has("devices_received_last_24h")).isTrue();
        assertThat(s.toString()).doesNotContain("online");
        assertThat(s.get("period").get("timezone").asString()).isEqualTo("Asia/Seoul");
    }

    @Test
    void deviceDetailShowsLastKnownViolationGpsLabelledAsEventSource() {
        String d = newDevice("gps");
        insertEvent(d, "G1", "2026-10-01T00:00:00Z", "2026-10-01T00:00:01Z", 37.1, 126.1, 90);
        insertEvent(d, "G2", "2026-10-02T00:00:00Z", "2026-10-02T00:00:01Z", null, null, 80);
        var c = AdminClient.loggedIn(rest);
        JsonNode dev = json(c.get("/api/v1/admin/devices/" + d).getBody()).get("device");
        assertThat(dev.get("event_count").asLong()).isEqualTo(2);
        assertThat(dev.get("last_known_gps").get("source").asString()).isEqualTo("VIOLATION_EVENT");
        assertThat(dev.get("last_known_gps").get("latitude").decimalValue().doubleValue()).isEqualTo(37.1);
        assertThat(c.get("/api/v1/admin/devices/NOPE-NOPE").getStatusCode().value()).isEqualTo(404);
    }
}
