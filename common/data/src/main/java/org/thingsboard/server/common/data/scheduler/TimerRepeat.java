// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TimerRepeat implements SchedulerRepeat {

    private long repeatInterval;
    private TimeUnit timeUnit;
    private long endsOn;

    @Override
    public SchedulerRepeatType getType() {
        return SchedulerRepeatType.TIMER;
    }

    @Override
    public long getNext(long startTime, long ts, String timezone) {
        long interval = this.timeUnit.toMillis(this.repeatInterval);
        if (interval <= 0) {
            return 0L;
        }
        for (long tmp = startTime; tmp < this.endsOn; tmp += interval) {
            if (tmp <= ts) {
                continue;
            }
            return tmp;
        }
        return 0L;
    }

}
