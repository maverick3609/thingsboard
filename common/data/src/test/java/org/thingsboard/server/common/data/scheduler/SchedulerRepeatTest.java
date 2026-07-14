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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerRepeatTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static long at(ZoneId zone, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli();
    }

    // ---------- descriptor ----------

    @Test
    void oneShotDescriptor_firesAtStartTime_thenPassesAway() {
        long start = at(UTC, 2026, 8, 1, 10, 0);
        SchedulerEventDescriptor d = new SchedulerEventDescriptor(start, "UTC", null);
        assertThat(d.getNextEventTime(start - 1000)).isEqualTo(start);
        assertThat(d.getNextEventTime(start + 1000)).isZero();
        assertThat(d.passedAway(start - 1)).isFalse();
        assertThat(d.passedAway(start + 1)).isTrue();
    }

    @Test
    void repeatingDescriptor_passedAway_isDrivenByEndsOn() {
        long start = at(UTC, 2026, 8, 1, 10, 0);
        TimerRepeat repeat = new TimerRepeat();
        repeat.setRepeatInterval(2);
        repeat.setTimeUnit(TimeUnit.HOURS);
        repeat.setEndsOn(at(UTC, 2026, 8, 2, 10, 0));
        SchedulerEventDescriptor d = new SchedulerEventDescriptor(start, "UTC", repeat);
        assertThat(d.passedAway(at(UTC, 2026, 8, 2, 9, 0))).isFalse();
        assertThat(d.passedAway(at(UTC, 2026, 8, 2, 11, 0))).isTrue();
        assertThat(d.getNextEventTime(at(UTC, 2026, 8, 2, 11, 0))).isZero();
    }

    // ---------- TIMER ----------

    @Test
    void timerRepeat_stepsArithmetically_strictBounds() {
        long start = at(UTC, 2026, 8, 1, 10, 0);
        TimerRepeat r = new TimerRepeat();
        r.setRepeatInterval(30);
        r.setTimeUnit(TimeUnit.MINUTES);
        r.setEndsOn(start + TimeUnit.HOURS.toMillis(2));
        // strictly after ts: at exactly an occurrence, next is the following one
        assertThat(r.getNext(start, start, "UTC")).isEqualTo(start + TimeUnit.MINUTES.toMillis(30));
        assertThat(r.getNext(start, start - 1, "UTC")).isEqualTo(start);
        // last occurrence strictly before endsOn: start+90min is last (start+120 == endsOn excluded)
        assertThat(r.getNext(start, start + TimeUnit.MINUTES.toMillis(91), "UTC")).isZero();
    }

    // ---------- DAILY across DST ----------

    @Test
    void dailyRepeat_keepsWallClockAcrossDstSpringForward() {
        // Europe/Berlin DST starts 2026-03-29 02:00 -> 03:00
        long start = at(BERLIN, 2026, 3, 28, 8, 0);
        DailyRepeat r = new DailyRepeat();
        r.setEndsOn(at(BERLIN, 2026, 4, 2, 0, 0));
        long next = r.getNext(start, start, "Europe/Berlin");
        assertThat(next).isEqualTo(at(BERLIN, 2026, 3, 29, 8, 0)); // still 08:00 wall clock (23h day)
        long next2 = r.getNext(start, next, "Europe/Berlin");
        assertThat(next2).isEqualTo(at(BERLIN, 2026, 3, 30, 8, 0));
    }

    // ---------- WEEKLY ----------

    @Test
    void weeklyRepeat_honorsDayMask_zeroIsSunday() {
        // 2026-08-03 is a Monday
        long start = at(UTC, 2026, 8, 3, 9, 0);
        WeeklyRepeat r = new WeeklyRepeat();
        r.setRepeatOn(List.of(1, 5)); // Monday=1, Friday=5
        r.setEndsOn(at(UTC, 2026, 8, 31, 0, 0));
        // start itself matches (Monday) when ts is before it
        assertThat(r.getNext(start, start - 1, "UTC")).isEqualTo(start);
        // after Monday -> Friday 2026-08-07 09:00
        assertThat(r.getNext(start, start, "UTC")).isEqualTo(at(UTC, 2026, 8, 7, 9, 0));
        // after Friday -> next Monday 2026-08-10
        assertThat(r.getNext(start, at(UTC, 2026, 8, 7, 9, 0), "UTC")).isEqualTo(at(UTC, 2026, 8, 10, 9, 0));
    }

    // ---------- EVERY_N_DAYS / EVERY_N_WEEKS ----------

    @Test
    void everyNDaysRepeat_strides() {
        long start = at(UTC, 2026, 8, 1, 6, 0);
        EveryNDaysRepeat r = new EveryNDaysRepeat();
        r.setDays(3);
        r.setEndsOn(at(UTC, 2026, 8, 20, 0, 0));
        assertThat(r.getNext(start, start, "UTC")).isEqualTo(at(UTC, 2026, 8, 4, 6, 0));
        assertThat(r.getNext(start, at(UTC, 2026, 8, 4, 6, 0), "UTC")).isEqualTo(at(UTC, 2026, 8, 7, 6, 0));
    }

    @Test
    void everyNWeeksRepeat_strides() {
        long start = at(UTC, 2026, 8, 3, 9, 0);
        EveryNWeeksRepeat r = new EveryNWeeksRepeat();
        r.setWeeks(2);
        r.setEndsOn(at(UTC, 2026, 10, 1, 0, 0));
        assertThat(r.getNext(start, start, "UTC")).isEqualTo(at(UTC, 2026, 8, 17, 9, 0));
    }

    // ---------- MONTHLY month-end clamping ----------

    @Test
    void monthlyRepeat_clampsFromOriginalStartDate() {
        long start = at(UTC, 2026, 1, 31, 12, 0);
        MonthlyRepeat r = new MonthlyRepeat();
        r.setEndsOn(at(UTC, 2026, 6, 1, 0, 0));
        long feb = r.getNext(start, start, "UTC");
        assertThat(feb).isEqualTo(at(UTC, 2026, 2, 28, 12, 0));   // clamped
        long mar = r.getNext(start, feb, "UTC");
        assertThat(mar).isEqualTo(at(UTC, 2026, 3, 31, 12, 0));   // back to 31st (computed from original start)
        long apr = r.getNext(start, mar, "UTC");
        assertThat(apr).isEqualTo(at(UTC, 2026, 4, 30, 12, 0));
    }

    // ---------- YEARLY ----------

    @Test
    void yearlyRepeat_steps() {
        long start = at(UTC, 2026, 5, 15, 0, 0);
        YearlyRepeat r = new YearlyRepeat();
        r.setEndsOn(at(UTC, 2029, 1, 1, 0, 0));
        assertThat(r.getNext(start, start, "UTC")).isEqualTo(at(UTC, 2027, 5, 15, 0, 0));
        assertThat(r.getNext(start, at(UTC, 2028, 5, 15, 0, 0), "UTC")).isZero(); // 2029-05-15 >= endsOn
    }

    // ---------- repeat-stride DoS guards ----------

    @Test
    void nonPositiveStrides_returnZero_insteadOfHanging() {
        long start = at(UTC, 2026, 8, 1, 10, 0);
        long endsOn = at(UTC, 2026, 8, 20, 0, 0);
        TimerRepeat timer = new TimerRepeat();
        timer.setRepeatInterval(0);
        timer.setTimeUnit(TimeUnit.MINUTES);
        timer.setEndsOn(endsOn);
        assertThat(timer.getNext(start, start, "UTC")).isZero();

        EveryNDaysRepeat days = new EveryNDaysRepeat();
        days.setDays(0);
        days.setEndsOn(endsOn);
        assertThat(days.getNext(start, start, "UTC")).isZero();

        EveryNWeeksRepeat weeks = new EveryNWeeksRepeat();
        weeks.setWeeks(-1);
        weeks.setEndsOn(endsOn);
        assertThat(weeks.getNext(start, start, "UTC")).isZero();
    }

    @Test
    void weeklyRepeat_nullOrEmptyDayMask_returnsZero() {
        long start = at(UTC, 2026, 8, 3, 9, 0);
        WeeklyRepeat r = new WeeklyRepeat();
        r.setEndsOn(at(UTC, 2026, 8, 31, 0, 0));
        r.setRepeatOn(null);
        assertThat(r.getNext(start, start - 1, "UTC")).isZero();
        r.setRepeatOn(List.of());
        assertThat(r.getNext(start, start - 1, "UTC")).isZero();
    }

    // ---------- JSON round-trip (polymorphic) ----------
    // JacksonUtil lives in common/util, which is not a common/data dependency;
    // use a plain ObjectMapper here per the task brief's documented fallback.

    @Test
    void repeatJson_roundTrips_polymorphically() throws Exception {
        String json = "{\"type\":\"WEEKLY\",\"endsOn\":1760000000000,\"repeatOn\":[1,3,5]}";
        SchedulerRepeat repeat = new ObjectMapper().readValue(json, SchedulerRepeat.class);
        assertThat(repeat).isInstanceOf(WeeklyRepeat.class);
        assertThat(repeat.getType()).isEqualTo(SchedulerRepeatType.WEEKLY);
        assertThat(((WeeklyRepeat) repeat).getRepeatOn()).containsExactly(1, 3, 5);
        assertThat(repeat.getEndsOn()).isEqualTo(1760000000000L);
    }

    @Test
    void timerJson_parsesTimeUnit() throws Exception {
        String json = "{\"type\":\"TIMER\",\"endsOn\":1760000000000,\"repeatInterval\":5,\"timeUnit\":\"MINUTES\"}";
        SchedulerRepeat repeat = new ObjectMapper().readValue(json, SchedulerRepeat.class);
        assertThat(repeat).isInstanceOf(TimerRepeat.class);
        assertThat(((TimerRepeat) repeat).getTimeUnit()).isEqualTo(TimeUnit.MINUTES);
    }
}
