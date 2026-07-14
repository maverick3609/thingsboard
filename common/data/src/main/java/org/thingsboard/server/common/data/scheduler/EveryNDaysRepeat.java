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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Calendar;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EveryNDaysRepeat implements SchedulerRepeat {

    private long endsOn;
    private int days;

    @Override
    public SchedulerRepeatType getType() {
        return SchedulerRepeatType.EVERY_N_DAYS;
    }

    @Override
    public long getNext(long startTime, long ts, String timezone) {
        if (this.days <= 0) {
            return 0L;
        }
        Calendar calendar = SchedulerUtils.getCalendarWithTimeZone(timezone);
        long tmp = startTime;
        int repeatIteration = 0;
        while (tmp < this.endsOn) {
            calendar.setTimeInMillis(startTime);
            calendar.add(Calendar.DAY_OF_YEAR, repeatIteration * this.days);
            tmp = calendar.getTimeInMillis();
            if (tmp > ts && tmp < this.endsOn) {
                return tmp;
            }
            ++repeatIteration;
        }
        return 0L;
    }

}
