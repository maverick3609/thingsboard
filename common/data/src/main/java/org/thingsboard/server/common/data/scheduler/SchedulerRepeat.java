// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DailyRepeat.class, name = "DAILY"),
        @JsonSubTypes.Type(value = EveryNDaysRepeat.class, name = "EVERY_N_DAYS"),
        @JsonSubTypes.Type(value = WeeklyRepeat.class, name = "WEEKLY"),
        @JsonSubTypes.Type(value = EveryNWeeksRepeat.class, name = "EVERY_N_WEEKS"),
        @JsonSubTypes.Type(value = MonthlyRepeat.class, name = "MONTHLY"),
        @JsonSubTypes.Type(value = YearlyRepeat.class, name = "YEARLY"),
        @JsonSubTypes.Type(value = TimerRepeat.class, name = "TIMER")
})
public interface SchedulerRepeat {

    long getEndsOn();

    SchedulerRepeatType getType();

    long getNext(long startTime, long ts, String timezone);

}
