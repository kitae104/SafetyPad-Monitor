package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import kr.safetypad.monitor.demo.DemoController;
import kr.safetypad.monitor.demo.DemoFixtureService;
import kr.safetypad.monitor.demo.DemoLiveGenerator;
import kr.safetypad.monitor.device.DevDeviceProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** Without the dev profile (production-like) demo code must not exist at all. */
@ActiveProfiles(profiles = "prod", inheritProfiles = false)
class ProductionProfileIT extends AbstractPostgresIT {
    @Autowired ApplicationContext ctx;
    @Autowired TestRestTemplate rest;

    @Test
    void demoAndDevBeansAreAbsent() {
        assertThat(ctx.getBeanNamesForType(DemoController.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DemoFixtureService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DemoLiveGenerator.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DevDeviceProvisioner.class)).isEmpty();
    }

    @Test
    void demoEndpointsDoNotExistEvenForLoggedInAdmin() {
        var c = AdminClient.loggedIn(rest);
        assertThat(c.post("/api/v1/admin/dev/import-demo", "{}").getStatusCode().value()).isIn(404, 405);
        assertThat(c.post("/api/v1/admin/dev/live-generator", "{\"enabled\":true}").getStatusCode().value()).isIn(404, 405);
    }
}
