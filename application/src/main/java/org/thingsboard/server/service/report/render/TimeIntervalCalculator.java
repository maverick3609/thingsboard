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
package org.thingsboard.server.service.report.render;

import org.thingsboard.server.common.data.report.configuration.timewindow.FixedTimeWindow;
import org.thingsboard.server.common.data.report.configuration.timewindow.History;
import org.thingsboard.server.common.data.report.configuration.timewindow.QuickTimeInterval;
import org.thingsboard.server.common.data.report.configuration.timewindow.TimeWindowConfiguration;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Resolves a report time-window ({@link TimeWindowConfiguration}) to a concrete {@code [startTs, endTs]}
 * epoch-millis range in a target timezone — the range the report's timeseries/alarm queries read over.
 * Handles the four PE history types (0 realtime last-N-ms, 1 fixed absolute, 2 quick interval, 3 "none"
 * → {@code [0,0]}) and every {@link QuickTimeInterval}.
 * <p>
 * Ported verbatim from PE {@code org.thingsboard.server.common.data.report.configuration.timewindow.
 * TimeIntervalCalculator} (decompiled from {@code report-4.2.0PE.jar}). <b>CE deviation:</b> PE ships this in
 * the {@code common/data} timewindow package; Inferrix ports it into {@code application/.../service/report/
 * render} instead — it only consumes R2a-present {@code common/data} timewindow POJOs, and keeping it in
 * {@code application} preserves the R2b invariant that the sole out-of-{@code application} change is the
 * {@code ScriptType.REPORT_DATA_KEY_SCRIPT} enum constant.
 */
public final class TimeIntervalCalculator {

    private TimeIntervalCalculator() {
    }

    public static TimeRange getTimeRange(TimeWindowConfiguration timeWindowConf, String timezone) {
        History historyConf = timeWindowConf.getHistory();
        return switch (historyConf.getHistoryType()) {
            case 0 -> {
                ZoneId zoneId = timezone != null ? ZoneId.of(timezone) : ZoneId.systemDefault();
                long currentTimeMillis = ZonedDateTime.now(zoneId).toInstant().toEpochMilli();
                yield new TimeRange(currentTimeMillis - historyConf.getTimewindowMs(), currentTimeMillis);
            }
            case 1 -> {
                FixedTimeWindow fixedTimeWindow = historyConf.getFixedTimewindow();
                yield new TimeRange(fixedTimeWindow.getStartTimeMs(), fixedTimeWindow.getEndTimeMs());
            }
            case 2 -> getQuickTimeRange(historyConf.getQuickInterval(), timezone);
            case 3 -> new TimeRange(0L, 0L);
            default -> throw new IllegalArgumentException("Unknown history type: " + historyConf.getHistoryType());
        };
    }

