package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "safetypad.ingest.per-key-per-minute=3")
class RateLimitIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;
    @Autowired DeviceKeyService keys;

    @Test
    void excessRequestsPerKeyGet429WithRetryAfter() {
        String id = "RL-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, null, null, false);
        String key = keys.issue(id).plaintextKey();
        int tooMany = 0;
        for (int i = 0; i < 6; i++) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("X-API-Key", key);
            String body = "{\"event_id\":\"E" + i + "\",\"device_id\":\"" + id + "\",\"event_time\":\""
                    + OffsetDateTime.now().minusMinutes(1) + "\"}";
            var res = rest.postForEntity("/api/v1/device-events", new HttpEntity<>(body, h), String.class);
            if (res.getStatusCode().value() == 429) {
                tooMany++;
                assertThat(res.getHeaders().getFirst("Retry-After")).isNotBlank();
            }
        }
        assertThat(tooMany).isGreaterThanOrEqualTo(2);
    }
}
