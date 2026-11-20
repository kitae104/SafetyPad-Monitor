package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;

class HealthSmokeTest extends AbstractPostgresIT {

    @Autowired TestRestTemplate rest;

    @Test
    void healthIsUp() {
        var res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("UP");
    }

    @Test
    void otherPathsDeniedByDefault() {
        var res = rest.getForEntity("/api/v1/anything", String.class);
        assertThat(res.getStatusCode().value()).isIn(401, 403);
    }
}
