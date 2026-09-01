/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.license;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.license.LicenseInfo;
import org.thingsboard.server.exception.EntitiesLimitExceededException;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Offline licence enforcement. Verifies the key at boot and on a timer, binds it to the instance UUID in the
 * local database, blocks creates once a cap is reached, and terminates the process on any critical violation.
 * <p>
 * No network access at any point: the public key is compiled in, and the only state is one local row.
 */
@Service
@Profile("!install")
@Slf4j
public class DefaultLicenseService implements LicenseService {

    /** Plan key per entity type. An entity type absent here is never capped. */
    private static final Map<EntityType, String> CAP_KEYS = Map.of(
            EntityType.DEVICE, "maxDevices",
            EntityType.ASSET, "maxAssets");

    private final LicenseCodec codec;
    private final LicenseDao licenseDao;

    @Autowired(required = false)
    private ConfigurableApplicationContext context;

    @Value("${license.key:}")
    private String licenseKey;

    @Value("${license.clock_tolerance_ms:3600000}")
    private long clockToleranceMs;

    private Clock clock = Clock.systemUTC();

    private volatile LicensePayload payload;
    private volatile UUID instanceId;

    public DefaultLicenseService(LicenseCodec codec, LicenseDao licenseDao) {
        this.codec = codec;
        this.licenseDao = licenseDao;
    }

    @PostConstruct
    public void init() {
        // Deliberately not wrapped: an unreadable licence state table is a broken deployment, not a licence
        // violation. Letting it fail bean creation surfaces the real SQL cause, where reporting it as a
        // licence exit code would send an operator hunting for a key problem that does not exist.
        instanceId = licenseDao.readOrCreateInstanceId();
        try {
            payload = verify();
            log.info("Inferrix licence accepted. Customer [{}], instance [{}], expires [{}] ({} day(s) left), "
                            + "maxDevices [{}], maxAssets [{}]",
                    payload.cust(), instanceId, payload.exp(), daysRemaining(),
                    capOrUnlimited("maxDevices"), capOrUnlimited("maxAssets"));
            warnIfOverCapacity();
        } catch (LicenseException e) {
            reportAndExit(e);
        }
    }

    @Scheduled(initialDelayString = "${license.check_interval_ms:3600000}",
            fixedDelayString = "${license.check_interval_ms:3600000}")
    public void scheduledCheck() {
        if (payload == null) {
            return; // boot already failed; a shutdown is in flight
        }
        try {
            checkExpiry(payload);
            checkClock();
            licenseDao.advanceHighWaterTs(clock.millis());
            warnIfOverCapacity();
        } catch (LicenseException e) {
            reportAndExit(e);
        }
    }

    private LicensePayload verify() {
        if (licenseKey == null || licenseKey.isBlank()) {
            throw new LicenseException(LicenseViolation.NO_KEY);
        }
        LicensePayload decoded = codec.decodeAndVerify(licenseKey.trim());
        if (!LicenseCodec.sha3Hex(instanceId.toString()).equals(decoded.iid())) {
            throw new LicenseException(LicenseViolation.WRONG_INSTANCE,
                    "this instance is " + instanceId);
        }
        checkExpiry(decoded);
        checkClock();
        licenseDao.advanceHighWaterTs(clock.millis());
        return decoded;
    }

    private void checkExpiry(LicensePayload decoded) {
        if (clock.millis() > decoded.expMillis()) {
            throw new LicenseException(LicenseViolation.EXPIRED, "expired at epoch second " + decoded.exp());
        }
    }

    private void checkClock() {
        long highWater = licenseDao.readHighWaterTs();
        long now = clock.millis();
        if (now < highWater - clockToleranceMs) {
            throw new LicenseException(LicenseViolation.CLOCK_ROLLBACK,
                    "clock reads " + now + " but the recorded high-water mark is " + highWater);
        }
    }

    @Override
    public void checkCreateAllowed(TenantId tenantId, EntityType entityType) {
        LicensePayload current = payload;
        if (current == null) {
            return;
        }
        String capKey = CAP_KEYS.get(entityType);
        if (capKey == null) {
            return;
        }
        Long cap = current.cap(capKey);
        if (cap == null) {
            return; // absent means unlimited: never issue the count query
        }
        long count = licenseDao.countEntities(entityType);
        if (count >= cap) {
            log.warn("Inferrix licence cap reached: {} {}/{}", entityType, count, cap);
            throw new EntitiesLimitExceededException(tenantId, entityType, cap);
        }
    }

    @Override
    public LicenseInfo getInfo() {
        LicensePayload current = payload;
        if (current == null) {
            return null;
        }
        LicenseInfo info = new LicenseInfo();
        info.setCustomer(current.cust());
        info.setInstanceId(instanceId == null ? null : instanceId.toString());
        info.setExpiresAt(current.exp());
        info.setDaysRemaining(daysRemaining());
        info.setDevices(licenseDao.countEntities(EntityType.DEVICE));
        info.setMaxDevices(current.cap("maxDevices"));
        info.setAssets(licenseDao.countEntities(EntityType.ASSET));
        info.setMaxAssets(current.cap("maxAssets"));
        return info;
    }

    private long daysRemaining() {
        LicensePayload current = payload;
        if (current == null) {
            return 0L;
        }
        long remaining = current.expMillis() - clock.millis();
        return remaining <= 0 ? 0L : TimeUnit.MILLISECONDS.toDays(remaining);
    }

    private Object capOrUnlimited(String key) {
        Long cap = payload == null ? null : payload.cap(key);
        return cap == null ? "unlimited" : cap;
    }

    private void warnIfOverCapacity() {
        LicensePayload current = payload;
        if (current == null) {
            return;
        }
        CAP_KEYS.forEach((entityType, capKey) -> {
            Long cap = current.cap(capKey);
            if (cap == null) {
                return;
            }
            long count = licenseDao.countEntities(entityType);
            if (count > cap) {
                log.warn("Inferrix licence over capacity: {} {}/{}. New {} creates are blocked until the "
                        + "count drops below the cap.", entityType, count, cap, entityType);
            }
        });
    }

    private void reportAndExit(LicenseException e) {
        log.error("{}", e.getMessage());
        if (e.getViolation() == LicenseViolation.NO_KEY) {
            log.error("Instance ID: {}", instanceId);
            log.error("Send this ID to Inferrix to obtain a licence key, then set");
            log.error("  export INFERRIX_LICENSE_KEY=\"...\"");
            log.error("in thingsboard.conf and restart.");
        }
        log.error("Terminating due to a critical licence error, exit code [{}]...",
                e.getViolation().getExitCode());
        shutdown(e.getViolation().getExitCode());
    }

    /**
     * Terminates the platform. A separate thread, because calling into the context's own shutdown from a
     * {@code @PostConstruct} or a scheduler thread deadlocks. {@code System.exit} sits in a finally so a
     * failure inside Spring's shutdown still ends the process. Overridden in tests to capture the code.
     */
    protected void shutdown(int exitCode) {
        new Thread(() -> {
            int code = exitCode;
            try {
                if (context != null) {
                    code = SpringApplication.exit(context, () -> exitCode);
                }
            } finally {
                System.exit(code);
            }
        }, "License Shutdown").start();
    }

    // Test seams.
    void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    void setClockToleranceMs(long clockToleranceMs) {
        this.clockToleranceMs = clockToleranceMs;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
