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
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.license.LicenseInfo;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.license.LicenseService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.model.SecurityUser;

/** Read-only view of the current licence for the home-page card. */
@RestController
@TbCoreComponent
@RequestMapping("/api")
@Tag(name = "License", description = "Inferrix licence information")
@RequiredArgsConstructor
@Slf4j
public class LicenseController {

    private final LicenseService licenseService;

    /**
     * Returns {@code null} when no licence is in force, which the UI treats as "hide the card".
     * <p>
     * A TENANT_ADMIN gets the capacity numbers -- those are what the cap enforces, so seeing capacity
     * pressure is the point of the card -- but not the licensing customer name or the instance ID. On a
     * shared multi-tenant install those identify the paying customer and the deployment itself to every
     * tenant, which is a cross-tenant leak. SYS_ADMIN gets the full record.
     */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @GetMapping(value = "/license/info", produces = "application/json")
    public LicenseInfo getLicenseInfo() {
        LicenseInfo info = licenseService.getInfo();
        if (info != null && Authority.SYS_ADMIN != currentAuthority()) {
            info.setCustomer(null);
            info.setInstanceId(null);
        }
        return info;
    }

    /**
     * {@code null} means the authority could not be determined, which {@link #getLicenseInfo()} treats as
     * not-SYS_ADMIN -- fail closed. {@code @PreAuthorize} above already guarantees a {@link SecurityUser}
     * principal, so that branch is unreachable in practice; it exists so a future change that breaks that
     * guarantee withholds data instead of leaking it.
     */
    private static Authority currentAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser user) {
            return user.getAuthority();
        }
        return null;
    }
}
