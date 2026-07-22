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
package org.thingsboard.server.service.report.util.itext;

import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.BaseFont;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.xhtmlrenderer.css.constants.IdentValue;
import org.xhtmlrenderer.css.sheet.FontFaceRule;
import org.xhtmlrenderer.css.value.FontSpecification;
import org.xhtmlrenderer.extend.UserAgentCallback;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.pdf.FontDescription;
import org.xhtmlrenderer.pdf.ITextFSFont;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.TrueTypeUtil;
import org.xhtmlrenderer.render.FSFont;
import org.xhtmlrenderer.util.IOUtil;

@Slf4j
public class PdfReportFontResolver
extends ITextFontResolver {
    private static final Map<String, PdfReportFontFamily> families = new HashMap<>();
    private final Map<String, PdfReportFontFamily> _fontFamilies = new HashMap<>();
    private final Map<String, FontDescription> _fontCache = new ConcurrentHashMap<>();

    @Override
    public FSFont resolveFont(@NotNull SharedContext renderingContext, FontSpecification spec) {
        return this.resolveFont(spec.families, spec.size, spec.fontWeight, spec.fontStyle);
    }

    @Override
    public void flushCache() {
    }

    @Override
    public void flushFontFaceFonts() {
    }

    @Override
    public void importFontFaces(List<FontFaceRule> fontFaces, UserAgentCallback userAgentCallback) {
    }

    private FSFont resolveFont(String[] families, float size, IdentValue weight, IdentValue style) {
        if (style != IdentValue.NORMAL && style != IdentValue.OBLIQUE && style != IdentValue.ITALIC) {
            style = IdentValue.NORMAL;
        }
        if (families != null) {
            for (String family : families) {
                FSFont font = this.resolveFont(family, size, weight, style);
                if (font == null) continue;
                log.debug("Resolved font {}:{}:{} -> {}", family, weight, style, font);
                return font;
            }
        }
        log.debug("Could not resolve font {}:{}:{} - fallback to Roboto", Arrays.toString(families), weight, style);
        return this.resolveFont("Roboto", size, weight, style);
    }

    private FSFont resolveFont(String fontFamily, float size, IdentValue weight, IdentValue style) {
        String normalizedFontFamily = this.stripQuotes(fontFamily);
        String cacheKey = String.format("%s-%s-%s", normalizedFontFamily, weight, style);
        FontDescription result = this._fontCache.get(cacheKey);
        if (result != null) {
            log.debug("Resolved font {}:{}:{} -> {}", fontFamily, weight, style, result);
            return new ITextFSFont(result, size);
        }
        PdfReportFontFamily family = this.getPdfReportFonts().get(normalizedFontFamily);
        if (family != null && (result = family.match(ITextFontResolver.convertWeightToInt(weight), style)) != null) {
            this._fontCache.put(cacheKey, result);
            return new ITextFSFont(result, size);
        }
        return null;
    }

    private Map<String, PdfReportFontFamily> getPdfReportFonts() {
        if (this._fontFamilies.isEmpty()) {
            synchronized (this._fontFamilies) {
                if (this._fontFamilies.isEmpty()) {
                    this._fontFamilies.putAll(this.loadPdfReportFonts());
                }
            }
        }
        return this._fontFamilies;
    }

    private Map<String, PdfReportFontFamily> loadPdfReportFonts() {
        if (families.isEmpty()) {
            synchronized (families) {
                this.addRoboto();
                this.addMonospace();
                this.addSansSerif();
                this.addSerif();
            }
        }
        return families;
    }

    private void addRoboto() {
        PdfReportFontFamily roboto = new PdfReportFontFamily("Roboto");
        this.loadFont(roboto, "/fonts/roboto/Roboto-Regular.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.NORMAL);
        this.loadFont(roboto, "/fonts/roboto/Roboto-Italic.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.ITALIC);
        this.loadFont(roboto, "/fonts/roboto/Roboto-Medium.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.NORMAL);
        this.loadFont(roboto, "/fonts/roboto/Roboto-MediumItalic.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.ITALIC);
        this.loadFont(roboto, "/fonts/roboto/Roboto-Bold.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.NORMAL);
        this.loadFont(roboto, "/fonts/roboto/Roboto-BoldItalic.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.ITALIC);
        families.put("Roboto", roboto);
    }

    private void addMonospace() {
        PdfReportFontFamily monospace = new PdfReportFontFamily("monospace");
        this.loadFont(monospace, "/fonts/monospace/DejaVuSansMono.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.NORMAL);
        this.loadFont(monospace, "/fonts/monospace/DejaVuSansMono-Oblique.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.ITALIC);
        this.loadFont(monospace, "/fonts/monospace/DejaVuSansMono.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.NORMAL);
        this.loadFont(monospace, "/fonts/monospace/DejaVuSansMono-Oblique.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.ITALIC);
        this.loadFont(monospace, "/fonts/monospace/DejaVuSansMono-Bold.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.NORMAL);
        this.loadFont(monospace, "/fonts/monospace/DejaVuSansMono-BoldOblique.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.ITALIC);
        families.put("monospace", monospace);
    }

    private void addSansSerif() {
        PdfReportFontFamily sansSerif = new PdfReportFontFamily("sans-serif");
        this.loadFont(sansSerif, "/fonts/sansserif/LiberationSans-Regular.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.NORMAL);
        this.loadFont(sansSerif, "/fonts/sansserif/LiberationSans-Italic.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.ITALIC);
        this.loadFont(sansSerif, "/fonts/sansserif/LiberationSans-Regular.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.NORMAL);
        this.loadFont(sansSerif, "/fonts/sansserif/LiberationSans-Italic.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.ITALIC);
        this.loadFont(sansSerif, "/fonts/sansserif/LiberationSans-Bold.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.NORMAL);
        this.loadFont(sansSerif, "/fonts/sansserif/LiberationSans-BoldItalic.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.ITALIC);
        families.put("sans-serif", sansSerif);
    }

    private void addSerif() {
        PdfReportFontFamily serif = new PdfReportFontFamily("serif");
        this.loadFont(serif, "/fonts/serif/LiberationSerif-Regular.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.NORMAL);
        this.loadFont(serif, "/fonts/serif/LiberationSerif-Italic.ttf", "Identity-H", true, IdentValue.NORMAL, IdentValue.ITALIC);
        this.loadFont(serif, "/fonts/serif/LiberationSerif-Regular.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.NORMAL);
        this.loadFont(serif, "/fonts/serif/LiberationSerif-Italic.ttf", "Identity-H", true, IdentValue.FONT_WEIGHT_500, IdentValue.ITALIC);
        this.loadFont(serif, "/fonts/serif/LiberationSerif-Bold.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.NORMAL);
        this.loadFont(serif, "/fonts/serif/LiberationSerif-BoldItalic.ttf", "Identity-H", true, IdentValue.BOLD, IdentValue.ITALIC);
        families.put("serif", serif);
    }

    private void loadFont(PdfReportFontFamily family, String uri, String encoding, boolean embedded, IdentValue fontWeightOverride, IdentValue fontStyleOverride) {
        try {
            byte[] ttfAfm = this.getBinaryResource(uri);
            BaseFont font = BaseFont.createFont(uri, encoding, embedded, false, ttfAfm, null);
            family.addFontDescription(TrueTypeUtil.extractDescription(uri, ttfAfm, font, true, fontWeightOverride, fontStyleOverride));
        }
        catch (DocumentException | IOException e) {
            log.warn("Could not load font " + uri, e);
        }
    }

    private String stripQuotes(String text) {
        String result = text;
        if (result.startsWith("\"")) {
            result = result.substring(1);
        }
        if (result.endsWith("\"")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private byte[] getBinaryResource(String uri) throws IOException {
        URL url = PdfReportFontResolver.class.getResource(uri);
        if (url == null) {
            throw new IOException("Could not find resource " + uri);
        }
        try (InputStream is = url.openStream()) {
            return is == null ? null : IOUtil.readBytes(is);
        }
    }
}
