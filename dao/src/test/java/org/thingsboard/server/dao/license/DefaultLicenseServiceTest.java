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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.exception.EntitiesLimitExceededException;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultLicenseServiceTest {

    private static final UUID INSTANCE_ID = UUID.fromString("9f3a1c02-4b6d-4c3e-9a10-2f7c5d3e8b71");
    private static final long EXP_SECONDS = 1795033600L;          // 2026-11-14T00:26:40Z
    private static final long BEFORE_EXP_MILLIS = (EXP_SECONDS - 86_400L) * 1000L;
    private static final long AFTER_EXP_MILLIS = (EXP_SECONDS + 86_400L) * 1000L;
    private static final long TOLERANCE_MILLIS = 3_600_000L;

    private LicenseDao dao;
    private LicenseCodec codec;
    private String goldenKey;
    private String fixturePublicKey;

    /** Captures exit codes instead of terminating the JVM. */
    private static class TestableLicenseService extends DefaultLicenseService {
        final List<Integer> exits = new ArrayList<>();

        TestableLicenseService(LicenseCodec codec, LicenseDao dao) {
            super(codec, dao);
        }

        @Override
        protected void shutdown(int exitCode) {
            exits.add(exitCode);
        }
    }

    private TestableLicenseService service(String key, long nowMillis) {
        TestableLicenseService s = new TestableLicenseService(codec, dao);
        s.setLicenseKey(key);
        s.setClockToleranceMs(TOLERANCE_MILLIS);
        s.setClock(Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC));
        return s;
    }

    @Before
    public void setUp() throws Exception {
        JsonNode vectors;
        try (InputStream in = getClass().getResourceAsStream("/license/license-vectors.json")) {
            vectors = new ObjectMapper().readTree(in);
        }
        fixturePublicKey = vectors.get("publicKeyBase64").asText();
        goldenKey = vectors.get("expectedKey").asText();
        codec = new LicenseCodec(fixturePublicKey);
        dao = mock(LicenseDao.class);
        // The golden vector's iid must correspond to INSTANCE_ID for the happy paths to pass.
        when(dao.readOrCreateInstanceId()).thenReturn(INSTANCE_ID);
        when(dao.readHighWaterTs()).thenReturn(0L);
    }

    @Test
    public void blankKeyExitsThirteen() {
        for (String blank : new String[]{null, "", "   ", "\n"}) {
            TestableLicenseService s = service(blank, BEFORE_EXP_MILLIS);
            s.init();
            assertThat(s.exits).containsExactly(LicenseViolation.NO_KEY.getExitCode());
        }
    }

    @Test
    public void blankKeyStillReadsTheInstanceIdSoItCanBeLogged() {
        TestableLicenseService s = service("", BEFORE_EXP_MILLIS);
        s.init();
        verify(dao).readOrCreateInstanceId();
    }

    @Test
    public void badSignatureExitsFifteen() {
        codec = new LicenseCodec(fixturePublicKey);
        char[] chars = goldenKey.toCharArray();
        int dot = goldenKey.indexOf('.');
        chars[dot + 3] = chars[dot + 3] == 'A' ? 'B' : 'A';
        TestableLicenseService s = service(new String(chars), BEFORE_EXP_MILLIS);
        s.init();
        assertThat(s.exits).containsExactly(LicenseViolation.BAD_SIGNATURE.getExitCode());
    }

    @Test
    public void malformedKeyExitsFourteen() {
        TestableLicenseService s = service("this-is-not-a-key", BEFORE_EXP_MILLIS);
        s.init();
        assertThat(s.exits).containsExactly(LicenseViolation.MALFORMED.getExitCode());
    }

    @Test
    public void wrongInstanceExitsSixteen() {
        when(dao.readOrCreateInstanceId()).thenReturn(UUID.randomUUID());
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        assertThat(s.exits).containsExactly(LicenseViolation.WRONG_INSTANCE.getExitCode());
    }

    @Test
    public void expiredExitsSeventeen() {
        TestableLicenseService s = service(goldenKey, AFTER_EXP_MILLIS);
        s.init();
        assertThat(s.exits).containsExactly(LicenseViolation.EXPIRED.getExitCode());
    }

    @Test
    public void clockRollbackBeyondToleranceExitsEighteen() {
        when(dao.readHighWaterTs()).thenReturn(BEFORE_EXP_MILLIS);
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS - TOLERANCE_MILLIS - 1);
        s.init();
        assertThat(s.exits).containsExactly(LicenseViolation.CLOCK_ROLLBACK.getExitCode());
    }

    @Test
    public void clockRollbackInsideToleranceIsAccepted() {
        when(dao.readHighWaterTs()).thenReturn(BEFORE_EXP_MILLIS);
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS - TOLERANCE_MILLIS + 1);
        s.init();
        assertThat(s.exits).isEmpty();
    }

    @Test
    public void validLicenceAdvancesTheHighWaterMark() {
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        assertThat(s.exits).isEmpty();
        verify(dao).advanceHighWaterTs(BEFORE_EXP_MILLIS);
    }

    @Test
    public void scheduledCheckReRunsExpiryAndExits() {
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        assertThat(s.exits).isEmpty();
        s.setClock(Clock.fixed(Instant.ofEpochMilli(AFTER_EXP_MILLIS), ZoneOffset.UTC));
        s.scheduledCheck();
        assertThat(s.exits).containsExactly(LicenseViolation.EXPIRED.getExitCode());
    }

    @Test
    public void underCapAllows() {
        when(dao.countEntities(EntityType.DEVICE)).thenReturn(4_999L);
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        s.checkCreateAllowed(TenantId.SYS_TENANT_ID, EntityType.DEVICE);
    }

    @Test
    public void atCapBlocks() {
        when(dao.countEntities(EntityType.DEVICE)).thenReturn(5_000L);
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        assertThatThrownBy(() -> s.checkCreateAllowed(TenantId.SYS_TENANT_ID, EntityType.DEVICE))
                .isInstanceOf(EntitiesLimitExceededException.class)
                .hasFieldOrPropertyWithValue("limit", 5_000L)
                .hasFieldOrPropertyWithValue("entityType", EntityType.DEVICE);
    }

    @Test
    public void overCapBlocks() {
        when(dao.countEntities(EntityType.ASSET)).thenReturn(9_999L);
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        assertThatThrownBy(() -> s.checkCreateAllowed(TenantId.SYS_TENANT_ID, EntityType.ASSET))
                .isInstanceOf(EntitiesLimitExceededException.class);
    }

    @Test
    public void absentCapSkipsTheCountQueryEntirely() {
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        s.checkCreateAllowed(TenantId.SYS_TENANT_ID, EntityType.DASHBOARD);
        verify(dao, never()).countEntities(EntityType.DASHBOARD);
    }

    @Test
    public void getInfoReportsCapsCountsAndExpiry() {
        when(dao.countEntities(EntityType.DEVICE)).thenReturn(3_104L);
        when(dao.countEntities(EntityType.ASSET)).thenReturn(412L);
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        var info = s.getInfo();
        assertThat(info.getMaxDevices()).isEqualTo(5_000L);
        assertThat(info.getDevices()).isEqualTo(3_104L);
        assertThat(info.getMaxAssets()).isEqualTo(2_000L);
        assertThat(info.getAssets()).isEqualTo(412L);
        assertThat(info.getExpiresAt()).isEqualTo(EXP_SECONDS);
        assertThat(info.getDaysRemaining()).isEqualTo(1L);
        assertThat(info.getInstanceId()).isEqualTo(INSTANCE_ID.toString());
    }

    @Test
    public void daysRemainingIsNeverNegative() {
        TestableLicenseService s = service(goldenKey, BEFORE_EXP_MILLIS);
        s.init();
        s.setClock(Clock.fixed(Instant.ofEpochMilli(AFTER_EXP_MILLIS), ZoneOffset.UTC));
        assertThat(s.getInfo().getDaysRemaining()).isZero();
    }

    @Test
    public void installServiceNeverThrowsAndNeverExits() {
        InstallLicenseService install = new InstallLicenseService();
        install.checkCreateAllowed(TenantId.SYS_TENANT_ID, EntityType.DEVICE);
        install.checkCreateAllowed(TenantId.SYS_TENANT_ID, EntityType.ASSET);
        assertThat(install.getInfo()).isNull();
    }
}
