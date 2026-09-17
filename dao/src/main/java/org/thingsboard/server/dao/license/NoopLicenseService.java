// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.license;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.license.LicenseInfo;

/**
 * No-op licence service selected only when enforcement is explicitly turned off via
 * {@code license.enforcement.enabled=false} -- the escape hatch every non-install Spring test context needs,
 * since none of them configure a licence key and {@link DefaultLicenseService}'s own boot check would
 * otherwise call {@link System#exit}. Real deployments can set the same flag, which is why this logs loudly:
 * it is a config escape hatch, and a production instance running with it open must say so.
 * <p>
 * Mutually exclusive with {@link DefaultLicenseService} by construction: that bean requires the property
 * true or absent, this one requires it false, and both require {@code !install} -- exactly one of the two,
 * plus {@link InstallLicenseService} under the install profile, is ever selected.
 */
@Service
@Profile("!install")
@ConditionalOnProperty(name = "license.enforcement.enabled", havingValue = "false")
@Slf4j
public class NoopLicenseService implements LicenseService {

    @PostConstruct
    public void init() {
        log.warn("Inferrix licence enforcement is DISABLED (license.enforcement.enabled=false). Entity "
                + "creation is not licence-limited. This must never be set in a production deployment.");
    }

    @Override
    public void checkCreateAllowed(TenantId tenantId, EntityType entityType) {
        // Enforcement explicitly disabled.
    }

    @Override
    public LicenseInfo getInfo() {
        return null;
    }
}
