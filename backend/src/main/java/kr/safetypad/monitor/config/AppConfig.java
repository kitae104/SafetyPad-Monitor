package kr.safetypad.monitor.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({IngestProperties.class, kr.safetypad.monitor.mqtt.MqttProperties.class})
public class AppConfig {
    /** All instants are UTC; conversion to Asia/Seoul happens only at display time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
