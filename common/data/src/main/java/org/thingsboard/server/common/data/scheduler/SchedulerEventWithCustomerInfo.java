// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.id.SchedulerEventId;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
public class SchedulerEventWithCustomerInfo extends SchedulerEventInfo {

    @Schema(description = "Title of the customer", example = "Company A")
    private String customerTitle;
    @Schema(description = "Parameter that specifies if customer is public", accessMode = Schema.AccessMode.READ_ONLY, type = "boolean")
    private boolean customerIsPublic;
    private List<Long> timestamps;

    public SchedulerEventWithCustomerInfo() {
    }

    public SchedulerEventWithCustomerInfo(SchedulerEventId schedulerEventId) {
        super(schedulerEventId);
    }

    public SchedulerEventWithCustomerInfo(SchedulerEventInfo schedulerEventInfo, String customerTitle, boolean customerIsPublic) {
        super(schedulerEventInfo);
        this.customerTitle = customerTitle;
        this.customerIsPublic = customerIsPublic;
    }

    public String getCustomerTitle() {
        return customerTitle;
    }

    public void setCustomerTitle(String customerTitle) {
        this.customerTitle = customerTitle;
    }

    public boolean isCustomerIsPublic() {
        return customerIsPublic;
    }

    public void setCustomerIsPublic(boolean customerIsPublic) {
        this.customerIsPublic = customerIsPublic;
    }

    public List<Long> getTimestamps() {
        return timestamps;
    }

    public void setTimestamps(List<Long> timestamps) {
        this.timestamps = timestamps;
    }

    @Override
    public String toString() {
        return "SchedulerEventWithCustomerInfo(super=" + super.toString() + ", customerTitle=" + getCustomerTitle()
                + ", customerIsPublic=" + isCustomerIsPublic() + ", timestamps=" + getTimestamps() + ")";
    }

}
