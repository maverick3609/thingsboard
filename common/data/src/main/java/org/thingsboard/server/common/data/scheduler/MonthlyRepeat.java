// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Calendar;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonthlyRepeat extends SchedulerDate implements SchedulerRepeat {

    private long endsOn;

    @Override
    public SchedulerRepeatType getType() {
        return SchedulerRepeatType.MONTHLY;
    }

    @Override
    public long getNext(long startTime, long ts, String timezone) {
        return this.getNext(startTime, ts, timezone, this.endsOn, Calendar.MONTH);
    }

}
