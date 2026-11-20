package kr.safetypad.monitor.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MQTT ingestion (embedded broker). Disabled unless explicitly enabled. Devices authenticate with
 * username = device_id and password = the device API key, exactly like X-API-Key on the REST endpoint.
 * Plain-text MQTT is refused unless {@code allowPlaintext} is set (local development only); production uses TLS.
 */
@ConfigurationProperties("safetypad.mqtt")
public record MqttProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0.0.0.0") String host,
        /** Plain-text port; 0 = off. Only honoured when allowPlaintext is true. */
        @DefaultValue("1883") int port,
        @DefaultValue("false") boolean allowPlaintext,
        /** TLS port (MQTT over TLS, normally 8883); 0 = off. Needs a keystore. */
        @DefaultValue("0") int tlsPort,
        @DefaultValue("") String keystorePath,
        @DefaultValue("") String keystorePassword,
        @DefaultValue("PKCS12") String keystoreType,
        @DefaultValue("4") int workerThreads,
        @DefaultValue("1000") int queueCapacity,
        /** Failed logins per minute per username before further attempts are refused (brute-force guard). */
        @DefaultValue("20") int failedLoginsPerMinute) {

    public static final String TOPIC_PREFIX = "safetypad/devices/";

    public static String eventsTopic(String deviceId) { return TOPIC_PREFIX + deviceId + "/events"; }
    public static String acksTopic(String deviceId) { return TOPIC_PREFIX + deviceId + "/acks"; }
}
