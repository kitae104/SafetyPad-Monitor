package kr.safetypad.monitor.device;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kr.safetypad.monitor.config.IngestProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device API key lifecycle. Keys are high-entropy random values; only an HMAC-SHA256 digest
 * (keyed with a server-side pepper) is persisted, so a database leak alone does not reveal usable keys.
 */
@Service
public class DeviceKeyService {

    public record AuthenticatedDevice(long devicePk, String deviceId, long credentialId, boolean deviceActive) {}

    /** The plaintext key exists only in this object, returned once at issue time. */
    public record IssuedKey(long credentialId, String plaintextKey, String prefix) {
        @Override
        public String toString() {
            return "IssuedKey[credentialId=" + credentialId + ", prefix=" + prefix + "]";
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecretKeySpec pepper;

    public DeviceKeyService(JdbcClient jdbc, Clock clock, IngestProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.pepper = new SecretKeySpec(props.deviceKeyPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String digest(String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(pepper);
            return HexFormat.of().formatHex(mac.doFinal(key.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public Optional<AuthenticatedDevice> authenticate(String key) {
        return jdbc.sql("""
                SELECT c.id AS cred_id, d.id AS dev_pk, d.device_id, d.active
                FROM device_credentials c JOIN devices d ON d.id = c.device_pk
                WHERE c.key_digest = :digest AND c.active AND c.revoked_at IS NULL
                  AND (c.expires_at IS NULL OR c.expires_at > :now)
                """)
                .param("digest", digest(key))
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new AuthenticatedDevice(
                        rs.getLong("dev_pk"), rs.getString("device_id"), rs.getLong("cred_id"), rs.getBoolean("active")))
                .optional();
    }

    /**
     * Is this credential still usable? Long-lived connections (MQTT) authenticate once, so every message re-checks that the
     * key was not revoked/expired and the device was not disabled.
     */
    public Optional<AuthenticatedDevice> revalidate(long credentialId) {
        return jdbc.sql("""
                SELECT c.id AS cred_id, d.id AS dev_pk, d.device_id, d.active
                FROM device_credentials c JOIN devices d ON d.id = c.device_pk
                WHERE c.id = :id AND c.active AND c.revoked_at IS NULL
                  AND (c.expires_at IS NULL OR c.expires_at > :now)
                """)
                .param("id", credentialId)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new AuthenticatedDevice(
                        rs.getLong("dev_pk"), rs.getString("device_id"), rs.getLong("cred_id"), rs.getBoolean("active")))
                .optional();
    }

    @Transactional
    public long registerDevice(String deviceId, String alias, String zone, boolean simulated) {
        return jdbc.sql("""
                INSERT INTO devices (device_id, alias, zone, simulated) VALUES (:id, :alias, :zone, :sim)
                ON CONFLICT (device_id) DO UPDATE SET device_id = EXCLUDED.device_id
                RETURNING id
                """)
                .param("id", deviceId).param("alias", alias).param("zone", zone).param("sim", simulated)
                .query(Long.class).single();
    }

    /** Issue an additional key. Existing keys stay valid until revoked, which allows overlap during rotation. */
    @Transactional
    public IssuedKey issue(String deviceId) {
        long devicePk = jdbc.sql("SELECT id FROM devices WHERE device_id = :id")
                .param("id", deviceId).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown device"));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = "spk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String prefix = key.substring(0, 8);
        long id = jdbc.sql("""
                INSERT INTO device_credentials (device_pk, key_digest, key_prefix, issued_at)
                VALUES (:pk, :digest, :prefix, :now) RETURNING id
                """)
                .param("pk", devicePk).param("digest", digest(key)).param("prefix", prefix)
                .param("now", Timestamp.from(clock.instant()))
                .query(Long.class).single();
        return new IssuedKey(id, key, prefix);
    }

    @Transactional
    public void revoke(long credentialId) {
        jdbc.sql("UPDATE device_credentials SET active = false, revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("id", credentialId).update();
    }
}
