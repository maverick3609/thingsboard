/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
