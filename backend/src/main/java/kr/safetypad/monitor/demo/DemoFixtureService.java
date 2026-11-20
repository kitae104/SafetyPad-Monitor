package kr.safetypad.monitor.demo;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.ingestion.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DEV ONLY. Loads the fictional data/*.json into the database and can remove it again.
 * The bean does not exist outside the dev profile or when SIMULATED_DATA_ENABLED is not true,
 * so production can never seed or reset demo data. Everything it writes is flagged simulated=true.
 */
@Service
@Profile("dev")
@ConditionalOnProperty(name = "safetypad.simulated-data-enabled", havingValue = "true")
public class DemoFixtureService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path dataDir;

    public DemoFixtureService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
                              @Value("${safetypad.demo.data-dir:../data}") String dataDir) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.dataDir = Path.of(dataDir);
    }

    /**
     * @param shiftToToday move all timestamps by whole days so the newest seed event falls on today (KST);
     *                     otherwise a stale seed would show zero "today" violations.
     */
    @Transactional
    public Map<String, Object> importDemo(boolean shiftToToday) {
        JsonNode devices = read("devices.json");
        JsonNode events = read("events.json");
        JsonNode locations = read("gps_locations.json");

        long shiftDays = 0;
        if (shiftToToday) {
            Instant newest = Instant.MIN;
            for (JsonNode e : events) {
                Instant t = OffsetDateTime.parse(e.get("event_time").asString()).toInstant();
                if (t.isAfter(newest)) newest = t;
            }
            LocalDate newestDay = Kst.today(newest);
            shiftDays = ChronoUnit.DAYS.between(newestDay, Kst.today(clock.instant()));
        }
        Duration shift = Duration.ofDays(shiftDays);

        Map<String, Long> pk = new HashMap<>();
        for (JsonNode d : devices) {
            String id = d.get("device_id").asString();
            Long key = jdbc.queryForObject("""
                    INSERT INTO devices (device_id, alias, zone, active, simulated, last_battery_level)
                    VALUES (?, ?, ?, ?, true, ?)
                    ON CONFLICT (device_id) DO UPDATE SET alias = EXCLUDED.alias, zone = EXCLUDED.zone, simulated = true
                    RETURNING id
                    """, Long.class, id, d.get("alias").asString(), d.get("zone").asString(),
                    d.get("active").asBoolean(), d.get("battery_level").asInt());
            pk.put(id, key);
        }

        List<Object[]> eventRows = new ArrayList<>();
        for (JsonNode e : events) {
            JsonNode gps = e.get("gpsData");
            boolean hasGps = gps != null && gps.isObject();
            ObjectNode raw = ((ObjectNode) e.deepCopy());
            raw.put("simulated", true);
            eventRows.add(new Object[]{
                    pk.get(e.get("device_id").asString()), e.get("event_id").asString(), e.get("event_type").asString(),
                    ts(e.get("event_time").asString(), shift), ts(e.get("received_at").asString(), shift),
                    hasGps ? scale(gps.get("latitude").doubleValue()) : null,
                    hasGps ? scale(gps.get("longitude").doubleValue()) : null,
                    e.get("battery_level").isNull() ? null : e.get("battery_level").asInt(), raw.toString()});
        }
        int[][] ev = jdbc.batchUpdate("""
                INSERT INTO device_events (device_pk, event_id, event_type, event_time, received_at, gps_lat, gps_lon, battery_level, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (device_pk, event_id) DO NOTHING
                """, eventRows, 500, (ps, r) -> {
            ps.setObject(1, r[0]); ps.setObject(2, r[1]); ps.setObject(3, r[2]); ps.setObject(4, r[3]); ps.setObject(5, r[4]);
            ps.setObject(6, r[5]); ps.setObject(7, r[6]); ps.setObject(8, r[7]); ps.setObject(9, r[8]);
        });

        List<Object[]> locRows = new ArrayList<>();
        for (JsonNode l : locations) {
            Timestamp at = ts(l.get("recorded_at").asString(), shift);
            locRows.add(new Object[]{pk.get(l.get("device_id").asString()), at, at,
                    scale(l.get("latitude").doubleValue()), scale(l.get("longitude").doubleValue()), l.get("source").asString()});
        }
        jdbc.batchUpdate("""
                INSERT INTO device_locations (device_pk, recorded_at, received_at, lat, lon, source)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (device_pk, recorded_at) DO NOTHING
                """, locRows, 500, (ps, r) -> {
            for (int i = 0; i < r.length; i++) ps.setObject(i + 1, r[i]);
        });

        jdbc.update("""
                UPDATE devices d SET last_received_at = x.m
                FROM (SELECT device_pk, max(received_at) AS m FROM device_events GROUP BY device_pk) x
                WHERE x.device_pk = d.id AND d.simulated AND (d.last_received_at IS NULL OR d.last_received_at < x.m)
                """);

        long insertedEvents = 0;
        for (int[] b : ev) for (int n : b) insertedEvents += Math.max(n, 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", devices.size());
        out.put("events_inserted", insertedEvents);
        out.put("locations_in_seed", locations.size());
        out.put("shift_days", shiftDays);
        out.put("label", "가상 데이터");
        return out;
    }

    /** Removes events and pings of simulated devices only. The delete trigger is opened for this transaction alone. */
    @Transactional
    public Map<String, Object> resetDemo() {
        jdbc.queryForObject("SELECT set_config('safetypad.allow_demo_reset', 'on', true)", String.class);
        int events = jdbc.update("DELETE FROM device_events WHERE device_pk IN (SELECT id FROM devices WHERE simulated)");
        int locations = jdbc.update("DELETE FROM device_locations WHERE device_pk IN (SELECT id FROM devices WHERE simulated)");
        jdbc.update("UPDATE devices SET last_received_at = NULL, last_battery_level = NULL WHERE simulated");
        return Map.of("events_deleted", events, "locations_deleted", locations);
    }

    private JsonNode read(String name) {
        Path p = dataDir.resolve(name);
        if (!Files.isReadable(p)) {
            throw new ApiException(409, "DEMO_DATA_MISSING", "Demo data file not found: " + name);
        }
        try {
            return mapper.readTree(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new ApiException(409, "DEMO_DATA_UNREADABLE", "Demo data file unreadable: " + name);
        }
    }

    private static Timestamp ts(String iso, Duration shift) {
        return Timestamp.from(OffsetDateTime.parse(iso).toInstant().plus(shift));
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
