package kr.safetypad.monitor.ingestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Two-stage validation matching the contract:
 * {@link #parse} -> structural problems (400); {@link #validate} -> semantic problems (422).
 * The device_id/API-key match (403) is checked by the caller between the two stages.
 */
public final class EventPayloadParser {

    public record ParsedEvent(String eventId, String deviceId, JsonNode eventTimeNode, JsonNode gps,
                              JsonNode eventType, JsonNode battery) {}

    public record ValidEvent(String eventId, String deviceId, String eventType, Instant eventTime,
                             BigDecimal lat, BigDecimal lon, Integer battery) {}

    private static final Set<String> ALLOWED = Set.of("event_id", "device_id", "event_type", "event_time", "gpsData", "battery_level");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:\\-]+");
    public static final String EVENT_TYPE_MULTI_RIDE = "MULTI_RIDE";

    private EventPayloadParser() {}

    public static ParsedEvent parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("Body must be a JSON object");
        }
        for (String name : body.propertyNames()) {
            if (!ALLOWED.contains(name)) {
                throw ApiException.badRequest("Unknown field: " + sanitize(name));
            }
        }
        return new ParsedEvent(
                requiredText(body, "event_id"), requiredText(body, "device_id"),
                body.get("event_time"), body.get("gpsData"), body.get("event_type"), body.get("battery_level"));
    }

    public static ValidEvent validate(ParsedEvent p, Instant now, Duration maxFutureSkew, Duration maxAge) {
        if (p.eventId().length() > 100 || !ID.matcher(p.eventId()).matches()) {
            throw ApiException.unprocessable("event_id must be 1-100 chars of [A-Za-z0-9._:-]");
        }
        if (p.deviceId().length() > 64 || !ID.matcher(p.deviceId()).matches()) {
            throw ApiException.unprocessable("device_id must be 1-64 chars of [A-Za-z0-9._:-]");
        }

        String eventType = EVENT_TYPE_MULTI_RIDE;
        if (p.eventType() != null && !p.eventType().isNull()) {
            if (!p.eventType().isString()) throw ApiException.badRequest("event_type must be a string");
            eventType = p.eventType().asString();
            if (!EVENT_TYPE_MULTI_RIDE.equals(eventType)) {
                throw ApiException.unprocessable("Unsupported event_type");
            }
        }

        if (p.eventTimeNode() == null || !p.eventTimeNode().isString()) {
            throw ApiException.badRequest("event_time must be an ISO-8601 string");
        }
        Instant eventTime;
        try {
            // OffsetDateTime requires an explicit offset, so timezone-less instants are rejected.
            eventTime = OffsetDateTime.parse(p.eventTimeNode().asString()).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("event_time must be ISO-8601 with timezone offset, e.g. 2026-11-05T14:30:10+09:00");
        }
        if (eventTime.isAfter(now.plus(maxFutureSkew))) {
            throw ApiException.unprocessable("event_time is in the future beyond allowed clock skew");
        }
        if (eventTime.isBefore(now.minus(maxAge))) {
            throw ApiException.unprocessable("event_time is older than the accepted window");
        }

        BigDecimal lat = null;
        BigDecimal lon = null;
        JsonNode gps = p.gps();
        // null, absent and "" all mean "no GPS fix" (final representation pending firmware agreement).
        boolean noGps = gps == null || gps.isNull() || (gps.isString() && gps.asString().isEmpty());
        if (!noGps) {
            if (!gps.isObject()) throw ApiException.unprocessable("gpsData must be an object or null");
            JsonNode la = gps.get("latitude");
            JsonNode lo = gps.get("longitude");
            if (la == null || lo == null || !la.isNumber() || !lo.isNumber()) {
                throw ApiException.unprocessable("gpsData requires numeric latitude and longitude");
            }
            for (String name : gps.propertyNames()) {
                if (!name.equals("latitude") && !name.equals("longitude")) {
                    throw ApiException.unprocessable("Unknown gpsData field: " + sanitize(name));
                }
            }
            double dLat = la.doubleValue();
            double dLon = lo.doubleValue();
            if (!Double.isFinite(dLat) || !Double.isFinite(dLon) || dLat < -90 || dLat > 90 || dLon < -180 || dLon > 180) {
                throw ApiException.unprocessable("latitude must be within [-90, 90] and longitude within [-180, 180]");
            }
            lat = BigDecimal.valueOf(dLat).setScale(6, RoundingMode.HALF_UP);
            lon = BigDecimal.valueOf(dLon).setScale(6, RoundingMode.HALF_UP);
        }

        Integer battery = null;
        JsonNode b = p.battery();
        if (b != null && !b.isNull()) {
            if (!b.isNumber() || !b.isIntegralNumber() || !b.canConvertToInt() || b.intValue() < 0 || b.intValue() > 100) {
                throw ApiException.unprocessable("battery_level must be an integer within [0, 100]");
            }
            battery = b.intValue();
        }
        return new ValidEvent(p.eventId(), p.deviceId(), eventType, eventTime, lat, lon, battery);
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) throw ApiException.badRequest("Missing required field: " + field);
        if (!n.isString() || n.asString().isBlank()) throw ApiException.badRequest(field + " must be a non-empty string");
        return n.asString();
    }

    /** Keep echoed client text short and free of control characters. */
    private static String sanitize(String s) {
        String t = s.length() > 40 ? s.substring(0, 40) : s;
        return t.replaceAll("[^\\p{L}\\p{N}_.\\-]", "?");
    }
}
