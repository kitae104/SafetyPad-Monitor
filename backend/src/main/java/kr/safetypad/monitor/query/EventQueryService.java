package kr.safetypad.monitor.query;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.common.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class EventQueryService {

    /** Dates are Asia/Seoul calendar days, both inclusive. */
    public record Filter(LocalDate from, LocalDate to, String deviceQuery, Long minLagSeconds, Boolean hasGps) {}

    public record EventRow(long id, String eventId, String deviceId, String alias, String eventType,
                           String eventTime, String receivedAt, long lagSeconds, boolean clockSkewSuspected,
                           BigDecimal latitude, BigDecimal longitude, Integer batteryLevel, boolean simulated) {}

    /** received_at earlier than event_time by more than this suggests a wrong device RTC. */
    static final long CLOCK_SKEW_THRESHOLD_SECONDS = -60;
    private static final Map<String, String> SORTS = Map.of(
            "event_time", "e.event_time", "received_at", "e.received_at", "device_id", "d.device_id");

    private static final String SELECT = """
            SELECT e.id, e.event_id, d.device_id, d.alias, e.event_type, e.event_time, e.received_at,
                   EXTRACT(EPOCH FROM (e.received_at - e.event_time))::bigint AS lag_s,
                   e.gps_lat, e.gps_lon, e.battery_level, d.simulated
            FROM device_events e JOIN devices d ON d.id = e.device_pk
            """;

    private final JdbcClient jdbc;

    public EventQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<EventRow> search(Filter f, String sort, boolean desc, int page, int size) {
        Map<String, Object> p = new HashMap<>();
        String where = where(f, p);
        long total = jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id = e.device_pk" + where)
                .params(p).query(Long.class).single();
        String col = SORTS.getOrDefault(sort, "e.event_time");
        p.put("limit", size);
        p.put("offset", (long) page * size);
        List<EventRow> rows = jdbc.sql(SELECT + where + " ORDER BY " + col + (desc ? " DESC" : " ASC")
                        + ", e.id DESC LIMIT :limit OFFSET :offset")
                .params(p).query(EventQueryService::map).list();
        return new Page<>(rows, page, size, total);
    }

    /** Bounded export read; the caller enforces the row cap. */
    public List<EventRow> export(Filter f, int maxRows) {
        Map<String, Object> p = new HashMap<>();
        String where = where(f, p);
        p.put("limit", maxRows);
        return jdbc.sql(SELECT + where + " ORDER BY e.event_time DESC, e.id DESC LIMIT :limit")
                .params(p).query(EventQueryService::map).list();
    }

    public Optional<EventRow> find(long id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id").param("id", id).query(EventQueryService::map).optional();
    }

    static String where(Filter f, Map<String, Object> p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (f.from() != null) {
            w.append(" AND e.event_time >= :from");
            p.put("from", java.sql.Timestamp.from(Kst.startOfDay(f.from())));
        }
        if (f.to() != null) {
            w.append(" AND e.event_time < :toExcl");
            p.put("toExcl", java.sql.Timestamp.from(Kst.startOfDay(f.to().plusDays(1))));
        }
        if (f.deviceQuery() != null && !f.deviceQuery().isBlank()) {
            w.append(" AND d.device_id ILIKE :dq ESCAPE '\\'");
            p.put("dq", "%" + f.deviceQuery().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.minLagSeconds() != null) {
            w.append(" AND EXTRACT(EPOCH FROM (e.received_at - e.event_time)) >= :minLag");
            p.put("minLag", f.minLagSeconds());
        }
        if (f.hasGps() != null) {
            w.append(f.hasGps() ? " AND e.gps_lat IS NOT NULL" : " AND e.gps_lat IS NULL");
        }
        return w.toString();
    }

    private static EventRow map(ResultSet rs, int n) throws SQLException {
        long lag = rs.getLong("lag_s");
        return new EventRow(rs.getLong("id"), rs.getString("event_id"), rs.getString("device_id"), rs.getString("alias"),
                rs.getString("event_type"), Kst.format(rs.getTimestamp("event_time")), Kst.format(rs.getTimestamp("received_at")),
                lag, lag < CLOCK_SKEW_THRESHOLD_SECONDS, rs.getBigDecimal("gps_lat"), rs.getBigDecimal("gps_lon"),
                (Integer) rs.getObject("battery_level"), rs.getBoolean("simulated"));
    }
}
