package kr.safetypad.monitor.demo;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.ingestion.EventPayloadParser;
import kr.safetypad.monitor.ingestion.EventPayloadParser.ValidEvent;
import kr.safetypad.monitor.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DEV ONLY (same gate as {@link DemoFixtureService}; the bean does not exist in production).
 * Makes the demo look alive: while enabled it adds fictional violation events for random ACTIVE SIMULATED devices
 * at random intervals. Events go through {@link IngestionService#ingest}, i.e. the same atomic, idempotent insert
 * with a server-written received_at as real device traffic, and carry simulated=true in the raw payload.
 *
 * <p>Generation is bound to a lease: the browser renews it while the button is pressed, and if renewals stop
 * (tab closed, network lost, logout) generation ends by itself, so it can never run unattended.
 */
@Service
@Profile("dev")
@ConditionalOnProperty(name = "safetypad.simulated-data-enabled", havingValue = "true")
public class DemoLiveGenerator {
    private static final Logger log = LoggerFactory.getLogger(DemoLiveGenerator.class);

    // Rough bounding box of the fictional stage around 인하공전 (matches data/events.json).
    private static final double LAT_MIN = 37.4440, LAT_MAX = 37.4640, LON_MIN = 126.6420, LON_MAX = 126.6670;
    private static final double JITTER = 0.0015;                 // ~150 m around the device's last known point

    public record Status(boolean running, long generated, long leaseSecondsLeft, String lastEventAt) {}
    public record Transition(boolean changed, Status status) {}

    private final JdbcClient jdbc;
    private final IngestionService ingestion;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long minDelayMs;
    private final long maxDelayMs;
    private final Duration lease;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "demo-live-generator");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private boolean running;
    private long epoch;                                           // invalidates a tick left over from an earlier run
    private Instant leaseUntil = Instant.EPOCH;
    private long generated;
    private Instant lastEventAt;
    private ScheduledFuture<?> next;

    public DemoLiveGenerator(JdbcClient jdbc, IngestionService ingestion, ObjectMapper mapper, Clock clock,
                             @Value("${safetypad.demo.live.min-delay-ms:1000}") long minDelayMs,
                             @Value("${safetypad.demo.live.max-delay-ms:12000}") long maxDelayMs,
                             @Value("${safetypad.demo.live.lease-seconds:30}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.clock = clock;
        this.minDelayMs = Math.max(1, minDelayMs);
        this.maxDelayMs = Math.max(this.minDelayMs, maxDelayMs);
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
    }

    /** enabled=true starts, or renews the lease of, a run; enabled=false stops it. changed = start/stop transition. */
    public Transition setEnabled(boolean enabled) {
        synchronized (lock) {
            boolean changed = false;
            if (enabled) {
                leaseUntil = clock.instant().plus(lease);
                if (!running) {
                    running = true;
                    generated = 0;
                    lastEventAt = null;
                    changed = true;
                    long myEpoch = ++epoch;
                    next = exec.schedule(() -> tick(myEpoch), randomDelay(), TimeUnit.MILLISECONDS);
                }
            } else if (running) {
                stopLocked();
                changed = true;
            }
            return new Transition(changed, statusLocked());
        }
    }

    public Status status() {
        synchronized (lock) {
            expireIfLeaseLapsedLocked();
            return statusLocked();
        }
    }

    private void tick(long myEpoch) {
        synchronized (lock) {
            if (!running || myEpoch != epoch) return;
            if (expireIfLeaseLapsedLocked()) return;
        }
        try {
            generateOne();
        } catch (RuntimeException e) {
            log.warn("Demo live generator: could not add an event ({})", e.getClass().getSimpleName());
        }
        synchronized (lock) {
            if (running && myEpoch == epoch) {
                next = exec.schedule(() -> tick(myEpoch), randomDelay(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private boolean expireIfLeaseLapsedLocked() {
        if (running && clock.instant().isAfter(leaseUntil)) {
            stopLocked();
            log.info("Demo live generator stopped: lease expired after {} events", generated);
            return true;
        }
        return false;
    }

    private void stopLocked() {
        running = false;
        epoch++;
        if (next != null) next.cancel(false);
    }

    private Status statusLocked() {
        long left = running ? Math.max(0, Duration.between(clock.instant(), leaseUntil).toSeconds()) : 0;
        return new Status(running, generated, left, lastEventAt == null ? null : Kst.format(lastEventAt));
    }

    private long randomDelay() {
        return ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1);
    }

    private void generateOne() {
        List<Map<String, Object>> devices = jdbc.sql(
                "SELECT id, device_id, last_battery_level FROM devices WHERE simulated AND active").query().listOfRows();
        if (devices.isEmpty()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Map<String, Object> d = devices.get(rnd.nextInt(devices.size()));
        long pk = ((Number) d.get("id")).longValue();
        String deviceId = (String) d.get("device_id");

        Instant now = clock.instant();
        // Mostly fresh events; now and then one that waited in the device queue for a while (shows up as a delay).
        long ageSeconds = rnd.nextInt(100) < 85 ? rnd.nextLong(0, 16) : rnd.nextLong(60, 601);
        Instant eventTime = now.minusSeconds(ageSeconds);
        String eventId = deviceId + "-LIVE-" + now.toEpochMilli() + "-" + Integer.toString(rnd.nextInt(0x10000), 16);

        BigDecimal lat = null, lon = null;
        if (rnd.nextInt(100) >= 5) {                               // ~5% have no GPS fix: kept, never invented
            double[] base = lastGps(pk);
            double la = clamp(base[0] + rnd.nextDouble(-JITTER, JITTER), LAT_MIN, LAT_MAX);
            double lo = clamp(base[1] + rnd.nextDouble(-JITTER, JITTER), LON_MIN, LON_MAX);
            lat = BigDecimal.valueOf(la).setScale(6, RoundingMode.HALF_UP);
            lon = BigDecimal.valueOf(lo).setScale(6, RoundingMode.HALF_UP);
        }
        Integer prev = d.get("last_battery_level") == null ? null : ((Number) d.get("last_battery_level")).intValue();
        int battery = prev == null ? rnd.nextInt(30, 101) : prev <= 3 ? 95 : (rnd.nextInt(100) < 30 ? prev - 1 : prev);

        ObjectNode raw = mapper.createObjectNode();
        raw.put("event_id", eventId);
        raw.put("device_id", deviceId);
        raw.put("event_type", EventPayloadParser.EVENT_TYPE_MULTI_RIDE);
        raw.put("event_time", OffsetDateTime.ofInstant(eventTime, Kst.ZONE).toString());
        if (lat == null) {
            raw.putNull("gpsData");
        } else {
            ObjectNode gps = raw.putObject("gpsData");
            gps.put("latitude", lat);
            gps.put("longitude", lon);
        }
        raw.put("battery_level", battery);
        raw.put("simulated", true);

        ingestion.ingest(pk, new ValidEvent(eventId, deviceId, EventPayloadParser.EVENT_TYPE_MULTI_RIDE, eventTime, lat, lon, battery), raw);
        synchronized (lock) {
            generated++;
            lastEventAt = clock.instant();
        }
    }

    private double[] lastGps(long pk) {
        return jdbc.sql("SELECT gps_lat, gps_lon FROM device_events WHERE device_pk = :pk AND gps_lat IS NOT NULL ORDER BY event_time DESC LIMIT 1")
                .param("pk", pk).query((rs, n) -> new double[]{rs.getDouble("gps_lat"), rs.getDouble("gps_lon")}).optional()
                .orElseGet(() -> new double[]{(LAT_MIN + LAT_MAX) / 2, (LON_MIN + LON_MAX) / 2});
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @PreDestroy
    void shutdown() {
        exec.shutdownNow();
    }
}
