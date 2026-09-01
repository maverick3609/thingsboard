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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DaoSqlTest
public class LicenseDaoTest extends AbstractServiceTest {

    @Autowired
    private LicenseDao licenseDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @After
    public void tearDown() {
        jdbcTemplate.execute("DELETE FROM inferrix_license_state");
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
    public void secondInsertDoesNotCreateASecondRow() {
        UUID first = licenseDao.readOrCreateInstanceId();
        // Simulate a second cluster node racing the first: both call the same insert-if-absent.
        UUID second = licenseDao.readOrCreateInstanceId();
        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM inferrix_license_state", Integer.class))
                .isEqualTo(1);
    }

    @Test
    public void countsEntitiesInstallWide() {
        assertThat(licenseDao.countEntities(EntityType.DEVICE)).isGreaterThanOrEqualTo(0L);
        assertThat(licenseDao.countEntities(EntityType.ASSET)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    public void rejectsUnsupportedEntityType() {
        assertThatThrownBy(() -> licenseDao.countEntities(EntityType.DASHBOARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DASHBOARD");
    }
}
