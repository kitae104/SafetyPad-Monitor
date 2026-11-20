package kr.safetypad.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kr.safetypad.monitor.device.DeviceKeyService;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MQTT ingestion against the real embedded broker and a real PostgreSQL (no mocks), using a real MQTT client. */
class MqttIngestIT extends AbstractPostgresIT {
    static final int PORT = freePort();

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void mqtt(DynamicPropertyRegistry r) {
        r.add("safetypad.mqtt.enabled", () -> "true");
        r.add("safetypad.mqtt.allow-plaintext", () -> "true");
        r.add("safetypad.mqtt.host", () -> "127.0.0.1");
        r.add("safetypad.mqtt.port", () -> String.valueOf(PORT));
        r.add("safetypad.mqtt.failed-logins-per-minute", () -> "5");
    }

    @Autowired DeviceKeyService keys;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    record Dev(String id, String key) {}

    Dev newDevice() {
        String id = "M-" + UUID.randomUUID().toString().substring(0, 8);
        keys.registerDevice(id, "mqtt-test", "test", false);
        return new Dev(id, keys.issue(id).plaintextKey());
    }

    String eventJson(String deviceId, String eventId, String gps) {
        String time = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusMinutes(1).withNano(0).toString();
        return "{\"event_id\":\"" + eventId + "\",\"device_id\":\"" + deviceId + "\",\"event_type\":\"MULTI_RIDE\","
                + "\"event_time\":\"" + time + "\",\"gpsData\":" + gps + ",\"battery_level\":55}";
    }

    static final String GPS = "{\"latitude\":37.451448,\"longitude\":126.651542}";

    /** A device as the firmware would behave: connect, subscribe to its own acks, publish with QoS 1. */
    final class Device implements AutoCloseable {
        final MqttClient client;
        final String id;
        final BlockingQueue<JsonNode> acks = new LinkedBlockingQueue<>();

        Device(String username, String clientId, String key) throws MqttException {
            this.id = username;
            client = new MqttClient("tcp://127.0.0.1:" + PORT, clientId, new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setUserName(username);
            o.setPassword(key.toCharArray());
            o.setCleanSession(true);
            o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);   // no silent 3.1 retry (it would double-count failed logins)
            o.setAutomaticReconnect(false);
            o.setConnectionTimeout(5);
            client.connect(o);
        }

        void listenToOwnAcks() throws MqttException {
            client.subscribe("safetypad/devices/" + id + "/acks", 1, (IMqttMessageListener) (t, m) -> acks.add(mapper.readTree(m.getPayload())));
        }

        void publish(String body) throws MqttException {
            client.publish("safetypad/devices/" + id + "/events", body.getBytes(StandardCharsets.UTF_8), 1, false);
        }

        JsonNode ack() throws InterruptedException {
            JsonNode n = acks.poll(10, TimeUnit.SECONDS);
            assertThat(n).as("an ack arrives after the commit").isNotNull();
            return n;
        }

        @Override
        public void close() {
            try {
                if (client.isConnected()) client.disconnect();
                client.close();
            } catch (MqttException ignored) {
                // test teardown
            }
        }
    }

    long count(String deviceId) {
        return jdbc.sql("SELECT count(*) FROM device_events e JOIN devices d ON d.id = e.device_pk WHERE d.device_id = :d")
                .param("d", deviceId).query(Long.class).single();
    }

    Instant receivedAt(String deviceId, String eventId) {
        return jdbc.sql("SELECT e.received_at FROM device_events e JOIN devices d ON d.id = e.device_pk WHERE d.device_id = :d AND e.event_id = :e")
                .param("d", deviceId).param("e", eventId).query(Timestamp.class).single().toInstant();
    }

    @Test
    void eventIsStoredThenAcknowledgedAndRetryIsADuplicateThatKeepsTheFirstReceivedAt() throws Exception {
        Dev d = newDevice();
        try (Device dev = new Device(d.id(), d.id(), d.key())) {
            dev.listenToOwnAcks();
            dev.publish(eventJson(d.id(), "evt-1", GPS));
            JsonNode ack = dev.ack();
            assertThat(ack.get("success").asBoolean()).isTrue();
            assertThat(ack.get("event_id").asString()).isEqualTo("evt-1");
            assertThat(ack.get("duplicate").asBoolean()).isFalse();
            assertThat(count(d.id())).isEqualTo(1);                          // already committed when the ack was seen
            Instant first = receivedAt(d.id(), "evt-1");

            Thread.sleep(1100);
            dev.publish(eventJson(d.id(), "evt-1", GPS));                    // the device never saw the ack and retries
            JsonNode again = dev.ack();
            assertThat(again.get("success").asBoolean()).isTrue();
            assertThat(again.get("duplicate").asBoolean()).isTrue();
            assertThat(count(d.id())).isEqualTo(1);
            assertThat(receivedAt(d.id(), "evt-1")).isEqualTo(first);        // never overwritten by a retry
        }
    }

    @Test
    void eventWithoutGpsIsAccepted() throws Exception {
        Dev d = newDevice();
        try (Device dev = new Device(d.id(), d.id(), d.key())) {
            dev.listenToOwnAcks();
            dev.publish(eventJson(d.id(), "nogps", "null"));
            assertThat(dev.ack().get("success").asBoolean()).isTrue();
        }
    }

