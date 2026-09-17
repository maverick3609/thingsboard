// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
