package kr.safetypad.monitor.ingestion;

import java.util.Map;
import kr.safetypad.monitor.device.DeviceKeyService.AuthenticatedDevice;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class IngestionController {

    private final EventIntake intake;

    public IngestionController(EventIntake intake) {
        this.intake = intake;
    }

    @PostMapping("/api/v1/device-events")
    ResponseEntity<Map<String, Object>> receive(@RequestBody JsonNode body, Authentication auth) {
        AuthenticatedDevice device = (AuthenticatedDevice) auth.getPrincipal();
        IngestionService.Result r = intake.accept(device, body);
        return ResponseEntity.ok(Map.of("success", true, "event_id", r.eventId(), "duplicate", r.duplicate()));
    }
}