    /**
     * PE-faithful: each arm sets {@code start} as a side effect and yields the {@code end} instant, so the
     * switch produces {@code end} while {@code start} is definitely assigned by the time it is read below.
     */
    private static TimeRange getQuickTimeRange(QuickTimeInterval interval, String timezone) {
        ZoneId zoneId = timezone != null ? ZoneId.of(timezone) : ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime start;
        ZonedDateTime end = switch (interval) {
            case YESTERDAY -> {
                start = now.minusDays(1L).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(1L).minusNanos(1L);
            }
            case DAY_BEFORE_YESTERDAY -> {
                start = now.minusDays(2L).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(1L).minusNanos(1L);
            }
            case THIS_DAY_LAST_WEEK -> {
                start = now.minusWeeks(1L).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(1L).minusNanos(1L);
            }
            case PREVIOUS_WEEK -> {
                start = now.minusWeeks(1L).with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(7L).minusNanos(1L);
            }
            case PREVIOUS_WEEK_ISO -> {
                start = now.minusWeeks(1L).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(7L).minusNanos(1L);
            }
            case PREVIOUS_MONTH -> {
                start = now.minusMonths(1L).withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId);
                yield start.plusMonths(1L).minusNanos(1L);
            }
            case PREVIOUS_QUARTER -> {
                int currentQuarter = (now.getMonthValue() - 1) / 3 + 1;
                int prevQuarter = currentQuarter - 1;
                int startMonth = (prevQuarter - 1) * 3 + 1;
                ZonedDateTime refNow = now;
                if (startMonth < 1) {
                    startMonth += 12;
                    refNow = now.minusYears(1L);
                }
                start = ZonedDateTime.of(LocalDate.of(refNow.getYear(), startMonth, 1), LocalTime.MIN, zoneId);
                yield start.plusMonths(3L).minusNanos(1L);
            }
            case PREVIOUS_HALF_YEAR -> {
                start = now.getMonthValue() <= 6 ? ZonedDateTime.of(LocalDate.of(now.getYear() - 1, 7, 1), LocalTime.MIN, zoneId) : ZonedDateTime.of(LocalDate.of(now.getYear(), 1, 1), LocalTime.MIN, zoneId);
                yield start.plusMonths(6L).minusNanos(1L);
            }
            case PREVIOUS_YEAR -> {
                start = ZonedDateTime.of(LocalDate.of(now.getYear() - 1, 1, 1), LocalTime.MIN, zoneId);
                yield start.plusYears(1L).minusNanos(1L);
            }
            case CURRENT_HOUR -> {
                start = now.truncatedTo(ChronoUnit.HOURS);
                yield start.plusHours(1L).minusNanos(1L);
            }
            case CURRENT_DAY -> {
                start = now.toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(1L).minusNanos(1L);
            }
            case CURRENT_DAY_SO_FAR -> {
                start = now.toLocalDate().atStartOfDay(zoneId);
                yield now;
            }
            case CURRENT_WEEK -> {
                start = now.with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(7L).minusNanos(1L);
            }
            case CURRENT_WEEK_ISO -> {
                start = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(zoneId);
                yield start.plusDays(7L).minusNanos(1L);
            }
            case CURRENT_WEEK_SO_FAR -> {
                start = now.with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay(zoneId);
                yield now;
            }
            case CURRENT_WEEK_ISO_SO_FAR -> {
                start = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(zoneId);
                yield now;
            }
            case CURRENT_MONTH -> {
                start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId);
                yield start.plusMonths(1L).minusNanos(1L);
            }
            case CURRENT_MONTH_SO_FAR -> {
                start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId);
                yield now;
            }
            case CURRENT_QUARTER -> {
                int thisQuarter = (now.getMonthValue() - 1) / 3 + 1;
                int quarterStartMonth = (thisQuarter - 1) * 3 + 1;
                start = ZonedDateTime.of(LocalDate.of(now.getYear(), quarterStartMonth, 1), LocalTime.MIN, zoneId);
                yield start.plusMonths(3L).minusNanos(1L);
            }
            case CURRENT_QUARTER_SO_FAR -> {
                int thisQuarter = (now.getMonthValue() - 1) / 3 + 1;
                int quarterStartMonth = (thisQuarter - 1) * 3 + 1;
                start = ZonedDateTime.of(LocalDate.of(now.getYear(), quarterStartMonth, 1), LocalTime.MIN, zoneId);
                yield now;
            }
            case CURRENT_HALF_YEAR -> {
                start = now.getMonthValue() <= 6 ? ZonedDateTime.of(LocalDate.of(now.getYear(), 1, 1), LocalTime.MIN, zoneId) : ZonedDateTime.of(LocalDate.of(now.getYear(), 7, 1), LocalTime.MIN, zoneId);
                yield start.plusMonths(6L).minusNanos(1L);
            }
            case CURRENT_HALF_YEAR_SO_FAR -> {
                start = now.getMonthValue() <= 6 ? ZonedDateTime.of(LocalDate.of(now.getYear(), 1, 1), LocalTime.MIN, zoneId) : ZonedDateTime.of(LocalDate.of(now.getYear(), 7, 1), LocalTime.MIN, zoneId);
                yield now;
            }
            case CURRENT_YEAR -> {
                start = ZonedDateTime.of(LocalDate.of(now.getYear(), 1, 1), LocalTime.MIN, zoneId);
                yield start.plusYears(1L).minusNanos(1L);
            }
            case CURRENT_YEAR_SO_FAR -> {
                start = ZonedDateTime.of(LocalDate.of(now.getYear(), 1, 1), LocalTime.MIN, zoneId);
                yield now;
            }
            default -> throw new IllegalArgumentException("Unsupported interval: " + interval);
        };
        return new TimeRange(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli());
    }

    public static class TimeRange {
        public final long startTs;
        public final long endTs;

        public TimeRange(long startTs, long endTs) {
            this.startTs = startTs;
            this.endTs = endTs;
        }
    }
}
