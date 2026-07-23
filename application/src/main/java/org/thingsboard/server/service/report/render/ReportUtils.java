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

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Report-artifact naming. Ported from PE's {@code org.thingsboard.server.report.util.ReportUtils}
 * (decompiled from {@code report-4.2.0PE.jar}) — only {@code prepareReportName}, the one method
 * this port's renderer needs; PE's other ~15 methods there belong to the multi-component
 * (table/chart/text) report engine, out of R1 scope (spec §6.1).
 */
public final class ReportUtils {

    /** PE-verbatim: {@code %d{<SimpleDateFormat pattern>}} token. */
    private static final Pattern REPORT_NAME_DATE_PATTERN = Pattern.compile("%d\\{([^}]*)}");

    /** PE-verbatim default, also {@link org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig}'s documented example. */
    public static final String DEFAULT_REPORT_NAME_PATTERN = "report-%d{yyyy-MM-dd_HH:mm:ss}";

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

}
