// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
     * @throws LicenseException thrown when a fatal violation was already detected and the platform is
     *         shutting down.
     */
    void checkCreateAllowed(TenantId tenantId, EntityType entityType);

    /** Current licence state for the UI, or {@code null} when no licence is in force (install profile). */
    LicenseInfo getInfo();
}
