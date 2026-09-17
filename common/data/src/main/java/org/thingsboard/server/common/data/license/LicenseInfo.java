// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
