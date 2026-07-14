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
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

import static org.thingsboard.server.dao.model.ModelConstants.SCHEDULER_EVENT_TABLE_NAME;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = SCHEDULER_EVENT_TABLE_NAME)
public final class SchedulerEventEntity extends AbstractSchedulerEventInfoEntity<SchedulerEvent> {

    @Convert(converter = JsonConverter.class)
    @Column(name = ModelConstants.SCHEDULER_EVENT_CONFIGURATION_PROPERTY)
    private JsonNode configuration;

    public SchedulerEventEntity() {
        super();
    }

    public SchedulerEventEntity(SchedulerEvent schedulerEvent) {
        super(schedulerEvent);
        this.configuration = schedulerEvent.getConfiguration();
    }

    public SchedulerEvent toData() {
        return new SchedulerEvent(super.toSchedulerEventInfo(), configuration);
    }

}
