package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/** Production default: the route feature is OFF until the firmware GPS-ping contract is approved. */
@TestPropertySource(properties = "safetypad.route-enabled=false")
class RouteDisabledIT extends AbstractPostgresIT {
    @Autowired TestRestTemplate rest;

    @Test
    void routeEndpointIsUnavailableAndConfigSaysSo() {
        var c = AdminClient.loggedIn(rest);
        var res = c.get("/api/v1/admin/devices/PAD-0001/route");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).contains("ROUTE_DISABLED");
        assertThat(c.get("/api/v1/admin/config").getBody()).contains("\"route_enabled\":false");
    }
}
