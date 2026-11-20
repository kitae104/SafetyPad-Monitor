package kr.safetypad.monitor.demo;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import kr.safetypad.monitor.audit.AuditService;
import kr.safetypad.monitor.ingestion.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEV ONLY (see {@link DemoFixtureService}). Absent in production, where these paths simply do not exist. */
@RestController
@Profile("dev")
@ConditionalOnProperty(name = "safetypad.simulated-data-enabled", havingValue = "true")
@RequestMapping("/api/v1/admin/dev")
public class DemoController {
    record ImportRequest(Boolean shiftToToday) {}
    record ResetRequest(String confirm) {}
    record LiveRequest(Boolean enabled) {}

    public static final String RESET_CONFIRMATION = "RESET_DEMO_DATA";

    private final DemoFixtureService fixtures;
    private final DemoLiveGenerator live;
    private final AuditService audit;

    public DemoController(DemoFixtureService fixtures, DemoLiveGenerator live, AuditService audit) {
        this.fixtures = fixtures;
        this.live = live;
        this.audit = audit;
    }

    @PostMapping("/import-demo")
    Map<String, Object> importDemo(@RequestBody(required = false) ImportRequest r, HttpServletRequest req) {
        boolean shift = r == null || r.shiftToToday() == null || r.shiftToToday();
        Map<String, Object> result = fixtures.importDemo(shift);
        audit.record("DEMO_IMPORT", "simulated", result, req);
        return result;
    }

    @PostMapping("/reset-demo")
    Map<String, Object> resetDemo(@RequestBody ResetRequest r, HttpServletRequest req) {
        if (r == null || !RESET_CONFIRMATION.equals(r.confirm())) {
            throw ApiException.badRequest("confirm must equal " + RESET_CONFIRMATION);
        }
        Map<String, Object> result = fixtures.resetDemo();
        audit.record("DEMO_RESET", "simulated", result, req);
        return result;
    }

    @GetMapping("/live-generator")
    DemoLiveGenerator.Status liveStatus() {
        return live.status();
    }

    /** enabled=true starts or renews (heartbeat from the browser); enabled=false stops. Only start/stop are audited. */
    @PostMapping("/live-generator")
    DemoLiveGenerator.Status setLive(@RequestBody(required = false) LiveRequest r, HttpServletRequest req) {
        if (r == null || r.enabled() == null) {
            throw ApiException.badRequest("enabled must be true or false");
        }
        var t = live.setEnabled(r.enabled());
        if (t.changed()) {
            audit.record(r.enabled() ? "DEMO_LIVE_START" : "DEMO_LIVE_STOP", "simulated",
                    Map.of("generated", t.status().generated()), req);
        }
        return t.status();
    }
}
