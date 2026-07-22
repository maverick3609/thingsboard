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

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.SVGRenderingHints;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.geometry.size.FloatSize;
import com.github.weisj.jsvg.parser.DefaultParserProvider;
import com.github.weisj.jsvg.parser.DomProcessor;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.ParserProvider;
import com.github.weisj.jsvg.parser.SVGLoader;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public class PdfSvgDocument {
    private final SVGDocument _document;
    private final ViewBox _viewBox;

    public static PdfSvgDocument fromSvgString(String svgString) {
        return fromSvgBytes(svgString.getBytes(StandardCharsets.UTF_8));
    }

    public static PdfSvgDocument fromSvgBytes(byte[] svgBytes) {
        SVGLoader loader = new SVGLoader();
        final AtomicReference<ViewBox> viewBoxRef = new AtomicReference<>();
        DefaultParserProvider parserProvider = new DefaultParserProvider() {

            @Override
            public DomProcessor createPreProcessor() {
                return root -> viewBoxRef.set(root.attributeNode().getViewBox());
            }
        };
        SVGDocument document = loader.load(new ByteArrayInputStream(svgBytes), null, LoaderContext.builder().parserProvider((ParserProvider) parserProvider).build());
        if (document != null) {
            return new PdfSvgDocument(document, viewBoxRef.get());
        }
        return null;
    }

    public PdfSvgDocument(SVGDocument document, ViewBox viewBox) {
        this._document = document;
        this._viewBox = viewBox;
    }

    public FloatSize size() {
        return this._document.size();
    }

    public byte[] render(float targetWidth, float targetHeight, int usablePageWidthPx) throws Exception {
        ViewBox targetViewBox = this._viewBox != null ? new ViewBox(this._viewBox.x, this._viewBox.y, targetWidth, targetHeight) : new ViewBox(0.0f, 0.0f, this.size().width, this.size().height);
        if (targetViewBox.width != (float) usablePageWidthPx) {
            float scale = (float) usablePageWidthPx / targetViewBox.width;
            targetViewBox.width = usablePageWidthPx;
            targetViewBox.height *= scale;
            targetViewBox.x *= scale;
            targetViewBox.y *= scale;
        }
        BufferedImage image = new BufferedImage((int) targetViewBox.width, (int) targetViewBox.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(SVGRenderingHints.KEY_IMAGE_ANTIALIASING, SVGRenderingHints.VALUE_IMAGE_ANTIALIASING_ON);
        graphics.setRenderingHint(SVGRenderingHints.KEY_SOFT_CLIPPING, SVGRenderingHints.VALUE_SOFT_CLIPPING_ON);
        this._document.render((Component) null, graphics, targetViewBox);
        graphics.dispose();
        return toCompressedPngData(image);
    }

    private static byte[] toCompressedPngData(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(image);
        ImageWriter writer = ImageIO.getImageWriters(type, "png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.0f);
        }
        ImageOutputStream output = ImageIO.createImageOutputStream(out);
        writer.setOutput(output);
        try {
            writer.write(null, new IIOImage(image, null, null), param);
        }
        finally {
            writer.dispose();
            output.flush();
        }
        return out.toByteArray();
    }
}
