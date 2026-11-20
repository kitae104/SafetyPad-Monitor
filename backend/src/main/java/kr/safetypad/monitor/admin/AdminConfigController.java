package kr.safetypad.monitor.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.safetypad.monitor.demo.DemoFixtureService;
import kr.safetypad.monitor.mqtt.MqttProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the SPA which optional capabilities exist. Flags come from the server; the UI never decides on its own. */
@RestController
public class AdminConfigController {
    private final ObjectProvider<DemoFixtureService> demo;
    private final boolean routeEnabled;
    private final boolean locationIngest;
    private final String mapProvider;
    private final String mapApiKey;
    private final MqttProperties mqtt;

    public AdminConfigController(ObjectProvider<DemoFixtureService> demo,
                                 @Value("${safetypad.route-enabled:false}") boolean routeEnabled,
                                 @Value("${safetypad.location-ping-ingest-enabled:false}") boolean locationIngest,
                                 @Value("${safetypad.map.provider:mock}") String mapProvider,
                                 @Value("${safetypad.map.api-key:}") String mapApiKey,
                                 MqttProperties mqtt) {
        this.demo = demo;
        this.routeEnabled = routeEnabled;
        this.locationIngest = locationIngest;
        this.mapProvider = mapProvider;
        this.mapApiKey = mapApiKey;
        this.mqtt = mqtt;
    }

    @GetMapping("/api/v1/admin/config")
    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demo_tools", demo.getIfAvailable() != null);
        m.put("route_enabled", routeEnabled);
        m.put("location_ping_ingest_enabled", locationIngest);
        // Kakao *JavaScript* keys are browser-public by design (protected by the registered-domain allowlist), unlike
        // the REST/admin keys. Only the JS key may ever be put in MAP_API_KEY. Served to authenticated admins only.
        boolean kakao = "kakao".equalsIgnoreCase(mapProvider) && !mapApiKey.isBlank();
        m.put("map_provider", kakao ? "kakao" : "mock");
        m.put("map_js_key", kakao ? mapApiKey.trim() : null);
        m.put("timezone", "Asia/Seoul");
        // Ports only (no host, no secrets): tells operators which MQTT listeners this server actually opened.
        m.put("mqtt_enabled", mqtt.enabled());
        m.put("mqtt_tls_port", mqtt.enabled() && mqtt.tlsPort() > 0 ? mqtt.tlsPort() : null);
        m.put("mqtt_plaintext_port", mqtt.enabled() && mqtt.allowPlaintext() && mqtt.port() > 0 ? mqtt.port() : null);
        return m;
    }
}
