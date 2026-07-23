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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialises a report's already-rendered rows to CSV bytes (PE {@code report.util.CsvUtils#generateCsv}), via
 * commons-csv's {@link CSVPrinter} with {@link CSVFormat#DEFAULT} (UTF-8). commons-csv escapes CSV
 * <b>structure</b> — commas, quotes, embedded newlines — so a value can't break out of its cell.
 * <p>
 * <b>Security — CSV formula injection (Inferrix hardening; PE has NO such guard).</b> Structural escaping does
 * <b>not</b> neutralise <i>formula</i> triggers. A data-derived cell (entity name, attribute/timeseries value,
 * alarm type — all attacker-influenceable; a device can post a telemetry value) that begins with {@code =},
 * {@code +}, {@code -}, {@code @}, TAB (0x09) or CR (0x0D) is executed as a formula when the CSV is opened in
 * Excel / Google Sheets / LibreOffice — remote code execution on the victim's machine (OWASP CSV Injection).
 * The report data layer (F2) deliberately does NOT escape cell values — HTML escaping is the PDF path's job,
 * and CSV is where formula neutralisation belongs. {@link #neutralizeFormula} prefixes any triggered cell with
 * a single quote so the spreadsheet treats the whole cell as text; {@link #generateCsv} applies it to
 * <b>every</b> cell (header and data). The only change to a legitimate value is that leading quote (e.g. a
 * negative number {@code -5} → {@code '-5}) — the accepted OWASP trade-off, since a leading {@code -}/{@code +}
 * is indistinguishable from a formula.
 */
public final class CsvUtils {

    /**
     * A cell whose FIRST character is one of these is neutralised. {@code = + - @} are the classic
     * spreadsheet-formula leads; TAB (0x09) and CR (0x0D) are included because some spreadsheet importers strip
     * a leading control character and then treat the following {@code =}/{@code +}/… as a formula (OWASP).
     */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private CsvUtils() {
    }

    public static byte[] generateCsv(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            for (List<String> row : rows) {
                List<String> safeRow = new ArrayList<>(row.size());
                for (String cell : row) {
                    safeRow.add(neutralizeFormula(cell));
                }
                csvPrinter.printRecord(safeRow);
            }
            csvPrinter.flush();
        } catch (IOException e) {
            // A ByteArrayOutputStream never actually throws IOException; defensive only so callers stay clean.
            throw new RuntimeException("Failed to generate CSV report", e);
        }
        return out.toByteArray();
    }

    /**
     * Neutralises a single cell against CSV formula injection: if {@code cell} begins with a {@link
     * #FORMULA_TRIGGERS} character, prefixes it with a single quote {@code '} so the spreadsheet renders it as
     * literal text. Otherwise returns the cell unchanged. Null/empty pass through untouched (nothing to
     * neutralise). Package-private so {@code CsvUtilsTest} can assert it directly.
     */
    static String neutralizeFormula(String cell) {
        if (cell == null || cell.isEmpty()) {
            return cell;
        }
        if (FORMULA_TRIGGERS.indexOf(cell.charAt(0)) >= 0) {
            return "'" + cell;
        }
        return cell;
    }

}
