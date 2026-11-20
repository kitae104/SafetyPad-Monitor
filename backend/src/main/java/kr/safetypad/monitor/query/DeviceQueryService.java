package kr.safetypad.monitor.query;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.common.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DeviceQueryService {

    /** Last GPS taken from a violation event. It is a past event position, NOT a live location. */
    public record LastKnownGps(BigDecimal latitude, BigDecimal longitude, String eventTime, String source) {}

    public record DeviceRow(String deviceId, String alias, String zone, boolean active, boolean simulated,
                            String lastReceivedAt, Integer lastBatteryLevel, long eventCount, LastKnownGps lastKnownGps) {}

    private static final String SELECT = """
            SELECT d.device_id, d.alias, d.zone, d.active, d.simulated, d.last_received_at, d.last_battery_level,
                   (SELECT count(*) FROM device_events c WHERE c.device_pk = d.id) AS event_count,
                   g.gps_lat, g.gps_lon, g.event_time AS gps_time
            FROM devices d
            LEFT JOIN LATERAL (
                SELECT gps_lat, gps_lon, event_time FROM device_events e
                WHERE e.device_pk = d.id AND e.gps_lat IS NOT NULL
                ORDER BY e.event_time DESC LIMIT 1) g ON true
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int lowBatteryThreshold;

    public DeviceQueryService(JdbcClient jdbc, Clock clock, @Value("${safetypad.low-battery-threshold:20}") int lowBatteryThreshold) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lowBatteryThreshold = lowBatteryThreshold;
    }

    /** @param active null = all devices, true = active only, false = inactive only */
    public Page<DeviceRow> list(String q, Boolean active, int page, int size) {
        return list(q, active, false, null, page, size);
    }

    /**
     * @param lowBattery          true = the dashboard's "배터리 주의" set (active, last reported level at or below the threshold)
     * @param receivedWithinHours non-null = the dashboard's "최근 N시간 수신" set (last_received_at within N hours; NOT an online claim)
     */
    public Page<DeviceRow> list(String q, Boolean active, boolean lowBattery, Integer receivedWithinHours, int page, int size) {
        Map<String, Object> p = new HashMap<>();
        List<String> conditions = new java.util.ArrayList<>();
        if (q != null && !q.isBlank()) {
            conditions.add("(d.device_id ILIKE :q ESCAPE '\\' OR d.alias ILIKE :q ESCAPE '\\')");
            p.put("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (active != null) {
            conditions.add("d.active = :active");
            p.put("active", active);
        }
        if (lowBattery) {
            conditions.add("d.active AND d.last_battery_level <= :lowBatteryThreshold");
            p.put("lowBatteryThreshold", lowBatteryThreshold);
        }
        if (receivedWithinHours != null) {
            conditions.add("d.last_received_at >= :receivedSince");
            p.put("receivedSince", java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(receivedWithinHours))));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.sql("SELECT count(*) FROM devices d" + where).params(p).query(Long.class).single();
        p.put("limit", size);
        p.put("offset", (long) page * size);
        List<DeviceRow> rows = jdbc.sql(SELECT + where + " ORDER BY d.device_id LIMIT :limit OFFSET :offset")
                .params(p).query(DeviceQueryService::map).list();
        return new Page<>(rows, page, size, total);
    }

    public Optional<DeviceRow> find(String deviceId) {
        return jdbc.sql(SELECT + " WHERE d.device_id = :id").param("id", deviceId)
                .query(DeviceQueryService::map).optional();
    }

    /** Credential metadata only: the digest and plaintext are never exposed. */
    public List<Map<String, Object>> credentials(String deviceId) {
        return jdbc.sql("""
                SELECT c.id, c.key_prefix, c.active, c.issued_at, c.expires_at, c.revoked_at
                FROM device_credentials c JOIN devices d ON d.id = c.device_pk
                WHERE d.device_id = :id ORDER BY c.id DESC
                """).param("id", deviceId)
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("credential_id", rs.getLong("id"));
                    m.put("key_prefix", rs.getString("key_prefix"));
                    m.put("active", rs.getBoolean("active"));
                    m.put("issued_at", Kst.format(rs.getTimestamp("issued_at")));
                    m.put("expires_at", Kst.format(rs.getTimestamp("expires_at")));
                    m.put("revoked_at", Kst.format(rs.getTimestamp("revoked_at")));
                    return m;
                }).list();
    }

    private static DeviceRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        BigDecimal lat = rs.getBigDecimal("gps_lat");
        LastKnownGps gps = lat == null ? null
                : new LastKnownGps(lat, rs.getBigDecimal("gps_lon"), Kst.format(rs.getTimestamp("gps_time")), "VIOLATION_EVENT");
        return new DeviceRow(rs.getString("device_id"), rs.getString("alias"), rs.getString("zone"), rs.getBoolean("active"),
                rs.getBoolean("simulated"), Kst.format(rs.getTimestamp("last_received_at")),
                (Integer) rs.getObject("last_battery_level"), rs.getLong("event_count"), gps);
    }
}
