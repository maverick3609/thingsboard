// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Calendar;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EveryNWeeksRepeat implements SchedulerRepeat {

    private long endsOn;
    private int weeks;

    @Override
    public SchedulerRepeatType getType() {
        return SchedulerRepeatType.EVERY_N_WEEKS;
    }

    @Override
    public long getNext(long startTime, long ts, String timezone) {
        if (this.weeks <= 0) {
            return 0L;
        }
        Calendar calendar = SchedulerUtils.getCalendarWithTimeZone(timezone);
        long tmp = startTime;
        int repeatIteration = 0;
        while (tmp < this.endsOn) {
            calendar.setTimeInMillis(startTime);
            calendar.add(Calendar.DAY_OF_YEAR, repeatIteration * this.weeks * 7);
            tmp = calendar.getTimeInMillis();
            if (tmp > ts && tmp < this.endsOn) {
                return tmp;
            }
            ++repeatIteration;
        }
        return 0L;
    }

}
