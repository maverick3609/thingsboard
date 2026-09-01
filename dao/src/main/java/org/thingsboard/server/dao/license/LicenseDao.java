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
        Long value = jdbcTemplate.queryForObject(
                "SELECT high_water_ts FROM inferrix_license_state WHERE singleton = TRUE", Long.class);
        return value == null ? 0L : value;
    }

    /** Moves the mark forward only. GREATEST makes concurrent updates from several nodes safe. */
    public void advanceHighWaterTs(long nowMillis) {
        jdbcTemplate.update(
                "UPDATE inferrix_license_state SET high_water_ts = GREATEST(high_water_ts, ?) "
                        + "WHERE singleton = TRUE",
                nowMillis);
    }

    /**
     * Install-wide count, not per tenant: the licence caps the deployment, not a tenant.
     * <p>
     * ponytail: a full count(*) per create, which is the same cost CE already pays in
     * DataValidator.validateNumberOfEntitiesPerTenant. If bulk provisioning ever shows this in a profile,
     * replace it with a cached counter refreshed on the licence check interval.
     */
    public long countEntities(EntityType entityType) {
        String table = switch (entityType) {
            case DEVICE -> "device";
            case ASSET -> "asset";
            default -> throw new IllegalArgumentException(
                    "Licence limits are not defined for entity type " + entityType);
        };
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
