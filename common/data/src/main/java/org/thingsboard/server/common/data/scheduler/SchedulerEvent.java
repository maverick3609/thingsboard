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
import org.thingsboard.server.common.data.BaseDataWithAdditionalInfo;
import org.thingsboard.server.common.data.id.SchedulerEventId;

import java.util.Arrays;

@EqualsAndHashCode(callSuper = true)
public class SchedulerEvent extends SchedulerEventInfo {

    private static final long serialVersionUID = 2807343050519549363L;

    @Schema(description = "a JSON value with scheduler event configuration", implementation = JsonNode.class)
    private transient JsonNode configuration;
    @JsonIgnore
    private byte[] configurationBytes;

    public SchedulerEvent() {
    }

    public SchedulerEvent(SchedulerEventId id) {
        super(id);
    }

    public SchedulerEvent(SchedulerEvent schedulerEvent) {
        super(schedulerEvent);
        this.setConfiguration(schedulerEvent.getConfiguration().deepCopy());
    }

    public SchedulerEvent(SchedulerEventInfo schedulerEventInfo, JsonNode configuration) {
        super(schedulerEventInfo);
        this.setConfiguration(configuration.deepCopy());
    }

    public JsonNode getConfiguration() {
        return BaseDataWithAdditionalInfo.getJson(() -> configuration, () -> configurationBytes);
    }

    public void setConfiguration(JsonNode data) {
        BaseDataWithAdditionalInfo.setJson(data, json -> this.configuration = json, bytes -> this.configurationBytes = bytes);
    }

    public byte[] getConfigurationBytes() {
        return configurationBytes;
    }

    @JsonIgnore
    public void setConfigurationBytes(byte[] configurationBytes) {
        this.configurationBytes = configurationBytes;
    }

    @Override
    public String toString() {
        return "SchedulerEvent(super=" + super.toString() + ", configuration=" + getConfiguration()
                + ", configurationBytes=" + Arrays.toString(getConfigurationBytes()) + ")";
    }

}
