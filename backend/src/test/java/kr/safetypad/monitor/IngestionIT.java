package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.safetypad.monitor.device.DeviceKeyService;
import kr.safetypad.monitor.device.DeviceKeyService.IssuedKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

@ExtendWith(OutputCaptureExtension.class)
class IngestionIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;
    @Autowired DeviceKeyService keys;
    @Autowired JdbcClient jdbc;

    record Dev(String id, IssuedKey key) {}

    Dev newDevice() {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, "test", "test", false);
        return new Dev(id, keys.issue(id));
    }

    String eventJson(String deviceId, String eventId, String gps, String extra) {
        String time = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusMinutes(1).withNano(0).toString();
        return "{\"event_id\":\"" + eventId + "\",\"device_id\":\"" + deviceId + "\",\"event_type\":\"MULTI_RIDE\","
                + "\"event_time\":\"" + time + "\",\"gpsData\":" + gps + ",\"battery_level\":55" + extra + "}";
    }

    static final String GPS = "{\"latitude\":37.451448,\"longitude\":126.651542}";

    ResponseEntity<String> post(String key, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.set("X-API-Key", key);
        return rest.postForEntity("/api/v1/device-events", new HttpEntity<>(body, h), String.class);
    }

    long count(String deviceId) {
        return jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id = e.device_pk WHERE d.device_id = :d")
                .param("d", deviceId).query(Long.class).single();
    }

    @Test
    void validEventIsStoredAndAcknowledged() {
        Dev d = newDevice();
        var res = post(d.key().plaintextKey(), eventJson(d.id(), "E1", GPS, ""));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"success\":true").contains("\"duplicate\":false").contains("\"event_id\":\"E1\"");
        assertThat(count(d.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_received_at IS NOT NULL AND last_battery_level = 55 FROM devices WHERE device_id=:d")
                .param("d", d.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void tenRetriesKeepOneRowAndOriginalReceivedAt() throws Exception {
        Dev d = newDevice();
        String body = eventJson(d.id(), "RETRY", GPS, "");
        post(d.key().plaintextKey(), body);
        Timestamp first = jdbc.sql("SELECT received_at FROM device_events e JOIN devices x ON x.id=e.device_pk WHERE x.device_id=:d")
                .param("d", d.id()).query(Timestamp.class).single();
        Thread.sleep(20);
        for (int i = 0; i < 9; i++) {
            var res = post(d.key().plaintextKey(), body);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody()).contains("\"duplicate\":true");
        }
        assertThat(count(d.id())).isEqualTo(1);
        Timestamp after = jdbc.sql("SELECT received_at FROM device_events e JOIN devices x ON x.id=e.device_pk WHERE x.device_id=:d")
                .param("d", d.id()).query(Timestamp.class).single();
        assertThat(after).isEqualTo(first);
    }

    @Test
    void concurrentIdenticalPostsInsertExactlyOnce() throws Exception {
        Dev d = newDevice();
        String body = eventJson(d.id(), "RACE", GPS, "");
        int n = 20;
        var pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) tasks.add(() -> { go.await(); return post(d.key().plaintextKey(), body); });
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (var t : tasks) futures.add(pool.submit(t));
        go.countDown();
        int fresh = 0, dup = 0;
        for (var f : futures) {
            var r = f.get();
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            if (r.getBody().contains("\"duplicate\":false")) fresh++; else dup++;
        }
        pool.shutdown();
        assertThat(fresh).isEqualTo(1);
        assertThat(dup).isEqualTo(n - 1);
        assertThat(count(d.id())).isEqualTo(1);
    }

    @Test
    void authenticationFailures() {
        Dev d = newDevice();
        String body = eventJson(d.id(), "A1", GPS, "");
        assertThat(post(null, body).getStatusCode().value()).isEqualTo(401);
        assertThat(post("spk_nope", body).getStatusCode().value()).isEqualTo(401);
        keys.revoke(d.key().credentialId());
        assertThat(post(d.key().plaintextKey(), body).getStatusCode().value()).isEqualTo(401);
        assertThat(count(d.id())).isZero();
    }

    @Test
    void keyOfAnotherDeviceIsForbidden() {
        Dev a = newDevice();
        Dev b = newDevice();
        var res = post(a.key().plaintextKey(), eventJson(b.id(), "X1", GPS, ""));
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(count(b.id())).isZero();
    }

    @Test
    void rotationAllowsOldAndNewKeyUntilRevoked() {
        Dev d = newDevice();
        IssuedKey second = keys.issue(d.id());
        assertThat(post(d.key().plaintextKey(), eventJson(d.id(), "R1", GPS, "")).getStatusCode().value()).isEqualTo(200);
        assertThat(post(second.plaintextKey(), eventJson(d.id(), "R2", GPS, "")).getStatusCode().value()).isEqualTo(200);
        keys.revoke(d.key().credentialId());
        assertThat(post(d.key().plaintextKey(), eventJson(d.id(), "R3", GPS, "")).getStatusCode().value()).isEqualTo(401);
        assertThat(post(second.plaintextKey(), eventJson(d.id(), "R3", GPS, "")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void plaintextKeyIsNeverStored() {
        Dev d = newDevice();
        Long hits = jdbc.sql("SELECT count(*) FROM device_credentials WHERE key_digest = :k OR key_prefix = :k")
                .param("k", d.key().plaintextKey()).query(Long.class).single();
        assertThat(hits).isZero();
        assertThat(d.key().toString()).doesNotContain(d.key().plaintextKey());
    }

    @Test
    void structuralErrorsAre400() {
        Dev d = newDevice();
        String k = d.key().plaintextKey();
        assertThat(post(k, "{not json").getStatusCode().value()).isEqualTo(400);
        assertThat(post(k, "[]").getStatusCode().value()).isEqualTo(400);
        assertThat(post(k, "{\"device_id\":\"" + d.id() + "\",\"event_time\":\"2026-01-01T00:00:00Z\"}").getStatusCode().value()).isEqualTo(400);
        assertThat(post(k, eventJson(d.id(), "U1", GPS, ",\"extra\":1")).getStatusCode().value()).isEqualTo(400);
        assertThat(count(d.id())).isZero();
    }

    @Test
    void semanticErrorsAre422() {
        Dev d = newDevice();
        String k = d.key().plaintextKey();
        String badLat = "{\"latitude\":91,\"longitude\":126.6}";
        String badLon = "{\"latitude\":37.4,\"longitude\":-181}";
        assertThat(post(k, eventJson(d.id(), "S1", badLat, "")).getStatusCode().value()).isEqualTo(422);
        assertThat(post(k, eventJson(d.id(), "S2", badLon, "")).getStatusCode().value()).isEqualTo(422);
        assertThat(post(k, eventJson(d.id(), "S3", "\"abc\"", "")).getStatusCode().value()).isEqualTo(422);
        assertThat(post(k, eventJson(d.id(), "S4", GPS, "").replace("\"battery_level\":55", "\"battery_level\":101")).getStatusCode().value()).isEqualTo(422);
        assertThat(post(k, eventJson(d.id(), "S5", GPS, "").replace("MULTI_RIDE", "OTHER")).getStatusCode().value()).isEqualTo(422);
        String noTz = eventJson(d.id(), "S6", GPS, "").replaceAll("\"event_time\":\"[^\"]*\"", "\"event_time\":\"2026-11-05T14:30:10\"");
        assertThat(post(k, noTz).getStatusCode().value()).isEqualTo(422);
        String future = eventJson(d.id(), "S7", GPS, "").replaceAll("\"event_time\":\"[^\"]*\"",
                "\"event_time\":\"" + Instant.now().plusSeconds(86_400) + "\"");
        assertThat(post(k, future).getStatusCode().value()).isEqualTo(422);
        assertThat(post(k, eventJson("bad id!", "S8", GPS, "")).getStatusCode().value()).isEqualTo(403);
        assertThat(count(d.id())).isZero();
    }

    @Test
    void nullAndEmptyGpsAreStoredAsNullButEventKept() {
        Dev d = newDevice();
        String k = d.key().plaintextKey();
        assertThat(post(k, eventJson(d.id(), "G1", "null", "")).getStatusCode().value()).isEqualTo(200);
        assertThat(post(k, eventJson(d.id(), "G2", "\"\"", "")).getStatusCode().value()).isEqualTo(200);
        assertThat(post(k, eventJson(d.id(), "G3", GPS, "")).getStatusCode().value()).isEqualTo(200);
        long nulls = jdbc.sql("SELECT count(*) FROM device_events e JOIN devices x ON x.id=e.device_pk WHERE x.device_id=:d AND gps_lat IS NULL AND gps_lon IS NULL")
                .param("d", d.id()).query(Long.class).single();
        assertThat(nulls).isEqualTo(2);
        assertThat(count(d.id())).isEqualTo(3);
    }

    @Test
    void eventTimeKeepsInstantAcrossOffsets() {
        Dev d = newDevice();
        String t = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusMinutes(2).withNano(0).toString();
        String body = eventJson(d.id(), "T1", GPS, "").replaceAll("\"event_time\":\"[^\"]*\"", "\"event_time\":\"" + t + "\"");
        post(d.key().plaintextKey(), body);
        Timestamp stored = jdbc.sql("SELECT event_time FROM device_events e JOIN devices x ON x.id=e.device_pk WHERE x.device_id=:d")
                .param("d", d.id()).query(Timestamp.class).single();
        assertThat(stored.toInstant()).isEqualTo(OffsetDateTime.parse(t).toInstant());
    }

    @Test
    void oversizedBodyIs413() {
        Dev d = newDevice();
        String big = eventJson(d.id(), "BIG", GPS, ",\"pad\":\"" + "x".repeat(9000) + "\"");
        assertThat(post(d.key().plaintextKey(), big).getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void rawEventsAreImmutable() {
        Dev d = newDevice();
        post(d.key().plaintextKey(), eventJson(d.id(), "IMM", GPS, ""));
        assertThatThrownBy(() -> jdbc.sql("UPDATE device_events SET received_at = now() WHERE event_id = 'IMM'").update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM device_events WHERE event_id = 'IMM'").update())
                .hasMessageContaining("immutable");
        assertThat(count(d.id())).isEqualTo(1);
    }

    @Test
    void apiKeyNeverAppearsInLogsOrResponses(CapturedOutput out) {
        Dev d = newDevice();
        String key = d.key().plaintextKey();
        var ok = post(key, eventJson(d.id(), "LOG1", GPS, ""));
        var bad = post(key, "{not json");
        var wrong = post(key + "x", eventJson(d.id(), "LOG2", GPS, ""));
        assertThat(out.getAll()).doesNotContain(key);
        assertThat(ok.getBody() + bad.getBody() + wrong.getBody()).doesNotContain(key);
        assertThat(ok.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }
}
