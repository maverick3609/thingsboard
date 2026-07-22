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

import com.lowagie.text.pdf.BaseFont;
import org.xhtmlrenderer.extend.FontContext;
import org.xhtmlrenderer.pdf.FontDescription;
import org.xhtmlrenderer.pdf.ITextFSFont;
import org.xhtmlrenderer.pdf.ITextFSFontMetrics;
import org.xhtmlrenderer.pdf.ITextTextRenderer;
import org.xhtmlrenderer.render.FSFont;
import org.xhtmlrenderer.render.FSFontMetrics;

public class PdfReportTextRenderer
extends ITextTextRenderer {
    @Override
    public FSFontMetrics getFSFontMetrics(FontContext context, FSFont font, String string) {
        FontDescription description = ((ITextFSFont) font).getFontDescription();
        BaseFont bf = description.getFont();
        float size = font.getSize2D();
        float strikethroughThickness = description.getYStrikeoutSize() != 0.0f ? description.getYStrikeoutSize() / 1000.0f * size : size / 12.0f;
        return new ITextFSFontMetrics(bf.getFontDescriptor(9, size) + bf.getFontDescriptor(11, size), -bf.getFontDescriptor(10, size), -description.getYStrikeoutPosition() / 1000.0f * size, strikethroughThickness, -description.getUnderlinePosition() / 1000.0f * size, description.getUnderlineThickness() / 1000.0f * size);
    }
}
