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

import org.apache.commons.lang3.math.NumberUtils;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.DataReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.service.report.context.TbReportCtx;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Report-rendering helpers ported from PE's {@code org.thingsboard.server.report.util.ReportUtils}
 * (decompiled from {@code report-4.2.0PE.jar}). R1 needed only {@code prepareReportName}; R2b's shared data
 * layer ({@link AbstractReportService}) adds the value-formatting/data-source helpers it consumes:
 * {@link #ENTITY_TIME_FIELDS}, {@link #getSingleDataSource}, {@link #convertStringToTypedValue}, and the
 * {@link TbReportCtx}-aware {@code formatTimestamp} overloads. PE's remaining table/CSV helpers
 * ({@code formatValueWithPrecisionAndUnits}, {@code sortRowsByTableSortOrder}, …) stay out of scope until
 * the R2b table renderers (Task G) / CSV (Task I) land.
 */
public final class ReportUtils {

    /** PE-verbatim: {@code %d{<SimpleDateFormat pattern>}} token. */
    private static final Pattern REPORT_NAME_DATE_PATTERN = Pattern.compile("%d\\{([^}]*)}");

    /** PE-verbatim default, also {@link org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig}'s documented example. */
    public static final String DEFAULT_REPORT_NAME_PATTERN = "report-%d{yyyy-MM-dd_HH:mm:ss}";

    /**
     * PE-verbatim: data-key names whose value is an epoch-millis timestamp — {@link AbstractReportService}
     * formats these through {@code formatTimestamp} (and also emits a {@code rawTs_<label>} raw copy for
     * table sorting) rather than passing the raw number through.
     */
    public static final Set<String> ENTITY_TIME_FIELDS = Set.of("ts", "createdTime", "startTime", "endTime", "ackTime", "clearTime", "assignTime");

    private ReportUtils() {
    }

    /**
     * Replaces every {@code %d{<pattern>}} token in {@code namePattern} with {@code reportDate}
     * formatted (via {@link SimpleDateFormat}) in {@code timeZoneStr}. Falls back to
     * {@link #DEFAULT_REPORT_NAME_PATTERN} when {@code namePattern} is null/empty, and to the JVM
     * default timezone when {@code timeZoneStr} is null. PE-verbatim algorithm.
     */
    public static String prepareReportName(String namePattern, Date reportDate, String timeZoneStr) {
        TimeZone timeZone = timeZoneStr == null ? TimeZone.getDefault() : TimeZone.getTimeZone(timeZoneStr);
        String name = namePattern == null || namePattern.isEmpty() ? DEFAULT_REPORT_NAME_PATTERN : namePattern;
        Matcher matcher = REPORT_NAME_DATE_PATTERN.matcher(name);
        while (matcher.find()) {
            String toReplace = matcher.group(0);
            SimpleDateFormat dateFormat = new SimpleDateFormat(matcher.group(1));
            dateFormat.setTimeZone(timeZone);
            String replacement = dateFormat.format(reportDate);
            name = name.replace(toReplace, replacement);
        }
        return name;
    }

    /**
     * Formats an epoch-millis timestamp for display using a {@link DateTimeFormatter} pattern in the given
     * IANA timezone (PE-verbatim, {@code report.util.ReportUtils}). Used to stamp a report's created-time.
     * Returns {@code ""} for a zero timestamp, the raw millis when the pattern is empty or {@code
     * "milliseconds"}, and a diagnostic string (never throws) on a bad pattern/timezone.
     */
    public static String formatTimestamp(long timestamp, String pattern, String timezone) {
        if (timestamp == 0L) {
            return "";
        }
        if (pattern == null || pattern.isEmpty() || pattern.equals("milliseconds")) {
            return String.valueOf(timestamp);
        }
        try {
            ZoneId zoneId = timezone != null && !timezone.isBlank() ? ZoneId.of(timezone) : ZoneId.systemDefault();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern).withZone(zoneId);
            return formatter.format(Instant.ofEpochMilli(timestamp));
        } catch (Exception e) {
            return "Invalid timestamp: " + timestamp;
        }
    }

    /**
     * The single, fully-configured {@link DataSource} of a data component (PE-verbatim). For an ALARM_TABLE
     * it is the alarm source; otherwise the first entry of {@code getDataSources()}. Returns empty when the
     * source is missing or under-configured (a DEVICE source with no device id, an ENTITY source with no
     * entity-alias id) so callers render nothing rather than issuing a bad query.
     */
    public static Optional<DataSource> getSingleDataSource(DataReportComponent component) {
        DataSource dataSource = null;
        if (ReportComponentType.ALARM_TABLE.equals(component.getType())) {
            dataSource = ((AlarmTableComponent) component).getAlarmSource();
        } else {
            List<DataSource> dataSources = component.getDataSources();
            if (dataSources != null && !dataSources.isEmpty()) {
                dataSource = dataSources.get(0);
            }
        }
        if (dataSource == null) {
            return Optional.empty();
        }
        switch (dataSource.getType()) {
            case DEVICE -> {
                if (dataSource.getDeviceId() == null) {
                    return Optional.empty();
                }
            }
            case ENTITY -> {
                if (dataSource.getEntityAliasId() == null) {
                    return Optional.empty();
                }
            }
            default -> {
            }
        }
        return Optional.of(dataSource);
    }

    /**
     * PE-verbatim: coerces a raw string value to the typed value TBEL post-processing expects — a {@code
     * Double} for a numeric string, a {@code Boolean} for {@code "true"}/{@code "false"}, otherwise the
     * string unchanged. Blank input is returned as-is.
     */
    public static Object convertStringToTypedValue(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        if (NumberUtils.isParsable(value)) {
            return Double.parseDouble(value);
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    /**
     * {@link TbReportCtx}-aware formatter for a timestamp held as a string (PE-verbatim). Falls back to the
     * report's {@code timeDataPattern} when {@code pattern} is empty and to the ctx timezone when {@code
     * timezone} is blank; returns a diagnostic (never throws) when the string is not a valid epoch-millis.
     */
    public static String formatTimestamp(String timestampStr, String pattern, TbReportCtx ctx, String timezone) {
        try {
            long timestamp = Long.parseLong(timestampStr);
            return formatTimestamp(timestamp, pattern, ctx, timezone);
        } catch (NumberFormatException e) {
            return "Invalid timestamp string: " + timestampStr;
        }
    }

    /**
     * {@link TbReportCtx}-aware formatter (PE-verbatim): resolves the effective pattern from {@code pattern}
     * else the report's {@code timeDataPattern}, and the timezone from {@code timezone} else the ctx
     * timezone, then delegates to {@link #formatTimestamp(long, String, String)}.
     */
    public static String formatTimestamp(long timestamp, String pattern, TbReportCtx ctx, String timezone) {
        String effectivePattern = pattern != null && !pattern.isEmpty() ? pattern : ctx.getConfiguration().getTimeDataPattern();
        String targetTimezone = StringUtils.isNotBlank(timezone) ? timezone : ctx.getTimeZone();
        return formatTimestamp(timestamp, effectivePattern, targetTimezone);
    }

}
