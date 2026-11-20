package kr.safetypad.monitor.device;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.safetypad.monitor.audit.AuditService;
import kr.safetypad.monitor.ingestion.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Device registry changes and key lifecycle. Every change is audited; plaintext keys are returned exactly once. */
@RestController
@RequestMapping("/api/v1/admin/devices")
public class AdminDeviceController {
    record CreateRequest(String deviceId, String alias, String zone) {}
    record ConfirmRequest(Boolean confirm) {}
    record ActiveRequest(Boolean active) {}

    private final DeviceKeyService keys;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public AdminDeviceController(DeviceKeyService keys, JdbcClient jdbc, AuditService audit) {
        this.keys = keys;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest r, HttpServletRequest req) {
        if (r == null || r.deviceId() == null || !r.deviceId().matches("[A-Za-z0-9._:\\-]{1,64}")) {
            throw ApiException.unprocessable("device_id must be 1-64 chars of [A-Za-z0-9._:-]");
        }
        try {
            jdbc.sql("INSERT INTO devices (device_id, alias, zone) VALUES (:id, :alias, :zone)")
                    .param("id", r.deviceId()).param("alias", clip(r.alias(), 100)).param("zone", clip(r.zone(), 100)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "CONFLICT", "Device already exists");
        }
        audit.record("DEVICE_CREATE", r.deviceId(), null, req);
        return ResponseEntity.status(201).body(Map.of("device_id", r.deviceId()));
    }

    /** Issues an additional key (rotation = issue new, deploy to device, then revoke old). Requires explicit confirmation. */
    @PostMapping("/{deviceId}/credentials")
    ResponseEntity<Map<String, Object>> issue(@PathVariable String deviceId, @RequestBody ConfirmRequest c, HttpServletRequest req) {
        requireConfirm(c);
        DeviceKeyService.IssuedKey k;
        try {
            k = keys.issue(deviceId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "NOT_FOUND", "Device not found");
        }
        audit.record("KEY_ISSUE", deviceId, Map.of("credential_id", k.credentialId(), "prefix", k.prefix()), req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credential_id", k.credentialId());
        body.put("key_prefix", k.prefix());
        body.put("api_key", k.plaintextKey());
        body.put("notice", "This key is shown only once. Store it securely; it cannot be retrieved later.");
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(body);
    }

    @PostMapping("/{deviceId}/credentials/{credentialId}/revoke")
    Map<String, Object> revoke(@PathVariable String deviceId, @PathVariable long credentialId,
                               @RequestBody ConfirmRequest c, HttpServletRequest req) {
        requireConfirm(c);
        int n = jdbc.sql("""
                UPDATE device_credentials SET active = false, revoked_at = now()
                WHERE id = :cid AND revoked_at IS NULL AND device_pk = (SELECT id FROM devices WHERE device_id = :d)
                """).param("cid", credentialId).param("d", deviceId).update();
        if (n == 0) throw new ApiException(404, "NOT_FOUND", "Active credential not found for device");
        audit.record("KEY_REVOKE", deviceId, Map.of("credential_id", credentialId), req);
        return Map.of("success", true);
    }

    @PostMapping("/{deviceId}/active")
    Map<String, Object> setActive(@PathVariable String deviceId, @RequestBody ActiveRequest a, HttpServletRequest req) {
        if (a == null || a.active() == null) throw ApiException.badRequest("active is required");
        int n = jdbc.sql("UPDATE devices SET active = :a WHERE device_id = :d").param("a", a.active()).param("d", deviceId).update();
        if (n == 0) throw new ApiException(404, "NOT_FOUND", "Device not found");
        audit.record(a.active() ? "DEVICE_ENABLE" : "DEVICE_DISABLE", deviceId, null, req);
        return Map.of("success", true, "active", a.active());
    }

    private static void requireConfirm(ConfirmRequest c) {
        if (c == null || !Boolean.TRUE.equals(c.confirm())) {
            throw ApiException.badRequest("confirm=true is required for this action");
        }
    }

    private static String clip(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
