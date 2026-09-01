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

import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.dao.service.AbstractServiceTest;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DaoSqlTest
public class LicenseDaoTest extends AbstractServiceTest {

    // Fixtures for countsEntitiesInstallWide: two distinct tenants, so the count can only pass if it is
    // genuinely install-wide rather than accidentally scoped to one tenant.
    private static final UUID DEVICE_PROFILE_ID = UUID.randomUUID();
    private static final UUID ASSET_PROFILE_ID = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    @Autowired
    private LicenseDao licenseDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @After
    public void tearDown() {
        jdbcTemplate.execute("DELETE FROM inferrix_license_state");
        jdbcTemplate.update("DELETE FROM device WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        jdbcTemplate.update("DELETE FROM device_profile WHERE id = ?", DEVICE_PROFILE_ID);
        jdbcTemplate.update("DELETE FROM asset WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        jdbcTemplate.update("DELETE FROM asset_profile WHERE id = ?", ASSET_PROFILE_ID);
    }

    @Test
    public void mintsAnInstanceIdOnFirstReadAndKeepsIt() {
        UUID first = licenseDao.readOrCreateInstanceId();
        assertThat(first).isNotNull();
        assertThat(licenseDao.readOrCreateInstanceId()).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inferrix_license_state", Integer.class))
                .isEqualTo(1);
    }

    @Test
    public void highWaterStartsAtZeroAndOnlyMovesForward() {
        licenseDao.readOrCreateInstanceId();
        assertThat(licenseDao.readHighWaterTs()).isZero();

        licenseDao.advanceHighWaterTs(5_000L);
        assertThat(licenseDao.readHighWaterTs()).isEqualTo(5_000L);

        licenseDao.advanceHighWaterTs(9_000L);
        assertThat(licenseDao.readHighWaterTs()).isEqualTo(9_000L);

        // A backwards value must not lower the mark — that is the whole point of the guard.
        licenseDao.advanceHighWaterTs(1_000L);
        assertThat(licenseDao.readHighWaterTs()).isEqualTo(9_000L);
    }

    @Test
    public void advanceHighWaterTsFailsLoudWhenNoRowExistsYet() {
        // No readOrCreateInstanceId() call first -- the table is empty. Without the row-count guard this
        // would silently update zero rows and swallow the advance instead of raising.
        assertThatThrownBy(() -> licenseDao.advanceHighWaterTs(1_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOrCreateInstanceId");
    }

    @Test
    public void readOrCreateInstanceIdIsIdempotentOnSequentialCalls() {
        // Two calls, one thread, no race -- proves idempotency, not concurrency safety.
        // See concurrentReadOrCreateInstanceIdCallsConvergeOnOneRow for the actual concurrent case.
        UUID first = licenseDao.readOrCreateInstanceId();
        UUID second = licenseDao.readOrCreateInstanceId();
        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inferrix_license_state", Integer.class))
                .isEqualTo(1);
    }

    @Test
    public void concurrentReadOrCreateInstanceIdCallsConvergeOnOneRow() throws Exception {
        // Two real threads, released together, each on their own DB connection -- this is the actual
        // two-cluster-nodes-racing-at-startup scenario readOrCreateInstanceId is built to survive.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<UUID> raceToInsert = () -> {
                bothReady.countDown();
                go.await();
                return licenseDao.readOrCreateInstanceId();
            };

            Future<UUID> first = executor.submit(raceToInsert);
            Future<UUID> second = executor.submit(raceToInsert);
            bothReady.await(10, TimeUnit.SECONDS);
            go.countDown();

            UUID firstId = first.get(10, TimeUnit.SECONDS);
            UUID secondId = second.get(10, TimeUnit.SECONDS);

            assertThat(secondId).isEqualTo(firstId);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inferrix_license_state", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void countsEntitiesInstallWide() {
        long devicesBefore = licenseDao.countEntities(EntityType.DEVICE);
        long assetsBefore = licenseDao.countEntities(EntityType.ASSET);

        jdbcTemplate.update("INSERT INTO device_profile (id, created_time) VALUES (?, ?)",
                DEVICE_PROFILE_ID, System.currentTimeMillis());
        insertDevice(TENANT_A, "license-dao-test-device-a");
        insertDevice(TENANT_B, "license-dao-test-device-b");

        jdbcTemplate.update("INSERT INTO asset_profile (id, created_time) VALUES (?, ?)",
                ASSET_PROFILE_ID, System.currentTimeMillis());
        insertAsset(TENANT_A, "license-dao-test-asset-a");
        insertAsset(TENANT_B, "license-dao-test-asset-b");

        // Two different tenant_id values: this only passes if the count is install-wide, not tenant-scoped.
        assertThat(licenseDao.countEntities(EntityType.DEVICE)).isEqualTo(devicesBefore + 2);
        assertThat(licenseDao.countEntities(EntityType.ASSET)).isEqualTo(assetsBefore + 2);
    }

    private void insertDevice(UUID tenantId, String name) {
        jdbcTemplate.update(
                "INSERT INTO device (id, created_time, device_profile_id, tenant_id, name) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), System.currentTimeMillis(), DEVICE_PROFILE_ID, tenantId, name);
    }

    private void insertAsset(UUID tenantId, String name) {
        jdbcTemplate.update(
                "INSERT INTO asset (id, created_time, asset_profile_id, tenant_id, name) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), System.currentTimeMillis(), ASSET_PROFILE_ID, tenantId, name);
    }

    @Test
    public void rejectsUnsupportedEntityType() {
        assertThatThrownBy(() -> licenseDao.countEntities(EntityType.DASHBOARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DASHBOARD");
    }
}
