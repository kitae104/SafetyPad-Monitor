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

class MetricsIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired DeviceKeyService keys;

    private int scrape(String user, String pass) {
        HttpHeaders h = new HttpHeaders();
        if (user != null) h.setBasicAuth(user, pass);
        return rest.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode().value();
    }

    @Test
    void prometheusNeedsItsOwnCredentials() {
        assertThat(scrape(null, null)).isEqualTo(401);
        assertThat(scrape("metrics-test", "wrong-password-wrong")).isEqualTo(401);
        assertThat(scrape(ADMIN_USER, ADMIN_PASSWORD)).isEqualTo(401);                // admin account is not a metrics identity
        assertThat(scrape("metrics-test", "metrics-test-password-123456")).isEqualTo(200);
    }

    @Test
    void ingestOutcomesAreCounted() {
        String id = "MT-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, null, null, false);
        String key = keys.issue(id).plaintextKey();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", key);
        String body = "{\"event_id\":\"M1\",\"device_id\":\"" + id + "\",\"event_time\":\"" + java.time.OffsetDateTime.now().minusMinutes(1) + "\"}";
        rest.postForEntity("/api/v1/device-events", new HttpEntity<>(body, h), String.class);
        rest.postForEntity("/api/v1/device-events", new HttpEntity<>(body, h), String.class);
        HttpHeaders mh = new HttpHeaders();
        mh.setBasicAuth("metrics-test", "metrics-test-password-123456");
        String text = rest.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(mh), String.class).getBody();
        assertThat(text).contains("safetypad_ingest_events_total{result=\"accepted\"")
                .contains("safetypad_ingest_events_total{result=\"duplicate\"")
                .contains("http_server_requests_seconds");
        assertThat(text).doesNotContain(key);
    }
}