    @Test
    void invalidPayloadsGetTheSameStatusCodesAsRest() throws Exception {
        Dev d = newDevice();
        Dev other = newDevice();
        try (Device dev = new Device(d.id(), d.id(), d.key())) {
            dev.listenToOwnAcks();

            dev.publish("{ not json");
            JsonNode bad = dev.ack();
            assertThat(bad.get("status").asInt()).isEqualTo(400);
            assertThat(bad.get("success").asBoolean()).isFalse();

            dev.publish(eventJson(other.id(), "mismatch", GPS));             // payload claims another device
            JsonNode mismatch = dev.ack();
            assertThat(mismatch.get("status").asInt()).isEqualTo(403);
            assertThat(mismatch.get("error").asString()).isEqualTo("DEVICE_MISMATCH");
            assertThat(mismatch.get("event_id").asString()).isEqualTo("mismatch");

            dev.publish(eventJson(d.id(), "badlat", "{\"latitude\":123.0,\"longitude\":126.6}"));
            assertThat(dev.ack().get("status").asInt()).isEqualTo(422);

            assertThat(count(d.id())).isZero();
            assertThat(count(other.id())).isZero();

            dev.publish("{\"pad\":\"" + "x".repeat(9000) + "\"}");           // above the 8 KiB limit
            assertThat(dev.ack().get("status").asInt()).isEqualTo(413);
        }
    }

    @Test
    void loginRequiresAValidKeyMatchingUsernameAndClientId() throws Exception {
        Dev a = newDevice();
        Dev b = newDevice();
        assertThatThrownBy(() -> new Device(a.id(), a.id(), "spk_wrong_key_value").close()).isInstanceOf(MqttException.class);
        assertThatThrownBy(() -> new Device(a.id(), a.id(), b.key()).close()).as("key belongs to another device").isInstanceOf(MqttException.class);
        assertThatThrownBy(() -> new Device(a.id(), "someone-else", a.key()).close()).as("client id must be the device id").isInstanceOf(MqttException.class);
        assertThatThrownBy(() -> {
            MqttClient c = new MqttClient("tcp://127.0.0.1:" + PORT, "anon", new MemoryPersistence());
            c.connect();                                                      // anonymous
        }).isInstanceOf(MqttException.class);
        new Device(a.id(), a.id(), a.key()).close();                          // the right combination works
    }

    @Test
    void aDeviceCannotUseAnotherDevicesTopics() throws Exception {
        Dev a = newDevice();
        Dev b = newDevice();
        try (Device da = new Device(a.id(), a.id(), a.key()); Device db = new Device(b.id(), b.id(), b.key())) {
            db.listenToOwnAcks();
            // a may not read b's acks nor any wildcard
            assertThatThrownBy(() -> da.client.subscribe("safetypad/devices/" + b.id() + "/acks", 1)).isInstanceOf(MqttException.class);
            assertThatThrownBy(() -> da.client.subscribe("safetypad/devices/#", 1)).isInstanceOf(MqttException.class);
            assertThatThrownBy(() -> da.client.subscribe("#", 1)).isInstanceOf(MqttException.class);
        }
        // a may not publish into b's events topic: nothing is stored for either device
        try (Device da = new Device(a.id(), a.id(), a.key())) {
            da.client.setTimeToWait(2000);                                    // an unauthorised publish is never acknowledged: do not block forever
            try {
                da.client.publish("safetypad/devices/" + b.id() + "/events", eventJson(b.id(), "forged", GPS).getBytes(StandardCharsets.UTF_8), 1, false);
            } catch (MqttException ignored) {
                // the broker may drop the connection instead of acknowledging; either way nothing may be stored
            }
            Thread.sleep(1500);
            assertThat(count(b.id())).isZero();
            assertThat(count(a.id())).isZero();
        }
    }

    @Test
    void revokedKeyAndDisabledDeviceAreRefusedOnTheNextMessage() throws Exception {
        Dev d = newDevice();
        try (Device dev = new Device(d.id(), d.id(), d.key())) {
            dev.listenToOwnAcks();
            dev.publish(eventJson(d.id(), "before", GPS));
            assertThat(dev.ack().get("success").asBoolean()).isTrue();

            long credId = jdbc.sql("SELECT c.id FROM device_credentials c JOIN devices x ON x.id = c.device_pk WHERE x.device_id = :d")
                    .param("d", d.id()).query(Long.class).single();
            keys.revoke(credId);
            dev.publish(eventJson(d.id(), "after-revoke", GPS));
            JsonNode ack = dev.ack();
            assertThat(ack.get("status").asInt()).isEqualTo(401);
            assertThat(count(d.id())).isEqualTo(1);                           // only the first event
        }
        assertThatThrownBy(() -> new Device(d.id(), d.id(), d.key())).as("revoked key can no longer log in").isInstanceOf(MqttException.class);

        Dev e = newDevice();
        try (Device dev = new Device(e.id(), e.id(), e.key())) {
            dev.listenToOwnAcks();
            jdbc.sql("UPDATE devices SET active = false WHERE device_id = :d").param("d", e.id()).update();
            dev.publish(eventJson(e.id(), "disabled", GPS));
            assertThat(dev.ack().get("status").asInt()).isEqualTo(403);
            assertThat(count(e.id())).isZero();
        }
    }

    @Test
    void repeatedBadLoginsAreThrottledPerUsername() throws Exception {
        Dev a = newDevice();
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> new Device(a.id(), a.id(), "spk_guess_" + UUID.randomUUID()).close()).isInstanceOf(MqttException.class);
        }
        // the correct key is refused too while the throttle is active (brute-force guard), so a guesser gets no signal
        assertThatThrownBy(() -> new Device(a.id(), a.id(), a.key()).close()).isInstanceOf(MqttException.class);
    }
}
