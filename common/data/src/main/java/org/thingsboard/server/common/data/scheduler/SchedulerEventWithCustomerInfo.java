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