// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
