// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Calendar;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeeklyRepeat implements SchedulerRepeat {

    private long endsOn;
    private List<Integer> repeatOn;

    @Override
    public SchedulerRepeatType getType() {
        return SchedulerRepeatType.WEEKLY;
    }

    @Override
    public long getNext(long startTime, long ts, String timezone) {
        if (this.repeatOn == null || this.repeatOn.isEmpty()) {
            return 0L;
        }
        Calendar calendar = SchedulerUtils.getCalendarWithTimeZone(timezone);
        long tmp = startTime;
        calendar.setTimeInMillis(tmp);
        while (tmp < this.endsOn) {
            if (tmp > ts) {
                int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
                if (this.repeatOn.contains(--dayOfWeek)) {
                    return tmp;
                }
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1);
            tmp = calendar.getTimeInMillis();
        }
        return 0L;
    }

}
