package kr.safetypad.monitor.device;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEV PROFILE ONLY. With DEV_PROVISION_DEVICES=PAD-0001,PAD-0002 it registers those devices as simulated
 * and writes a freshly issued key to .dev-secrets/<id>.key (mode 600, gitignored). Existing key files are kept.
 * Never active outside the "dev" profile, and never prints key material to logs.
 */
@Component
@Profile("dev")
public class DevDeviceProvisioner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevDeviceProvisioner.class);

    private final DeviceKeyService keys;

    public DevDeviceProvisioner(DeviceKeyService keys) {
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String ids = System.getenv("DEV_PROVISION_DEVICES");
        if (ids == null || ids.isBlank()) return;
        Path dir = Path.of(System.getenv().getOrDefault("DEV_SECRETS_DIR", "../.dev-secrets"));
        Files.createDirectories(dir);
        for (String id : Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!id.matches("[A-Za-z0-9._:\\-]{1,64}")) {
                log.warn("Skipping invalid device id in DEV_PROVISION_DEVICES");
                continue;
            }
            Path file = dir.resolve(id + ".key");
            keys.registerDevice(id, "테스트 발판 " + id, "가상 데이터", true);
            if (Files.exists(file)) continue;
            var issued = keys.issue(id);
            Files.writeString(file, issued.plaintextKey() + "\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            log.info("Provisioned simulated device {} (key prefix {}), key file written", id, issued.prefix());
        }
    }
}
