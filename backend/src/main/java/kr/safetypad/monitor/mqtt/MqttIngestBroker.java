package kr.safetypad.monitor.mqtt;

import static kr.safetypad.monitor.mqtt.MqttProperties.acksTopic;
import static kr.safetypad.monitor.mqtt.MqttProperties.eventsTopic;

import io.micrometer.core.instrument.MeterRegistry;
import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.security.IAuthenticator;
import io.moquette.broker.security.IAuthorizatorPolicy;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.AbstractInterceptHandler;
import io.moquette.interception.messages.InterceptPublishMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttQoS;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kr.safetypad.monitor.config.IngestProperties;
import kr.safetypad.monitor.device.DeviceKeyService;
import kr.safetypad.monitor.device.DeviceKeyService.AuthenticatedDevice;
import kr.safetypad.monitor.ingestion.ApiException;
import kr.safetypad.monitor.ingestion.EventIntake;
import kr.safetypad.monitor.ingestion.IngestionService;
import kr.safetypad.monitor.security.FixedWindowRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MQTT ingestion over an embedded broker. Same rules as {@code POST /api/v1/device-events}:
 * <ul>
 *   <li>Login: username = device_id, password = device API key (hash lookup, rotation and disabling work unchanged);
 *       client id must equal the device_id so one device can never take over another's session.</li>
 *   <li>ACL: a device may publish only to {@code safetypad/devices/<its id>/events} and subscribe only to its own
 *       {@code .../acks}. Wildcards never match, so no device can read another device's traffic.</li>
 *   <li>Commit confirmation: MQTT's PUBACK only means the broker received the bytes. The event is stored by a worker
 *       afterwards, and ONLY THEN the backend publishes the result on {@code .../acks}. A device must keep an event until
 *       it sees a success ack for its event_id (re-sending is safe: duplicates are acknowledged without changes).</li>
 *   <li>Every message re-checks the credential (revoked key / disabled device take effect at once).</li>
 * </ul>
 * Payloads, passwords and keys are never logged.
 */
