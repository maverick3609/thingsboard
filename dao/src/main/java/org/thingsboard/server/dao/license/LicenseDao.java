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
import org.springframework.dao.EmptyResultDataAccessException;
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
        return jdbcTemplate.queryForObject(
                "SELECT high_water_ts FROM inferrix_license_state WHERE singleton = TRUE", Long.class);
    }

    /**
     * Moves the mark forward only, and by at most {@code maxAdvanceMs} per call.
     * <p>
     * The clamp bounds the size of a single advance, not how many times it can fire. A transient bad
     * reading -- a one-off NTP jump that self-corrects, or a node that simply stops checking in -- costs
     * every other node at most one clamp period of lockout, and the install then heals itself once real
     * time catches up to the mark. A persistent skew -- a dead RTC battery, a wrong timezone -- instead
     * walks the mark forward by {@code maxAdvanceMs} on every check until it reaches the bad clock's own
     * reading, then tracks it exactly; from that point every correctly-clocked node stays locked out for
     * as long as the offending node keeps running and checking in, not just for {@code maxAdvanceMs}. The
     * actual remedy there is fixing or stopping that node's clock, not waiting. A mark that lags real time
     * is harmless; it only weakens rollback detection by the lag.
     * <p>
     * The {@code high_water_ts = 0} branch is the fresh-install bootstrap: clamping against zero would pin
     * the mark near the epoch. There is no trusted reference to check the first value against, so it is
     * taken as given.
     * <p>
     * Fails loud rather than silently discarding the advance: an {@code UPDATE ... WHERE singleton = TRUE}
     * against a missing row matches zero rows; {@code queryForObject} turns that into an
     * {@link EmptyResultDataAccessException}, which this method re-throws as an {@link IllegalStateException}
     * naming the real cause, rather than letting a discarded advance defeat the clock-rollback anti-tamper
     * check without a trace.
     *
     * @return the mark's value after this call; less than {@code nowMillis} means this call was clamped.
     */
    public long advanceHighWaterTs(long nowMillis, long maxAdvanceMs) {
        try {
            return jdbcTemplate.queryForObject(
                    "UPDATE inferrix_license_state SET high_water_ts = CASE "
                            + "WHEN high_water_ts = 0 THEN ? "
                            + "ELSE GREATEST(high_water_ts, LEAST(?, high_water_ts + ?)) END "
                            + "WHERE singleton = TRUE "
                            + "RETURNING high_water_ts",
                    Long.class, nowMillis, nowMillis, maxAdvanceMs);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "inferrix_license_state has no row yet -- readOrCreateInstanceId() must run first");
        }
    }

    /**
     * Install-wide count, not per tenant: the licence caps the deployment, not a tenant.
     * <p>
     * ponytail: a full count(*) per create, which is the same cost CE already pays in
     * DataValidator.validateNumberOfEntitiesPerTenant. If bulk provisioning ever shows this in a profile,
     * the fix is a counter the create/delete path increments and decrements -- NOT a read-through cache
     * evicted on save. That was tried and reverted: a create reads the count and then invalidates it at
     * AFTER_COMMIT, so every create still pays the query and the cache only ever helps read-only callers.
     */
    public long countEntities(EntityType entityType) {
        String table = switch (entityType) {
            case DEVICE -> "device";
            case ASSET -> "asset";
            default -> throw new IllegalArgumentException(
                    "Licence limits are not defined for entity type " + entityType);
        };
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
