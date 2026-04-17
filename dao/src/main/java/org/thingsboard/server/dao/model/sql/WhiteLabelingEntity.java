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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DomainId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.WhiteLabeling;
import org.thingsboard.server.common.data.wl.WhiteLabelingType;
import org.thingsboard.server.dao.model.ToData;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

import java.util.UUID;

import static org.thingsboard.server.dao.model.ModelConstants.WHITE_LABELING_DOMAIN_ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.WHITE_LABELING_SETTINGS_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.WHITE_LABELING_TABLE_NAME;
import static org.thingsboard.server.dao.model.ModelConstants.WHITE_LABELING_TYPE_PROPERTY;

@Data
@NoArgsConstructor
@Entity
@Table(name = WHITE_LABELING_TABLE_NAME)
@IdClass(WhiteLabelingCompositeKey.class)
public class WhiteLabelingEntity implements ToData<WhiteLabeling> {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "customer_id")
    private UUID customerId;

    @Id
    @Column(name = WHITE_LABELING_TYPE_PROPERTY)
    @Enumerated(EnumType.STRING)
    private WhiteLabelingType type;

    @Convert(converter = JsonConverter.class)
    @Column(name = WHITE_LABELING_SETTINGS_PROPERTY)
    private JsonNode settings;

    @Column(name = WHITE_LABELING_DOMAIN_ID_PROPERTY)
    private UUID domainId;

    public WhiteLabelingEntity(WhiteLabeling wl) {
        this.tenantId = wl.getTenantId().getId();
        this.customerId = wl.getCustomerId().getId();
        this.type = wl.getType();
        this.settings = wl.getSettings();
        if (wl.getDomainId() != null) {
            this.domainId = wl.getDomainId().getId();
        }
    }

    @Override
    public WhiteLabeling toData() {
        WhiteLabeling wl = new WhiteLabeling();
        wl.setTenantId(TenantId.fromUUID(tenantId));
        wl.setCustomerId(new CustomerId(customerId));
        wl.setType(type);
        wl.setSettings(settings);
        if (domainId != null) {
            wl.setDomainId(new DomainId(domainId));
        }
        return wl;
    }
}
