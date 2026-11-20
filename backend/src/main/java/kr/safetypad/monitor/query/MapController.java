package kr.safetypad.monitor.query;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.ingestion.ApiException;
import kr.safetypad.monitor.query.EventQueryService.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Map data. Two strictly separate feeds:
 *  - violation EVENT points (always available; rows without GPS are counted, never pinned)
 *  - periodic GPS PINGS used for routes (feature-flagged, off in production until the firmware contract is approved).
 * A route is NEVER derived from violation events.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class MapController {
    static final int MAX_POINTS = 5_000;
    static final int MAX_ROUTE_POINTS = 2_000;
    static final String ROUTE_DISCLAIMER =
            "수집된 GPS 지점을 시간순으로 직선 연결한 것입니다. 실제 도로 경로나 이동 증거가 아니며, 수집 간격이 큰 구간은 점선으로 표시됩니다.";

    private final JdbcClient jdbc;
    private final boolean routeEnabled;
    private final Duration maxGap;

    public MapController(JdbcClient jdbc, @Value("${safetypad.route-enabled:false}") boolean routeEnabled,
                         @Value("${safetypad.route-gap-minutes:30}") long gapMinutes) {
        this.jdbc = jdbc;
        this.routeEnabled = routeEnabled;
        this.maxGap = Duration.ofMinutes(gapMinutes);
    }

    @GetMapping("/map/events")
    Map<String, Object> events(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "device_id", required = false) String deviceId) {
        if (from != null && to != null && from.isAfter(to)) throw ApiException.badRequest("from must not be after to");
        Map<String, Object> p = new HashMap<>();
        String where = EventQueryService.where(new Filter(from, to, deviceId, null, true), p);
        List<Map<String, Object>> points = jdbc.sql("""
                SELECT e.id, e.event_id, d.device_id, d.alias, e.event_time, e.received_at, e.gps_lat, e.gps_lon, d.simulated
                FROM device_events e JOIN devices d ON d.id = e.device_pk""" + where
                + " ORDER BY e.event_time DESC, e.id DESC LIMIT " + (MAX_POINTS + 1))
                .params(p).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("event_id", rs.getString("event_id"));
                    m.put("device_id", rs.getString("device_id"));
                    m.put("alias", rs.getString("alias"));
                    m.put("point_type", "VIOLATION");
                    m.put("event_time", Kst.format(rs.getTimestamp("event_time")));
                    m.put("received_at", Kst.format(rs.getTimestamp("received_at")));
                    m.put("latitude", rs.getBigDecimal("gps_lat"));
                    m.put("longitude", rs.getBigDecimal("gps_lon"));
                    m.put("simulated", rs.getBoolean("simulated"));
                    return m;
                }).list();
        boolean truncated = points.size() > MAX_POINTS;
        if (truncated) points = points.subList(0, MAX_POINTS);

        Map<String, Object> p2 = new HashMap<>();
        String whereMissing = EventQueryService.where(new Filter(from, to, deviceId, null, false), p2);
        long missing = jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id = e.device_pk" + whereMissing)
                .params(p2).query(Long.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("truncated", truncated);
        out.put("missing_gps_count", missing);
        out.put("note", "위반 이벤트가 발생한 지점입니다. 장비의 실시간 위치나 이동 경로가 아닙니다.");
        return out;
    }

    @GetMapping("/devices/{deviceId}/route")
    Map<String, Object> route(@PathVariable String deviceId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!routeEnabled) {
            throw new ApiException(404, "ROUTE_DISABLED", "Route view is disabled until the GPS ping contract is approved");
        }
        Long pk = jdbc.sql("SELECT id FROM devices WHERE device_id = :d").param("d", deviceId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Device not found"));
        if (from != null && to != null && from.isAfter(to)) throw ApiException.badRequest("from must not be after to");

        List<LocalDate> days = jdbc.sql("""
                SELECT DISTINCT (recorded_at AT TIME ZONE 'Asia/Seoul')::date AS d FROM device_locations
                WHERE device_pk = :pk ORDER BY d DESC LIMIT 14""")
                .param("pk", pk).query((rs, n) -> rs.getDate("d").toLocalDate()).list();
        LocalDate f = from, t = to;
        if (f == null && t == null && !days.isEmpty()) { f = days.get(0); t = days.get(0); }   // default: latest day with pings
        if (f == null) f = t;
        if (t == null) t = f;

        List<Map<String, Object>> pts = new ArrayList<>();
        Instant prev = null;
        long gaps = 0;
        if (f != null) {
            var rows = jdbc.sql("""
                    SELECT recorded_at, lat, lon, source FROM device_locations
                    WHERE device_pk = :pk AND recorded_at >= :a AND recorded_at < :b
                    ORDER BY recorded_at""" + " LIMIT " + (MAX_ROUTE_POINTS + 1))
                    .param("pk", pk).param("a", Timestamp.from(Kst.startOfDay(f))).param("b", Timestamp.from(Kst.startOfDay(t.plusDays(1))))
                    .query((rs, n) -> new Object[]{rs.getTimestamp("recorded_at").toInstant(), rs.getBigDecimal("lat"), rs.getBigDecimal("lon"), rs.getString("source")})
                    .list();
            for (Object[] r : rows) {
                if (pts.size() >= MAX_ROUTE_POINTS) break;
                Instant at = (Instant) r[0];
                boolean gapBefore = prev != null && Duration.between(prev, at).compareTo(maxGap) > 0;
                if (gapBefore) gaps++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("point_type", "PERIODIC");
                m.put("recorded_at", Kst.format(at));
                m.put("latitude", (BigDecimal) r[1]);
                m.put("longitude", (BigDecimal) r[2]);
                m.put("source", r[3]);
                m.put("gap_before", gapBefore);
                pts.add(m);
                prev = at;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device_id", deviceId);
        out.put("sufficient", pts.size() >= 2);          // fewer than 2 independent pings -> no line is drawn
        out.put("points", pts);
        out.put("gaps", gaps);
        out.put("max_gap_minutes", maxGap.toMinutes());
        out.put("range", f == null ? null : Map.of("from", f.toString(), "to", t.toString(), "timezone", "Asia/Seoul"));
        out.put("days_available", days.stream().map(LocalDate::toString).toList());
        out.put("last_ping_at", pts.isEmpty() ? null : pts.get(pts.size() - 1).get("recorded_at"));
        out.put("disclaimer", ROUTE_DISCLAIMER);
        return out;
    }
}
