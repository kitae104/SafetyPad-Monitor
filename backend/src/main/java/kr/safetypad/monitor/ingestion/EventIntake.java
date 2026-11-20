package kr.safetypad.monitor.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import kr.safetypad.monitor.config.IngestProperties;
import kr.safetypad.monitor.device.DeviceKeyService.AuthenticatedDevice;
import kr.safetypad.monitor.ingestion.EventPayloadParser.ParsedEvent;
import kr.safetypad.monitor.ingestion.EventPayloadParser.ValidEvent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The one place that turns an already authenticated device's JSON payload into a stored event, whatever the transport
 * (HTTPS POST or MQTT). Keeping a single path guarantees identical validation, idempotency and received_at rules:
 * 400 malformed, 403 device_id mismatch, 422 semantic invalid; success only after the transaction committed.
 */
@Service
public class EventIntake {
    private final IngestionService service;
    private final IngestProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public EventIntake(IngestionService service, IngestProperties props, Clock clock, MeterRegistry meters) {
        this.service = service;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    /** @throws ApiException 400/403/422; DataAccessException (transient, caller answers 503) */
    public IngestionService.Result accept(AuthenticatedDevice device, JsonNode body) {
        ParsedEvent parsed = EventPayloadParser.parse(body);                         // 400
        if (!parsed.deviceId().equals(device.deviceId())) {                           // 403
            throw new ApiException(403, "DEVICE_MISMATCH", "API key is not associated with this device_id");
        }
        ValidEvent event = EventPayloadParser.validate(parsed, clock.instant(),       // 422
                props.maxFutureSkew(), props.maxEventAge());
        IngestionService.Result r = service.ingest(device.devicePk(), event, body);   // commits before returning
        // Counted only after the transaction committed, so metrics never claim an event that was rolled back.
        meters.counter("safetypad.ingest.events", "result", r.duplicate() ? "duplicate" : "accepted").increment();
        return r;
    }
}
