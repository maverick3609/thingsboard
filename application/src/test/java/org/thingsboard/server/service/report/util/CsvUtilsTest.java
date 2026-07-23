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
package org.thingsboard.server.service.report.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link CsvUtils} — the CSV formula-injection guard (Inferrix hardening over PE) and the
 * unchanged commons-csv structural escaping. Proves that every cell beginning with a spreadsheet formula
 * trigger ({@code = + - @}, TAB, CR) is neutralised with a leading single quote in the RAW bytes, so no field
 * opens as a live formula in Excel / Google Sheets / LibreOffice.
 */
class CsvUtilsTest {

    @Test
    void neutralizesEachFormulaTriggerWithLeadingQuote() {
        assertThat(CsvUtils.neutralizeFormula("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvUtils.neutralizeFormula("+1")).isEqualTo("'+1");
        assertThat(CsvUtils.neutralizeFormula("-1")).isEqualTo("'-1");
        assertThat(CsvUtils.neutralizeFormula("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvUtils.neutralizeFormula("\t=1+1")).isEqualTo("'\t=1+1"); // leading TAB
        assertThat(CsvUtils.neutralizeFormula("\r=1+1")).isEqualTo("'\r=1+1"); // leading CR
    }

    @Test
    void neutralizesWhitespacePrefixedAndFullWidthFormulaLeads() {
        // A formula operator behind leading whitespace / a stripped control char (importers strip it, then
        // evaluate the remainder) — a bare first-character check would miss these.
        assertThat(CsvUtils.neutralizeFormula(" =1+1")).isEqualTo("' =1+1");     // leading space
        assertThat(CsvUtils.neutralizeFormula("\n=1+1")).isEqualTo("'\n=1+1");   // leading LF
        assertThat(CsvUtils.neutralizeFormula("  @SUM(A1)")).isEqualTo("'  @SUM(A1)");
        // Full-width operators CJK-locale Excel normalises to ASCII and evaluates.
        assertThat(CsvUtils.neutralizeFormula("＝1+1")).isEqualTo("'＝1+1");
        assertThat(CsvUtils.neutralizeFormula("＋1")).isEqualTo("'＋1");
    }

    @Test
    void leavesSafeValuesUnchanged() {
        assertThat(CsvUtils.neutralizeFormula("Device A")).isEqualTo("Device A");
        assertThat(CsvUtils.neutralizeFormula("42.5")).isEqualTo("42.5");
        assertThat(CsvUtils.neutralizeFormula("a=b")).isEqualTo("a=b"); // trigger not leading -> untouched
        assertThat(CsvUtils.neutralizeFormula(" hello")).isEqualTo(" hello"); // leading space, non-formula -> untouched
        assertThat(CsvUtils.neutralizeFormula("   ")).isEqualTo("   "); // all-whitespace -> nothing to neutralise
        assertThat(CsvUtils.neutralizeFormula("\tfoo")).isEqualTo("\tfoo"); // leading TAB, non-formula -> untouched
        assertThat(CsvUtils.neutralizeFormula("")).isEqualTo("");
        assertThat(CsvUtils.neutralizeFormula(null)).isNull();
    }

    @Test
    void generateCsvNeutralizesEveryCellHeaderAndData() {
        // Header AND data cells that begin with a trigger are neutralised in the RAW bytes.
        byte[] bytes = CsvUtils.generateCsv(List.of(
                List.of("=Header", "Safe"),
                List.of("=1+1", "+1"),
                List.of("-1", "@SUM(A1)"),
                List.of("\t=cmd", "\r=cmd")));
        String csv = new String(bytes, StandardCharsets.UTF_8);

        // The neutralised forms are present verbatim...
        assertThat(csv).contains("'=Header").contains("'=1+1").contains("'+1").contains("'-1").contains("'@SUM(A1)");
        // ...and NO field opens as a formula: no trigger immediately after a record start or a delimiter.
        assertThat(csv.stripLeading())
                .as("first field must not open as a formula")
                .doesNotStartWith("=").doesNotStartWith("+").doesNotStartWith("-").doesNotStartWith("@");
        assertThat(csv)
                .as("no field after a delimiter opens as a formula")
                .doesNotContain(",=").doesNotContain(",+").doesNotContain(",-").doesNotContain(",@");
    }

    @Test
    void generateCsvStillEscapesCsvStructure() {
        // Structural escaping is unchanged — commons-csv quotes a value that would otherwise break the row.
        byte[] bytes = CsvUtils.generateCsv(List.of(List.of("a,b", "c\"d", "e\nf")));
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("\"a,b\"");      // embedded comma -> quoted
        assertThat(csv).contains("\"c\"\"d\"");   // embedded quote -> doubled + quoted
        assertThat(csv).contains("\"e\nf\"");      // embedded newline -> quoted
    }

}
