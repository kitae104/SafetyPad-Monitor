package kr.safetypad.monitor.ingestion;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import kr.safetypad.monitor.ingestion.EventPayloadParser.ValidEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class IngestionService {

    public record Result(String eventId, boolean duplicate) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public IngestionService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Atomic idempotent insert. The unique (device_pk, event_id) constraint is the arbiter:
     * concurrent retries serialize on it and exactly one inserts. received_at is written only by
     * the winning insert, so a retry can never overwrite the first arrival time.
     * Success is returned only after this transaction commits (method return == commit).
     */
    @Transactional
    public Result ingest(long devicePk, ValidEvent e, JsonNode rawPayload) {
        Instant receivedAt = clock.instant();
        int inserted = jdbc.sql("""
                INSERT INTO device_events
                    (device_pk, event_id, event_type, event_time, received_at, gps_lat, gps_lon, battery_level, raw_payload)
                VALUES (:pk, :eid, :type, :etime, :rtime, :lat, :lon, :battery, CAST(:raw AS jsonb))
                ON CONFLICT (device_pk, event_id) DO NOTHING
                """)
                .param("pk", devicePk)
                .param("eid", e.eventId())
                .param("type", e.eventType())
                .param("etime", Timestamp.from(e.eventTime()))
                .param("rtime", Timestamp.from(receivedAt))
                .param("lat", e.lat())
                .param("lon", e.lon())
                .param("battery", e.battery())
                .param("raw", rawPayload.toString())
                .update();
        if (inserted == 0) {
            return new Result(e.eventId(), true);
        }
        jdbc.sql("""
                UPDATE devices SET
                    last_received_at = GREATEST(COALESCE(last_received_at, :rtime), :rtime),
                    last_battery_level = COALESCE(:battery, last_battery_level)
                WHERE id = :pk
                """)
                .param("rtime", Timestamp.from(receivedAt))
                .param("battery", e.battery())
                .param("pk", devicePk)
                .update();
        return new Result(e.eventId(), false);
    }
}
