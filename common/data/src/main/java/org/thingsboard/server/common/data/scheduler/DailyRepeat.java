// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Calendar;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyRepeat extends SchedulerDate implements SchedulerRepeat {

    public static final long _1DAY = 86400000L;

    private long endsOn;

    @Override
    public SchedulerRepeatType getType() {
        return SchedulerRepeatType.DAILY;
    }

    @Override
    public long getNext(long startTime, long ts, String timezone) {
        return this.getNext(startTime, ts, timezone, this.endsOn, Calendar.DAY_OF_YEAR);
    }

}