@Component
@ConditionalOnProperty(prefix = "safetypad.mqtt", name = "enabled", havingValue = "true")
public class MqttIngestBroker implements IAuthenticator, IAuthorizatorPolicy, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MqttIngestBroker.class);
    private static final int MAX_KEY_LENGTH = 128;
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final String INTERNAL_CLIENT = "safetypad-backend";

    private final MqttProperties props;
    private final IngestProperties ingestProps;
    private final DeviceKeyService keys;
    private final EventIntake intake;
    private final FixedWindowRateLimiter limiter;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    private final Server server = new Server();
    /** clientId (== device_id) -> credential that logged in. Bounded by the number of devices. */
    private final ConcurrentHashMap<String, AuthenticatedDevice> sessions = new ConcurrentHashMap<>();
    private ThreadPoolExecutor workers;
    private volatile boolean running;

    public MqttIngestBroker(MqttProperties props, IngestProperties ingestProps, DeviceKeyService keys, EventIntake intake,
                            FixedWindowRateLimiter limiter, ObjectMapper mapper, MeterRegistry meters) {
        this.props = props;
        this.ingestProps = ingestProps;
        this.keys = keys;
        this.intake = intake;
        this.limiter = limiter;
        this.mapper = mapper;
        this.meters = meters;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        boolean tls = props.tlsPort() > 0;
        boolean plain = props.allowPlaintext() && props.port() > 0;
        if (!tls && !plain) {
            throw new IllegalStateException("MQTT is enabled but no listener is allowed: set safetypad.mqtt.tls-port (+ keystore) "
                    + "or, for local development only, safetypad.mqtt.allow-plaintext=true");
        }
        if (tls && (props.keystorePath().isBlank() || props.keystorePassword().isBlank())) {
            throw new IllegalStateException("MQTT TLS needs safetypad.mqtt.keystore-path and keystore-password");
        }
        Properties p = new Properties();
        p.setProperty(BrokerConstants.HOST_PROPERTY_NAME, props.host());
        p.setProperty(BrokerConstants.PORT_PROPERTY_NAME, plain ? String.valueOf(props.port()) : BrokerConstants.DISABLED_PORT_BIND);
        if (tls) {
            p.setProperty(BrokerConstants.SSL_PORT_PROPERTY_NAME, String.valueOf(props.tlsPort()));
            p.setProperty(BrokerConstants.JKS_PATH_PROPERTY_NAME, props.keystorePath());
            p.setProperty(BrokerConstants.KEY_STORE_TYPE, props.keystoreType());
            p.setProperty(BrokerConstants.KEY_STORE_PASSWORD_PROPERTY_NAME, props.keystorePassword());
            p.setProperty(BrokerConstants.KEY_MANAGER_PASSWORD_PROPERTY_NAME, props.keystorePassword());
        }
        p.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "false");
        p.setProperty(BrokerConstants.ALLOW_ZERO_BYTE_CLIENT_ID_PROPERTY_NAME, "false");
        p.setProperty(BrokerConstants.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");   // nothing is stored by the broker; the DB is the record
        p.setProperty(BrokerConstants.ENABLE_TELEMETRY_NAME, "false");               // no phoning home
        p.setProperty(BrokerConstants.NETTY_MAX_BYTES_PROPERTY_NAME, String.valueOf(ingestProps.maxBodyBytes() + 1024));
        workers = new ThreadPoolExecutor(props.workerThreads(), props.workerThreads(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.queueCapacity()), r -> {
                    Thread t = new Thread(r, "mqtt-ingest");
                    t.setDaemon(true);
                    return t;
                });
        try {
            server.startServer(new MemoryConfig(p), List.of(new Handler()), null, this, this);
        } catch (IOException e) {
            throw new IllegalStateException("MQTT broker failed to start: " + e.getMessage(), e);
        }
        running = true;
        log.info("MQTT ingestion started (tls={}, plaintext={})", tls, plain);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server.stopServer();
    }

    @Override
    public boolean isRunning() { return running; }

    /** Actual plain-text port (tests use an ephemeral one). */
    public int port() { return server.getPort(); }

    // ---------------------------------------------------------------- login + ACL

    @Override
    public boolean checkValid(String clientId, String username, byte[] password) {
        if (clientId == null || username == null || password == null || !DEVICE_ID.matcher(username).matches()
                || password.length == 0 || password.length > MAX_KEY_LENGTH) {
            return false;
        }
        String failKey = "mqtt-fail:" + username;
        if (limiter.peek(failKey) >= props.failedLoginsPerMinute()) {
            meters.counter("safetypad.mqtt.logins", "result", "throttled").increment();
            return false;
        }
        AuthenticatedDevice d;
        try {
            d = keys.authenticate(new String(password, StandardCharsets.UTF_8)).orElse(null);
        } catch (DataAccessException e) {
            log.error("MQTT credential lookup failed: {}", e.getClass().getSimpleName());
            return false;                                                              // device reconnects later
        }
        if (d == null || !d.deviceActive() || !d.deviceId().equals(username) || !clientId.equals(username)) {
            limiter.tryAcquire(failKey, Integer.MAX_VALUE);                           // count the failure
            meters.counter("safetypad.mqtt.logins", "result", "rejected").increment();
            log.warn("Rejected MQTT login (unknown/revoked key, disabled device or mismatching username/client id)");
            return false;
        }
        sessions.put(clientId, d);
        meters.counter("safetypad.mqtt.logins", "result", "accepted").increment();
        return true;
    }

    @Override
    public boolean canWrite(Topic topic, String user, String client) {
        return user != null && topic.toString().equals(eventsTopic(user));
    }

    @Override
    public boolean canRead(Topic topic, String user, String client) {
        return user != null && topic.toString().equals(acksTopic(user));
    }

    // ---------------------------------------------------------------- message path

    private final class Handler extends AbstractInterceptHandler {
        @Override
        public String getID() { return "safetypad-ingest"; }

        @Override
        public void onSessionLoopError(Throwable error) {
            log.error("MQTT session loop error: {}", error.getClass().getSimpleName());
        }

        @Override
        public Class<?>[] getInterceptedMessageTypes() { return new Class<?>[] {InterceptPublishMessage.class}; }

        @Override
        public void onPublish(InterceptPublishMessage msg) {
            String user = msg.getUsername();
            if (user == null || !eventsTopic(user).equals(msg.getTopicName())) return;   // our own acks / anything else
            String clientId = msg.getClientID();
            ByteBuf buf = msg.getPayload();
            int n = buf.readableBytes();
            if (n > ingestProps.maxBodyBytes()) {
                reject(user, null, 413, "PAYLOAD_TOO_LARGE", "Payload exceeds " + ingestProps.maxBodyBytes() + " bytes");
                return;
            }
            byte[] bytes = new byte[n];
            buf.getBytes(buf.readerIndex(), bytes);                                      // the buffer is released after this callback
            try {
                workers.execute(() -> process(user, clientId, bytes));
            } catch (RejectedExecutionException e) {
                reject(user, null, 503, "SERVICE_UNAVAILABLE", "Temporarily unavailable, retry safely");
            }
        }
    }

    private void process(String user, String clientId, byte[] bytes) {
        JsonNode body = null;
        try {
            AuthenticatedDevice session = sessions.get(clientId);
            if (session == null || !session.deviceId().equals(user)) {
                reject(user, null, 401, "UNAUTHORIZED", "Reconnect with a valid key");
                disconnectLater(clientId);
                return;
            }
            try {
                body = mapper.readTree(bytes);
            } catch (RuntimeException e) {
                body = null;
            }
            if (body == null || !body.isObject()) {
                reject(user, null, 400, "BAD_REQUEST", "Malformed or missing JSON body");
                return;
            }
            String eventId = eventIdOf(body);
            AuthenticatedDevice d = keys.revalidate(session.credentialId()).orElse(null);   // revoked key / disabled device
            if (d == null || !d.deviceActive()) {
                sessions.remove(clientId);
                reject(user, eventId, d == null ? 401 : 403, d == null ? "UNAUTHORIZED" : "DEVICE_DISABLED", "Key or device is no longer allowed");
                disconnectLater(clientId);
                return;
            }
            if (!limiter.tryAcquire("cred:" + d.credentialId(), ingestProps.perKeyPerMinute())) {
                reject(user, eventId, 429, "TOO_MANY_REQUESTS", "Rate limit exceeded");
                return;
            }
            IngestionService.Result r = intake.accept(d, body);                          // returns only after commit
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("success", true);
            ack.put("event_id", r.eventId());
            ack.put("duplicate", r.duplicate());
            meters.counter("safetypad.mqtt.messages", "result", r.duplicate() ? "duplicate" : "accepted").increment();
            publishAck(user, ack);
        } catch (ApiException e) {
            reject(user, body == null ? null : eventIdOf(body), e.status(), e.code(), e.getMessage());
        } catch (DataAccessException e) {
            log.error("Database failure while ingesting MQTT message: {}", e.getClass().getSimpleName());
            reject(user, body == null ? null : eventIdOf(body), 503, "SERVICE_UNAVAILABLE", "Temporarily unavailable, retry safely");
        } catch (RuntimeException e) {
            log.error("Unexpected failure while ingesting MQTT message", e);
            reject(user, body == null ? null : eventIdOf(body), 500, "INTERNAL_ERROR", "Internal error");
        }
    }

    /** Closes the connection a moment later so the negative ack just published can still be flushed to the device. */
    private void disconnectLater(String clientId) {
        java.util.concurrent.CompletableFuture.runAsync(() -> server.disconnectClient(clientId),
                java.util.concurrent.CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS));
    }

    private static String eventIdOf(JsonNode body) {
        JsonNode n = body.get("event_id");
        return n != null && n.isString() && n.asString().length() <= 128 ? n.asString() : null;
    }

    /** Negative acknowledgement. 4xx = fix the payload and do not blindly retry; 429/5xx = retry later with the same event_id. */
    private void reject(String user, String eventId, int status, String code, String message) {
        meters.counter("safetypad.mqtt.messages", "result", "rejected", "status", String.valueOf(status)).increment();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("success", false);
        if (eventId != null) ack.put("event_id", eventId);
        ack.put("status", status);
        ack.put("error", code);
        ack.put("message", message);
        publishAck(user, ack);
    }

    private void publishAck(String user, Map<String, Object> ack) {
        try {
            var msg = MqttMessageBuilders.publish().topicName(acksTopic(user)).retained(false).qos(MqttQoS.AT_LEAST_ONCE)
                    .payload(Unpooled.copiedBuffer(mapper.writeValueAsString(ack), StandardCharsets.UTF_8)).build();
            server.internalPublish(msg, INTERNAL_CLIENT);
        } catch (RuntimeException e) {
            // The event itself is already committed; an unsent ack just makes the device retry and receive duplicate=true.
            log.warn("Could not publish MQTT ack: {}", e.getClass().getSimpleName());
        }
    }
}
