package kr.safetypad.monitor.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Instants are stored/processed in UTC; every human-facing rendering uses Asia/Seoul. */
public final class Kst {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Kst() {}

    public static String format(Instant i) {
        return i == null ? null : ISO.format(i.atZone(ZONE));
    }

    public static String format(Timestamp t) {
        return t == null ? null : format(t.toInstant());
    }

    public static Instant startOfDay(LocalDate d) {
        return d.atStartOfDay(ZONE).toInstant();
    }

    public static LocalDate today(Instant now) {
        return now.atZone(ZONE).toLocalDate();
    }
}
