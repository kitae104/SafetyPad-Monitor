package kr.safetypad.monitor.query;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.safetypad.monitor.common.Kst;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {
    public static final int TREND_DAYS = 7;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int lowBatteryThreshold;

    public SummaryService(JdbcClient jdbc, Clock clock, @Value("${safetypad.low-battery-threshold:20}") int lowBatteryThreshold) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lowBatteryThreshold = lowBatteryThreshold;
    }

    public Map<String, Object> summary() {
        return summary(clock.instant());
    }

    /**
     * "received in last 24h" is deliberately NOT an online count: without an agreed heartbeat the
     * server only knows when it last heard from a device.
     */
    public Map<String, Object> summary(Instant now) {
        LocalDate today = Kst.today(now);
        Timestamp todayStart = Timestamp.from(Kst.startOfDay(today));
        Timestamp tomorrowStart = Timestamp.from(Kst.startOfDay(today.plusDays(1)));
        Timestamp trendStart = Timestamp.from(Kst.startOfDay(today.minusDays(TREND_DAYS - 1)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", Kst.format(now));
        out.put("registered_devices", jdbc.sql("SELECT count(*) FROM devices WHERE active").query(Long.class).single());
        out.put("violations_today", jdbc.sql("SELECT count(*) FROM device_events WHERE event_time >= :a AND event_time < :b")
                .param("a", todayStart).param("b", tomorrowStart).query(Long.class).single());
        out.put("devices_received_last_24h", jdbc.sql("SELECT count(*) FROM devices WHERE last_received_at >= :t")
                .param("t", Timestamp.from(now.minus(Duration.ofHours(24)))).query(Long.class).single());
        out.put("low_battery_devices", jdbc.sql("SELECT count(*) FROM devices WHERE active AND last_battery_level <= :th")
                .param("th", lowBatteryThreshold).query(Long.class).single());
        out.put("low_battery_threshold", lowBatteryThreshold);
        out.put("simulated_data_present", jdbc.sql("SELECT EXISTS (SELECT 1 FROM devices WHERE simulated)").query(Boolean.class).single());

        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT (event_time AT TIME ZONE 'Asia/Seoul')::date AS day, count(*) AS n
                FROM device_events WHERE event_time >= :a AND event_time < :b GROUP BY 1
                """).param("a", trendStart).param("b", tomorrowStart)
                .query((rs, i) -> Map.entry(rs.getDate("day").toLocalDate(), rs.getLong("n")))
                .list().forEach(e -> byDay.put(e.getKey(), e.getValue()));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            trend.add(Map.of("date", d.toString(), "count", byDay.getOrDefault(d, 0L)));
        }
        out.put("daily_trend", trend);

        out.put("top_devices", jdbc.sql("""
                SELECT d.device_id, d.alias, count(*) AS n FROM device_events e JOIN devices d ON d.id = e.device_pk
                WHERE e.event_time >= :a AND e.event_time < :b GROUP BY d.device_id, d.alias
                ORDER BY n DESC, d.device_id LIMIT 5
                """).param("a", trendStart).param("b", tomorrowStart)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("device_id", rs.getString("device_id"));
                    m.put("alias", rs.getString("alias"));
                    m.put("count", rs.getLong("n"));
                    return m;
                }).list());

        out.put("lag_distribution", jdbc.sql("""
                SELECT CASE
                         WHEN lag < -60 THEN 'clock_skew'
                         WHEN lag < 10 THEN 'under_10s'
                         WHEN lag < 60 THEN '10s_to_1m'
                         WHEN lag < 600 THEN '1m_to_10m'
                         ELSE 'over_10m' END AS bucket, count(*) AS n
                FROM (SELECT EXTRACT(EPOCH FROM (received_at - event_time)) AS lag
                      FROM device_events WHERE event_time >= :a AND event_time < :b) t
                GROUP BY 1
                """).param("a", trendStart).param("b", tomorrowStart)
                .query((rs, i) -> Map.entry(rs.getString("bucket"), rs.getLong("n")))
                .list().stream().collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
        out.put("period", Map.of("from", today.minusDays(TREND_DAYS - 1).toString(), "to", today.toString(), "timezone", "Asia/Seoul"));
        return out;
    }
}
