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
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.BaseDataWithAdditionalInfo;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.ExportableEntity;
import org.thingsboard.server.common.data.HasCustomerId;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.HasVersion;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.validation.Length;
import org.thingsboard.server.common.data.validation.NoXss;

@Slf4j
@Schema
@EqualsAndHashCode(callSuper = true)
public class SchedulerEventInfo extends BaseDataWithAdditionalInfo<SchedulerEventId>
        implements HasName, HasTenantId, HasCustomerId, HasVersion, ExportableEntity<SchedulerEventId> {

    private static final long serialVersionUID = 2807343040519549363L;

    @Schema(description = "JSON object with Tenant Id", accessMode = Schema.AccessMode.READ_ONLY)
    private TenantId tenantId;
    @Schema(description = "JSON object with Customer Id", accessMode = Schema.AccessMode.READ_ONLY)
    private CustomerId customerId;
    @Schema(description = "JSON object with Originator Id", accessMode = Schema.AccessMode.READ_ONLY)
    private EntityId originatorId;
    @NoXss
    @Length(fieldName = "name")
    @Schema(description = "scheduler event name", example = "Weekly Dashboard Report")
    private String name;
    @Schema(description = "scheduler event type", example = "generateReport")
    @NoXss
    @Length(fieldName = "type")
    private String type;
    @Schema(description = "a JSON value with schedule time configuration", implementation = JsonNode.class)
    private JsonNode schedule;
    @Schema(description = "Enable/disable scheduler", example = "true")
    @Length(fieldName = "enabled")
    private boolean enabled = true;
    private SchedulerEventId externalId;
    private Long version;

    public SchedulerEventInfo() {
    }

    public SchedulerEventInfo(SchedulerEventId id) {
        super(id);
    }

    public SchedulerEventInfo(SchedulerEventInfo schedulerEventInfo) {
        super(schedulerEventInfo);
        this.tenantId = schedulerEventInfo.getTenantId();
        this.customerId = schedulerEventInfo.getCustomerId();
        this.originatorId = schedulerEventInfo.getOriginatorId();
        this.name = schedulerEventInfo.getName();
        this.type = schedulerEventInfo.getType();
        this.enabled = schedulerEventInfo.isEnabled();
        this.setSchedule(schedulerEventInfo.getSchedule());
        this.version = schedulerEventInfo.getVersion();
        this.externalId = schedulerEventInfo.getExternalId();
    }

    @Schema(description = "JSON object with the scheduler event Id. Specify this field to update the scheduler event. Referencing non-existing scheduler event Id will cause error. Omit this field to create new scheduler event")
    @Override
    public SchedulerEventId getId() {
        return super.getId();
    }

    @Schema(description = "Timestamp of the scheduler event creation, in milliseconds", example = "1609459200000", accessMode = Schema.AccessMode.READ_ONLY)
    @Override
    public long getCreatedTime() {
        return super.getCreatedTime();
    }

    @Schema(description = "Additional parameters of the scheduler event", implementation = JsonNode.class)
    @Override
    public JsonNode getAdditionalInfo() {
        return super.getAdditionalInfo();
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonIgnore
    public SchedulerEventDescriptor toDescriptor() {
        long startTime = schedule.get("startTime").asLong();
        String timezone = schedule.get("timezone").asText();
        JsonNode repeatNode = schedule.get("repeat");
        SchedulerRepeat repeat = null;
        if (repeatNode != null) {
            try {
                repeat = mapper.treeToValue(repeatNode, SchedulerRepeat.class);
            } catch (Exception e) {
                log.error("Failed to read scheduler config for {}", this, e);
            }
        }
        return new SchedulerEventDescriptor(startTime, timezone, repeat);
    }

    @JsonIgnore
    public EntityType getEntityType() {
        return EntityType.SCHEDULER_EVENT;
    }

    @Override
    public TenantId getTenantId() {
        return tenantId;
    }

    public void setTenantId(TenantId tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public CustomerId getCustomerId() {
        return customerId;
    }

    public void setCustomerId(CustomerId customerId) {
        this.customerId = customerId;
    }

    public EntityId getOriginatorId() {
        return originatorId;
    }

    public void setOriginatorId(EntityId originatorId) {
        this.originatorId = originatorId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonNode getSchedule() {
        return schedule;
    }

    public void setSchedule(JsonNode schedule) {
        this.schedule = schedule;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public SchedulerEventId getExternalId() {
        return externalId;
    }

    @Override
    public void setExternalId(SchedulerEventId externalId) {
        this.externalId = externalId;
    }

    @Override
    public Long getVersion() {
        return version;
    }

    @Override
    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public String toString() {
        return "SchedulerEventInfo(super=" + super.toString() + ", tenantId=" + getTenantId() + ", customerId=" + getCustomerId()
                + ", originatorId=" + getOriginatorId() + ", name=" + getName() + ", type=" + getType() + ", schedule=" + getSchedule()
                + ", enabled=" + isEnabled() + ", externalId=" + getExternalId() + ", version=" + getVersion() + ")";
    }

}
