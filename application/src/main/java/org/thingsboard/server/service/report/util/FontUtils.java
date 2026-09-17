// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.util;

import org.thingsboard.server.common.data.StringUtils;

import java.util.Set;

/**
 * The single source of truth for the {@code font-family} values a report component may emit into a
 * {@code font-family:${…}} CSS declaration.
 * <p>
 * Report style is authored by config (a tenant admin), and several templates concatenate the resolved
 * font family raw into a {@code th:style="|… font-family:${fontFamily}; …|"} attribute. Because CSS
 * declarations are {@code ;}-separated, an un-validated free-text family (e.g.
 * {@code "Roboto; background-image:url(…)"}) would inject arbitrary inline CSS on that element. So every
 * emitted family is constrained to {@link #ALLOWED_FONT_FAMILIES} — the families the bundled
 * {@code PdfReportFontResolver} actually registers — with anything else collapsed to {@link #DEFAULT_FONT_FAMILY}.
 * <p>
 * This centralises the control R2a introduced on the standalone {@code HeadingRenderer}; the table renderers
 * (header / cell / table-heading font) share it so the allowlist can't drift or be silently dropped by a
 * PE-verbatim port.
 */
public final class FontUtils {

    /** Families the bundled {@code PdfReportFontResolver} registers; only these ever reach {@code font-family:${…}}. */
    public static final Set<String> ALLOWED_FONT_FAMILIES = Set.of("Roboto", "monospace", "sans-serif", "serif");
    public static final String DEFAULT_FONT_FAMILY = "Roboto";

    private FontUtils() {
    }

    /**
     * Resolves a config font family to an allowlisted value: a case-insensitive match returns the canonical
     * spelling; {@code null}/blank or any unknown value returns {@link #DEFAULT_FONT_FAMILY}. The result is
     * always safe to interpolate into a {@code font-family:} declaration (no CSS metacharacters).
     */
    public static String resolveFontFamily(String family) {
        if (StringUtils.isBlank(family)) {
            return DEFAULT_FONT_FAMILY;
        }
        String trimmed = family.trim();
        for (String allowed : ALLOWED_FONT_FAMILIES) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return allowed;
            }
        }
        return DEFAULT_FONT_FAMILY;
    }
}
