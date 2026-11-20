package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The production listener: MQTT over TLS only (no plain-text port), with a real self-signed PKCS12 keystore. */
class MqttTlsIT extends AbstractPostgresIT {
    static final String PASSWORD = "changeit-test";
    static final int TLS_PORT;
    static final Path KEYSTORE;

    static {
        try (ServerSocket s = new ServerSocket(0)) {
            TLS_PORT = s.getLocalPort();
            KEYSTORE = Files.createTempDirectory("mqtt-tls-it").resolve("keystore.p12");
            Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "safetypad", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", KEYSTORE.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) throw new IllegalStateException("keytool failed");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void mqtt(DynamicPropertyRegistry r) {
        r.add("safetypad.mqtt.enabled", () -> "true");
        r.add("safetypad.mqtt.allow-plaintext", () -> "false");          // production posture
        r.add("safetypad.mqtt.host", () -> "127.0.0.1");
        r.add("safetypad.mqtt.tls-port", () -> String.valueOf(TLS_PORT));
        r.add("safetypad.mqtt.keystore-path", KEYSTORE::toString);
        r.add("safetypad.mqtt.keystore-password", () -> PASSWORD);
    }

    @Autowired DeviceKeyService keys;
    @Autowired ObjectMapper mapper;

    static SSLContext trustingTheTestCertificate() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(KEYSTORE)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    MqttConnectOptions options(String id, String key, SSLContext ctx) {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName(id);
        o.setPassword(key.toCharArray());
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setAutomaticReconnect(false);
        o.setConnectionTimeout(5);
        if (ctx != null) o.setSocketFactory(ctx.getSocketFactory());
        return o;
    }

    @Test
    void deviceStoresAnEventOverTlsAndPlainTextIsNotOffered() throws Exception {
        String id = "TLS-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, "tls", null, false);
        String key = keys.issue(id).plaintextKey();

        MqttClient c = new MqttClient("ssl://127.0.0.1:" + TLS_PORT, id, new MemoryPersistence());
        BlockingQueue<JsonNode> acks = new LinkedBlockingQueue<>();
        try {
            c.connect(options(id, key, trustingTheTestCertificate()));
            c.subscribe("safetypad/devices/" + id + "/acks", 1, (IMqttMessageListener) (t, m) -> acks.add(mapper.readTree(m.getPayload())));
            String time = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusMinutes(1).withNano(0).toString();
            c.publish("safetypad/devices/" + id + "/events", ("{\"event_id\":\"tls-1\",\"device_id\":\"" + id + "\",\"event_type\":\"MULTI_RIDE\","
                    + "\"event_time\":\"" + time + "\",\"gpsData\":null,\"battery_level\":50}").getBytes(StandardCharsets.UTF_8), 1, false);
            JsonNode ack = acks.poll(10, TimeUnit.SECONDS);
            assertThat(ack).isNotNull();
            assertThat(ack.get("success").asBoolean()).isTrue();
        } finally {
            if (c.isConnected()) c.disconnect();
            c.close();
        }

        // a client that does not trust the server certificate must not get a connection (no silent downgrade)
        MqttClient untrusting = new MqttClient("ssl://127.0.0.1:" + TLS_PORT, id + "x", new MemoryPersistence());
        assertThatThrownBy(() -> untrusting.connect(options(id, key, null))).isInstanceOf(MqttException.class);
        // plain-text MQTT to the TLS port is not accepted either
        MqttClient plain = new MqttClient("tcp://127.0.0.1:" + TLS_PORT, id + "y", new MemoryPersistence());
        assertThatThrownBy(() -> plain.connect(options(id, key, null))).isInstanceOf(MqttException.class);
    }
}
