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
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.service.AbstractServiceTest;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link LicenseDao#countEntities} is actually cached (not just correct), and that
 * {@link LicenseCountEvictor} keeps it honest across real device/asset creates and deletes.
 */
@DaoSqlTest
public class LicenseCountCacheTest extends AbstractServiceTest {

    // Fixtures for the raw-JDBC tests: inserting through JdbcTemplate directly, bypassing DeviceService /
    // AssetService, so no evict event fires -- that staleness is the whole point of those tests.
    private static final UUID DEVICE_PROFILE_ID = UUID.randomUUID();
    private static final UUID ASSET_PROFILE_ID = UUID.randomUUID();
    private static final UUID RAW_TENANT_ID = UUID.randomUUID();

    @Autowired
    private LicenseDao licenseDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private TbTransactionalCache<LicenseCountCacheKey, Long> cache;

    @After
    public void tearDown() {
        jdbcTemplate.update("DELETE FROM device WHERE tenant_id = ?", RAW_TENANT_ID);
        jdbcTemplate.update("DELETE FROM device_profile WHERE id = ?", DEVICE_PROFILE_ID);
        jdbcTemplate.update("DELETE FROM asset WHERE tenant_id = ?", RAW_TENANT_ID);
        jdbcTemplate.update("DELETE FROM asset_profile WHERE id = ?", ASSET_PROFILE_ID);
        // Courtesy for whatever test runs next in this shared Spring context: don't leave a cache entry
        // that is stale relative to the rows this test just deleted.
        cache.evict(new LicenseCountCacheKey(EntityType.DEVICE));
        cache.evict(new LicenseCountCacheKey(EntityType.ASSET));
    }

    @Test
    public void countIsServedFromCacheOnASecondCall() {
        long before = licenseDao.countEntities(EntityType.DEVICE); // populates the cache

        insertDeviceDirectly();

        // Inserted straight through JDBC, so no evict event fired. An uncached countEntities would see the
        // new row immediately and return before + 1; this asserts it doesn't.
        assertThat(licenseDao.countEntities(EntityType.DEVICE)).isEqualTo(before);
    }

    @Test
    public void creatingADeviceEvictsTheCachedCount() {
        long before = licenseDao.countEntities(EntityType.DEVICE); // populates the cache with the old count

        saveDevice("license-count-cache-test-create");

        // The only way this can be before + 1, rather than the stale cached "before", is a real evict
        // between the two reads -- proving the event wiring, not just that a row got inserted.
        assertThat(licenseDao.countEntities(EntityType.DEVICE)).isEqualTo(before + 1);
    }

    @Test
    public void deletingADeviceEvictsTheCachedCount() {
        Device device = saveDevice("license-count-cache-test-delete");
        long before = licenseDao.countEntities(EntityType.DEVICE); // populates the cache with device included

        deviceService.deleteDevice(tenantId, device.getId());

        assertThat(licenseDao.countEntities(EntityType.DEVICE)).isEqualTo(before - 1);
    }

    @Test
    public void creatingAnAssetEvictsTheCachedCount() {
        long before = licenseDao.countEntities(EntityType.ASSET); // populates the cache with the old count

        saveAsset("license-count-cache-test-create");

        // Same proof as creatingADeviceEvictsTheCachedCount, for the asset create path in BaseAssetService.
        assertThat(licenseDao.countEntities(EntityType.ASSET)).isEqualTo(before + 1);
    }

    @Test
    public void deletingAnAssetEvictsTheCachedCount() {
        Asset asset = saveAsset("license-count-cache-test-delete");
        long before = licenseDao.countEntities(EntityType.ASSET); // populates the cache with asset included

        assetService.deleteAsset(tenantId, asset.getId());

        assertThat(licenseDao.countEntities(EntityType.ASSET)).isEqualTo(before - 1);
    }

    @Test
    public void assetsAndDevicesUseSeparateCacheEntries() {
        long assetsBefore = licenseDao.countEntities(EntityType.ASSET); // populates the ASSET entry

        insertAssetDirectly(); // bypasses AssetService: the ASSET entry is now stale on purpose

        long devicesBefore = licenseDao.countEntities(EntityType.DEVICE); // populates the DEVICE entry
        saveDevice("license-count-cache-test-separate"); // evicts DEVICE only

        // DEVICE reflects the create (its entry was evicted). ASSET must still serve its stale cached value
        // -- if the two shared one cache slot, the DEVICE evict would have cleared it too, and this read
        // would miss and return the true, fresher (assetsBefore + 1) count instead.
        assertThat(licenseDao.countEntities(EntityType.DEVICE)).isEqualTo(devicesBefore + 1);
        assertThat(licenseDao.countEntities(EntityType.ASSET)).isEqualTo(assetsBefore);
    }

    @Test
    public void unsupportedEntityTypeIsNeverCachedAndAlwaysThrows() {
        assertThatThrownBy(() -> licenseDao.countEntities(EntityType.DASHBOARD))
                .isInstanceOf(IllegalArgumentException.class);
        // A second call must hit the same uncached throw, not a poisoned cache entry left by the first call.
        assertThatThrownBy(() -> licenseDao.countEntities(EntityType.DASHBOARD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Device saveDevice(String name) {
        Device device = new Device();
        device.setTenantId(tenantId);
        device.setName(name);
        device.setType("default");
        return deviceService.saveDevice(device);
    }

    private Asset saveAsset(String name) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setName(name);
        asset.setType("default");
        return assetService.saveAsset(asset);
    }

    private void insertDeviceDirectly() {
        jdbcTemplate.update("INSERT INTO device_profile (id, created_time) VALUES (?, ?)",
                DEVICE_PROFILE_ID, System.currentTimeMillis());
        jdbcTemplate.update(
                "INSERT INTO device (id, created_time, device_profile_id, tenant_id, name) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), System.currentTimeMillis(), DEVICE_PROFILE_ID, RAW_TENANT_ID,
                "license-count-cache-test-raw-device");
    }

    private void insertAssetDirectly() {
        jdbcTemplate.update("INSERT INTO asset_profile (id, created_time) VALUES (?, ?)",
                ASSET_PROFILE_ID, System.currentTimeMillis());
        jdbcTemplate.update(
                "INSERT INTO asset (id, created_time, asset_profile_id, tenant_id, name) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), System.currentTimeMillis(), ASSET_PROFILE_ID, RAW_TENANT_ID,
                "license-count-cache-test-raw-asset");
    }
}
