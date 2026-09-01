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
