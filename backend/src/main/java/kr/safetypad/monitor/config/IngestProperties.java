package kr.safetypad.monitor.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Device ingestion settings. The pepper has no default on purpose: the app refuses to start without it. */
@Validated
@ConfigurationProperties("safetypad.ingest")
public record IngestProperties(
        @NotBlank @Size(min = 32, message = "DEVICE_KEY_PEPPER must be at least 32 characters") String deviceKeyPepper,
        @DefaultValue("8192") int maxBodyBytes,
        @DefaultValue("PT10M") Duration maxFutureSkew,
        @DefaultValue("P366D") Duration maxEventAge,
        @DefaultValue("120") int perKeyPerMinute,
        @DefaultValue("600") int perIpPerMinute) {}
