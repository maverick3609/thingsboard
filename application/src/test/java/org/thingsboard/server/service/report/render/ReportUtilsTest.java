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

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link ReportUtils#prepareReportName}, the one method ported (byte-for-byte,
 * per that class's Javadoc) from PE's decompiled {@code org.thingsboard.server.report.util.ReportUtils}.
 * <p>
 * Every case is asserted against a single {@link #FIXED_DATE} (never {@code new Date()}), so
 * results are deterministic regardless of when/where the suite runs. Every expected string below
 * was independently computed (via a throwaway {@code jshell} run against plain {@link SimpleDateFormat},
 * not hand-arithmetic) before being pasted in here as a literal.
 * <p>
 * The two edge cases that could easily be guessed wrong — an invalid {@link SimpleDateFormat}
 * pattern, and an unrecognized timezone ID — were instead verified two ways before writing the
 * assertions: (1) empirically, in isolation (plain JDK {@code SimpleDateFormat}/{@code TimeZone}
 * calls, no ThingsBoard code involved), and (2) against PE's own {@code prepareReportName} bytecode
 * (CFR-decompiled from {@code report-4.2.0PE.jar}'s {@code org/thingsboard/server/report/util/ReportUtils.class},
 * see {@code docs/jars/}), which turned out to be identical to this port's method body, statement
 * for statement — so "what does the port do" and "what does PE do" are the same question here, and
 * both are answered by evidence rather than assumption.
 */
class ReportUtilsTest {

    /** 2024-03-15T10:30:45Z — arbitrary but fixed; nothing below ever calls {@code new Date()}. */
    private static final Date FIXED_DATE = Date.from(Instant.parse("2024-03-15T10:30:45.000Z"));

    // ----- (a) null/empty pattern -> DEFAULT_REPORT_NAME_PATTERN -----

    @Test
    void nullPatternFallsBackToDefaultPattern() {
        String name = ReportUtils.prepareReportName(null, FIXED_DATE, "UTC");

        assertThat(name).isEqualTo("report-2024-03-15_10:30:45");
    }

    @Test
    void emptyPatternFallsBackToDefaultPattern() {
        String name = ReportUtils.prepareReportName("", FIXED_DATE, "UTC");

        assertThat(name).isEqualTo("report-2024-03-15_10:30:45");
    }

    // ----- (b) multiple %d{} tokens, all replaced -----

    @Test
    void multipleDateTokensAreAllReplaced() {
        String pattern = "audit_%d{yyyy-MM-dd}_%d{HH-mm-ss}_end";

        String name = ReportUtils.prepareReportName(pattern, FIXED_DATE, "UTC");

        assertThat(name).isEqualTo("audit_2024-03-15_10-30-45_end");
    }

    // ----- (c) invalid SimpleDateFormat pattern -----

    /**
     * Verified (not assumed) PE behavior: PE's {@code prepareReportName} bytecode has no
     * try/catch around {@code new SimpleDateFormat(...)} either — this port's identical method
     * body means an invalid pattern throws uncaught in both, matching each other exactly. An
     * unterminated quote is a standard {@link SimpleDateFormat} invalid-pattern trigger,
     * independently confirmed (plain {@code new SimpleDateFormat("yyyy-MM-dd'")}, no
     * ThingsBoard code involved) to throw {@code IllegalArgumentException: Unterminated quote}.
     */
    @Test
    void invalidDateFormatPatternThrowsIllegalArgumentExceptionMatchingPe() {
        String pattern = "broken-%d{yyyy-MM-dd'}-name";

        assertThatThrownBy(() -> ReportUtils.prepareReportName(pattern, FIXED_DATE, "UTC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unterminated quote");
    }

    // ----- (d) null/unknown timezone -----

    /**
     * {@code timeZoneStr == null} takes {@code TimeZone.getDefault()} (the JVM's own default,
     * whatever it is on the machine running this test) — asserted against an independently
     * computed oracle using that same JDK call, rather than a hardcoded zone, so this doesn't
     * assume any particular CI/dev-machine timezone.
     */
    @Test
    void nullTimezoneFallsBackToJvmDefault() {
        String pattern = "ts-%d{yyyy-MM-dd_HH:mm:ss}";

        String name = ReportUtils.prepareReportName(pattern, FIXED_DATE, null);

        assertThat(name).isEqualTo("ts-" + formatWithTimeZone("yyyy-MM-dd_HH:mm:ss", TimeZone.getDefault()));
    }

    /**
     * An unrecognized (but non-null) timezone ID doesn't throw — {@code TimeZone.getTimeZone}'s
     * own documented contract is to fall back to {@code "GMT"} for an ID it can't understand,
     * independently confirmed via a plain {@code TimeZone.getTimeZone("Not/A/Real/Zone")} call.
     * GMT and UTC share the same (zero) offset, so for this fixed instant the rendered clock
     * reading is identical to the UTC cases above — asserted as a literal for readability, plus an
     * explicit check that the port really did thread the JDK's own GMT fallback through (not, say,
     * silently defaulting to the JVM's local zone instead).
     */
    @Test
    void unknownTimezoneFallsBackToGmtPerJdkContract() {
        assertThat(TimeZone.getTimeZone("Not/A/Real/Zone").getID()).isEqualTo("GMT");

        String pattern = "ts-%d{yyyy-MM-dd_HH:mm:ss}";

        String name = ReportUtils.prepareReportName(pattern, FIXED_DATE, "Not/A/Real/Zone");

        assertThat(name).isEqualTo("ts-2024-03-15_10:30:45");
    }

    private static String formatWithTimeZone(String pattern, TimeZone timeZone) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(pattern);
        dateFormat.setTimeZone(timeZone);
        return dateFormat.format(FIXED_DATE);
    }

}
