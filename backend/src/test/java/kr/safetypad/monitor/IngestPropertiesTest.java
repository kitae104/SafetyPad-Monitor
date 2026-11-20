package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import kr.safetypad.monitor.config.IngestProperties;
import org.junit.jupiter.api.Test;

class IngestPropertiesTest {
    private static IngestProperties with(String pepper) {
        return new IngestProperties(pepper, 8192, Duration.ofMinutes(10), Duration.ofDays(366), 120, 600);
    }

    @Test
    void blankOrShortPepperIsRejected() {
        try (var f = Validation.buildDefaultValidatorFactory()) {
            var v = f.getValidator();
            assertThat(v.validate(with(""))).isNotEmpty();
            assertThat(v.validate(with("short"))).isNotEmpty();
            assertThat(v.validate(with("x".repeat(32)))).isEmpty();
        }
    }
}
