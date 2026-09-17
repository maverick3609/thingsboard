// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.license;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.license.LicenseInfo;

/**
 * No-op licence service for the {@code install} profile, so install and upgrade run unlicensed. Without it a
 * customer could never build a database to read their instance ID out of, and the validators (which live in
 * {@code dao}, scanned by the install application too) would have no bean to inject.
 */
@Service
@Profile("install")
public class InstallLicenseService implements LicenseService {

    @Override
    public void checkCreateAllowed(TenantId tenantId, EntityType entityType) {
        // Install and upgrade are deliberately unlicensed.
    }

    @Override
    public LicenseInfo getInfo() {
        return null;
    }
}
