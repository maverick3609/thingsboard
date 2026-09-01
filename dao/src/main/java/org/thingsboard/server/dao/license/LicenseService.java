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

import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.license.LicenseInfo;

/** Licence enforcement surface used by the entity validators and the licence endpoint. */
public interface LicenseService {

    /**
     * @throws org.thingsboard.server.exception.EntitiesLimitExceededException when the install-wide count for
     *         this entity type has reached the licensed cap. Reusing that exception means the existing 403
     *         handler and the existing Angular limit dialog work with no change.
     */
    void checkCreateAllowed(TenantId tenantId, EntityType entityType);

    /** Current licence state for the UI, or {@code null} when no licence is in force (install profile). */
    LicenseInfo getInfo();
}
