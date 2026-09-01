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
package org.thingsboard.server.common.data.license;

import lombok.Data;

/**
 * What {@code GET /api/license/info} returns. A {@code null} cap means unlimited, which the UI renders as
 * "n / unlimited" with no progress bar.
 */
@Data
public class LicenseInfo {

    private String customer;
    private String instanceId;
    /** Expiry, epoch seconds UTC — the payload's own unit, so a decoded key and this field agree. */
    private long expiresAt;
    private long daysRemaining;
    private long devices;
    private Long maxDevices;
    private long assets;
    private Long maxAssets;
}
