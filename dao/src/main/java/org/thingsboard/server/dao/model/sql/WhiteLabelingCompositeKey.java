// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.model.sql;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.WhiteLabelingType;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WhiteLabelingCompositeKey implements Serializable {
    private UUID tenantId;
    private UUID customerId;
    private WhiteLabelingType type;

    public static WhiteLabelingCompositeKey forSystem(WhiteLabelingType type) {
        return new WhiteLabelingCompositeKey(
                TenantId.SYS_TENANT_ID.getId(),
                EntityId.NULL_UUID,
                type
        );
    }

    public static WhiteLabelingCompositeKey forTenant(TenantId tenantId, WhiteLabelingType type) {
        return new WhiteLabelingCompositeKey(
                tenantId.getId(),
                EntityId.NULL_UUID,
                type
        );
    }

    public static WhiteLabelingCompositeKey forCustomer(TenantId tenantId, CustomerId customerId, WhiteLabelingType type) {
        return new WhiteLabelingCompositeKey(
                tenantId.getId(),
                customerId.getId(),
                type
        );
    }
}
