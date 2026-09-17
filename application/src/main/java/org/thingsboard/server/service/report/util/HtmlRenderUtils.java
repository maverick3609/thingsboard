// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.util;

import java.util.HashMap;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document.OutputSettings.Syntax;
import org.thingsboard.server.service.report.util.itext.PdfReplacedElementFactory;
import org.thingsboard.server.service.report.util.itext.PdfReportFontResolver;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;
import org.thingsboard.server.service.report.util.itext.PdfReportTextRenderer;
import org.thingsboard.server.service.report.util.itext.PdfReportUserAgent;
import org.w3c.dom.Document;
import org.xhtmlrenderer.extend.FontResolver;
import org.xhtmlrenderer.extend.ReplacedElementFactory;
import org.xhtmlrenderer.extend.TextRenderer;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.pdf.ITextUserAgent;

public class HtmlRenderUtils {
    private static final ITextFontResolver fontResolver = new PdfReportFontResolver();
    private static final TextRenderer textRenderer = new PdfReportTextRenderer();

    public static ITextRenderer createRenderer(PdfReportImageResolver imageResolver, int usablePageWidthPx) {
        ITextOutputDevice outputDevice = new ITextOutputDevice(26.666666f);
        PdfReportUserAgent userAgent = new PdfReportUserAgent(imageResolver, outputDevice, 20, usablePageWidthPx);
        PdfReplacedElementFactory replacedElementFactory = new PdfReplacedElementFactory();
        return new ITextRenderer(26.666666f, 20, outputDevice, (ITextUserAgent) userAgent, (FontResolver) fontResolver, (ReplacedElementFactory) replacedElementFactory, textRenderer);
    }

    /**
     * Parses report HTML straight into the W3C DOM flying-saucer consumes.
     * <p>
     * Deliberately no serialise-and-reparse step: jsoup's XML output syntax wraps a style element's body
     * in CDATA comment guards, which the XML parser then unwraps into stray empty CSS comments around the
     * rules. Handing over the parsed DOM avoids that round trip entirely.
     */
    public static Document parseDom(String html) {
        org.jsoup.nodes.Document jsoupDocument = Jsoup.parse(html);
        jsoupDocument.outputSettings().syntax(Syntax.xml);
        return new W3CDom().fromJsoup(jsoupDocument);
    }

    public static int measureHtmlHeight(ITextRenderer renderer, String htmlContent, int width) throws Exception {
        HashMap<String, Object> variables = new HashMap<>();
        variables.put("htmlContent", htmlContent);
        variables.put("pageWidth", width + "px");
        variables.put("pageHeight", "1000px");
        String renderedHtmlContent = ThymeleafUtil.renderFromHtmlTemplate("html/measure-template", variables);
        Document document = parseDom(renderedHtmlContent);
        renderer.setDocument(document);
        renderer.layout();
        return (int) Math.ceil((double) renderer.getRootBox().getHeight() / 20.0);
    }
}
