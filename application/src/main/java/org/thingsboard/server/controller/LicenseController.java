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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.license.LicenseInfo;
import org.thingsboard.server.dao.license.LicenseService;
import org.thingsboard.server.queue.util.TbCoreComponent;

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
     * Returns {@code null} when no licence is in force, which the UI treats as "hide the card". Restricted to
     * platform operators: a customer user has no business seeing install-wide capacity or the instance ID.
     */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @GetMapping(value = "/license/info", produces = "application/json")
    public LicenseInfo getLicenseInfo() {
        return licenseService.getInfo();
    }
}
