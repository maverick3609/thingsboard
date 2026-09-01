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

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.common.data.EntityType;

import java.util.UUID;

/**
 * Every database read the licence feature needs, over plain {@link JdbcTemplate}. There is no JPA entity or
 * repository pair: this is one single-row table plus two counts, and a JPA layer would be pure ceremony.
 */
@Component
@RequiredArgsConstructor
public class LicenseDao {

    private final JdbcTemplate jdbcTemplate;
    private final TbTransactionalCache<LicenseCountCacheKey, Long> cache;

    /**
     * The identity this install's licence is bound to, minting it on first call.
     * <p>
     * Insert-then-read rather than trusting the insert's return: two cluster nodes starting at once both run
     * the insert, one wins, and both must end up reading the single row that survived.
     */
    public UUID readOrCreateInstanceId() {
        jdbcTemplate.update(
                "INSERT INTO inferrix_license_state (singleton, instance_id, high_water_ts) "
                        + "VALUES (TRUE, ?, 0) ON CONFLICT (singleton) DO NOTHING",
                UUID.randomUUID());
        return jdbcTemplate.queryForObject(
                "SELECT instance_id FROM inferrix_license_state WHERE singleton = TRUE", UUID.class);
    }

    public long readHighWaterTs() {
        return jdbcTemplate.queryForObject(
                "SELECT high_water_ts FROM inferrix_license_state WHERE singleton = TRUE", Long.class);
    }

    /**
     * Moves the mark forward only. GREATEST makes concurrent updates from several nodes safe.
     * <p>
     * Fails loud rather than silently discarding the advance: an {@code UPDATE ... WHERE singleton = TRUE}
     * against a missing row matches zero rows and would otherwise return cleanly, which would defeat the
     * clock-rollback anti-tamper check without a trace.
     */
    public void advanceHighWaterTs(long nowMillis) {
        int updated = jdbcTemplate.update(
                "UPDATE inferrix_license_state SET high_water_ts = GREATEST(high_water_ts, ?) "
                        + "WHERE singleton = TRUE",
                nowMillis);
        if (updated == 0) {
            throw new IllegalStateException(
                    "inferrix_license_state has no row yet -- readOrCreateInstanceId() must run first");
        }
    }

    /**
     * Install-wide count, not per tenant: the licence caps the deployment, not a tenant.
     * <p>
     * Read through a cache evicted by {@link LicenseCountEvictor} on every device/asset create and delete,
     * so the uncached count(*) runs once per mutation rather than once per create. Stock ThingsBoard runs no
     * query at all on this path (DefaultApiLimitService short-circuits when the tenant-profile limit is
     * unset), so an unconditional full-table scan here would be new cost on the hot provisioning path.
     */
    public long countEntities(EntityType entityType) {
        return cache.getAndPutInTransaction(new LicenseCountCacheKey(entityType),
                () -> countEntitiesUncached(entityType), false);
    }

    long countEntitiesUncached(EntityType entityType) {
        String table = switch (entityType) {
            case DEVICE -> "device";
            case ASSET -> "asset";
            default -> throw new IllegalArgumentException(
                    "Licence limits are not defined for entity type " + entityType);
        };
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
