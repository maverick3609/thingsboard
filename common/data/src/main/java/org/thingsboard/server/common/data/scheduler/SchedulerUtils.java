// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import org.thingsboard.server.common.data.StringUtils;

import java.util.Calendar;
import java.util.TimeZone;

public class SchedulerUtils {

    public static Calendar getCalendarWithTimeZone(String timezone) {
        TimeZone tz = StringUtils.isEmpty(timezone) ? TimeZone.getTimeZone("UTC") : TimeZone.getTimeZone(timezone);
        return Calendar.getInstance(tz);
    }

}
