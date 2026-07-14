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
