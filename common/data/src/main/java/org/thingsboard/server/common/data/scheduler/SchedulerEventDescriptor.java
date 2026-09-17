// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

public record SchedulerEventDescriptor(long startTime, String timezone, SchedulerRepeat repeat) {

    public boolean passedAway(long ts) {
        return this.repeat == null ? this.startTime < ts : this.repeat.getEndsOn() < ts;
    }

    public long getNextEventTime(long ts) {
        if (this.repeat != null && this.repeat.getEndsOn() > ts) {
            return this.repeat.getNext(this.startTime, ts, this.timezone);
        }
        if (ts < this.startTime) {
            return this.startTime;
        }
        return 0L;
    }

}
