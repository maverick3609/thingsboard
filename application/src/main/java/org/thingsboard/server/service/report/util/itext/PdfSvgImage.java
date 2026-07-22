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

import org.xhtmlrenderer.extend.FSImage;
import org.xhtmlrenderer.extend.Size;
import org.xhtmlrenderer.pdf.ITextFSImage;

public class PdfSvgImage
extends ITextFSImage {
    private final PdfSvgDocument _svgDocument;
    private byte[] _image;
    private final float dotsPerPixel;
    private final int usablePageWidthPx;

    public PdfSvgImage(PdfSvgDocument svgDocument, float dotsPerPixel, int usablePageWidthPx) {
        this(svgDocument, dotsPerPixel, usablePageWidthPx, new Size((int) (svgDocument.size().width * dotsPerPixel), (int) (svgDocument.size().height * dotsPerPixel)));
    }

    public PdfSvgImage(PdfSvgDocument svgDocument, float dotsPerPixel, int usablePageWidthPx, Size size) {
        super(null, size, null);
        this._svgDocument = svgDocument;
        this.dotsPerPixel = dotsPerPixel;
        this.usablePageWidthPx = usablePageWidthPx;
    }

    @Override
    public FSImage scale(int width, int height) {
        Size newSize = this.size.scale(width, height);
        if (this.size != newSize) {
            return new PdfSvgImage(this._svgDocument, this.dotsPerPixel, this.usablePageWidthPx, newSize);
        }
        return this;
    }

    @Override
    public byte[] getImage() {
        if (this._image == null) {
            try {
                this._image = this._svgDocument.render((float) this.getWidth() / this.dotsPerPixel, (float) this.getHeight() / this.dotsPerPixel, this.usablePageWidthPx);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return this._image;
    }
}
