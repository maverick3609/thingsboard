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
package org.thingsboard.server.service.install.lts;

import org.springframework.stereotype.Component;
import org.thingsboard.server.queue.util.TbCoreComponent;

/**
 * Registration-only migration for the licence-control schema. There is no {@link #apply()}: the change is
 * pure DDL in {@code data/upgrade/lts/4.3.1.5/schema_update.sql}, and the single row of
 * {@code inferrix_license_state} is created lazily by {@code LicenseDao}, not by the migration, so that
 * fresh installs and upgrades converge on one code path.
 * <p>
 * {@link LtsMigrationService} discovers migrations from the injected {@code List<LtsMigration>} beans, not
 * from the on-disk directories, so this bean is what makes the runner execute that SQL. A directory with no
 * matching bean is silently skipped; the consistency of the two is guarded by
 * {@code LtsMigrationIntegrationTest}.
 */
@Component
@TbCoreComponent
public class V4_3_1_5Migration implements LtsMigration {

    @Override
    public String getVersion() {
        return "4.3.1.5";
    }
}
