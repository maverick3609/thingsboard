/**
 * Copyright © 2016-2026 The Thingsboard Authors
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

import org.thingsboard.server.common.data.StringUtils;

import java.util.Calendar;
import java.util.TimeZone;

public class SchedulerUtils {

    public static Calendar getCalendarWithTimeZone(String timezone) {
        TimeZone tz = StringUtils.isEmpty(timezone) ? TimeZone.getTimeZone("UTC") : TimeZone.getTimeZone(timezone);
        return Calendar.getInstance(tz);
    }

}
