package kr.safetypad.monitor.query;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.safetypad.monitor.audit.AuditService;
import kr.safetypad.monitor.common.Kst;
import kr.safetypad.monitor.common.Page;
import kr.safetypad.monitor.ingestion.ApiException;
import kr.safetypad.monitor.query.EventQueryService.EventRow;
import kr.safetypad.monitor.query.EventQueryService.Filter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminQueryController {
    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_EXPORT_ROWS = 50_000;
    static final int MAX_RANGE_DAYS = 366;

    private final EventQueryService events;
    private final DeviceQueryService devices;
    private final SummaryService summary;
    private final AuditService audit;
    private final Clock clock;

    public AdminQueryController(EventQueryService events, DeviceQueryService devices, SummaryService summary,
                                AuditService audit, Clock clock) {
        this.events = events;
        this.devices = devices;
        this.summary = summary;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/summary")
    Map<String, Object> summary() {
        return summary.summary();
    }

    @GetMapping("/events")
    Page<EventRow> events(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(name = "min_lag_seconds", required = false) Long minLag,
            @RequestParam(name = "has_gps", required = false) Boolean hasGps,
            @RequestParam(defaultValue = "event_time") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("page must be >= 0 and size within 1.." + MAX_PAGE_SIZE);
        }
        if (!List.of("asc", "desc").contains(dir)) throw ApiException.badRequest("dir must be asc or desc");
        if (!List.of("event_time", "received_at", "device_id").contains(sort)) throw ApiException.badRequest("unsupported sort");
        return events.search(filter(from, to, deviceId, minLag, hasGps), sort, dir.equals("desc"), page, size);
    }

    @GetMapping("/events/{id}")
    EventRow event(@PathVariable long id) {
        return events.find(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Event not found"));
    }

    @GetMapping("/events/export.csv")
    void exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(name = "min_lag_seconds", required = false) Long minLag,
            @RequestParam(name = "has_gps", required = false) Boolean hasGps,
            HttpServletRequest req, HttpServletResponse res) throws IOException {
        Filter f = filter(from, to, deviceId, minLag, hasGps);
        List<EventRow> rows = events.export(f, MAX_EXPORT_ROWS + 1);
        boolean truncated = rows.size() > MAX_EXPORT_ROWS;
        if (truncated) rows = rows.subList(0, MAX_EXPORT_ROWS);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from == null ? null : from.toString());
        detail.put("to", to == null ? null : to.toString());
        detail.put("device_id", deviceId);
        detail.put("rows", rows.size());
        detail.put("truncated", truncated);
        audit.record("EXPORT_CSV", "events", detail, req);

        String stamp = Kst.today(clock.instant()).toString();
        res.setContentType("text/csv;charset=UTF-8");
        res.setHeader("Content-Disposition", "attachment; filename=\"safetypad-events-" + stamp + ".csv\"");
        res.setHeader("X-Export-Truncated", String.valueOf(truncated));
        res.setHeader("Cache-Control", "no-store");
        Writer w = new java.io.OutputStreamWriter(res.getOutputStream(), StandardCharsets.UTF_8);
        w.write(CsvWriter.BOM);
        CsvWriter.writeRow(w, List.of("event_id", "device_id", "device_alias", "event_type", "event_time_kst",
                "received_at_kst", "lag_seconds", "latitude", "longitude", "battery_level", "data_origin").stream()
                .map(CsvWriter::textCell).toList());
        for (EventRow r : rows) {
            CsvWriter.writeRow(w, List.of(
                    CsvWriter.textCell(r.eventId()), CsvWriter.textCell(r.deviceId()), CsvWriter.textCell(r.alias()),
                    CsvWriter.textCell(r.eventType()), CsvWriter.textCell(r.eventTime()), CsvWriter.textCell(r.receivedAt()),
                    CsvWriter.numberCell(r.lagSeconds()), CsvWriter.numberCell(r.latitude()), CsvWriter.numberCell(r.longitude()),
                    CsvWriter.numberCell(r.batteryLevel()), CsvWriter.textCell(r.simulated() ? "가상 데이터" : "실제 수신")));
        }
        w.flush();
    }

    @GetMapping("/devices")
    Page<DeviceQueryService.DeviceRow> devices(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Boolean active,
                                               @RequestParam(name = "low_battery", defaultValue = "false") boolean lowBattery,
                                               @RequestParam(name = "received_within_hours", required = false) Integer receivedWithinHours,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("page must be >= 0 and size within 1.." + MAX_PAGE_SIZE);
        }
        if (receivedWithinHours != null && (receivedWithinHours < 1 || receivedWithinHours > 24 * 366)) {
            throw ApiException.badRequest("received_within_hours must be within 1..8784");
        }
        return devices.list(q, active, lowBattery, receivedWithinHours, page, size);
    }

    @GetMapping("/devices/{deviceId}")
    Map<String, Object> device(@PathVariable String deviceId) {
        var d = devices.find(deviceId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Device not found"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device", d);
        m.put("credentials", devices.credentials(deviceId));
        return m;
    }

    private static Filter filter(LocalDate from, LocalDate to, String deviceId, Long minLag, Boolean hasGps) {
        if (from != null && to != null) {
            if (from.isAfter(to)) throw ApiException.badRequest("from must not be after to");
            if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
                throw ApiException.badRequest("date range must not exceed " + MAX_RANGE_DAYS + " days");
            }
        }
        if (deviceId != null && deviceId.length() > 64) throw ApiException.badRequest("device_id too long");
        return new Filter(from, to, deviceId, minLag, hasGps);
    }
}
