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
