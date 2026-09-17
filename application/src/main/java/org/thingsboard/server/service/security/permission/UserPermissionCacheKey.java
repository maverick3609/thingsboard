// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;

import java.io.Serializable;

@Data
@AllArgsConstructor
public class UserPermissionCacheKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private final TenantId tenantId;
    private final UserId userId;

    @Override
    public String toString() {
        return tenantId + "_" + userId;
    }

}
