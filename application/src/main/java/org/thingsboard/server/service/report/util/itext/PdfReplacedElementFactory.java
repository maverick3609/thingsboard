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

import org.w3c.dom.Element;
import org.xhtmlrenderer.extend.FSImage;
import org.xhtmlrenderer.extend.ReplacedElement;
import org.xhtmlrenderer.extend.ReplacedElementFactory;
import org.xhtmlrenderer.extend.UserAgentCallback;
import org.xhtmlrenderer.layout.LayoutContext;
import org.xhtmlrenderer.pdf.BookmarkElement;
import org.xhtmlrenderer.pdf.CheckboxFormField;
import org.xhtmlrenderer.pdf.EmptyReplacedElement;
import org.xhtmlrenderer.pdf.ITextImageElement;
import org.xhtmlrenderer.pdf.TextFormField;
import org.xhtmlrenderer.render.BlockBox;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.simple.extend.FormSubmissionListener;

public class PdfReplacedElementFactory
implements ReplacedElementFactory {
    @Override
    public ReplacedElement createReplacedElement(LayoutContext c, BlockBox box, UserAgentCallback uac, int cssWidth, int cssHeight) {
        String nodeName;
        Element e = box.getElement();
        if (e == null) {
            return null;
        }
        switch (nodeName = e.getNodeName()) {
            case "img": {
                String srcAttr = e.getAttribute("src");
                if (srcAttr.isEmpty()) {
                    srcAttr = "/assets/report/components/image-placeholder.svg";
                }
                if (srcAttr.equals("noImage")) {
                    return null;
                }
                FSImage fsImage = uac.getImageResource(srcAttr).getImage();
                if (fsImage == null) break;
                if (cssWidth != -1 || cssHeight != -1) {
                    fsImage = fsImage.scale(cssWidth, cssHeight);
                }
                return new ITextImageElement(fsImage);
            }
            case "input": {
                String type;
                switch (type = e.getAttribute("type")) {
                    case "hidden": {
                        return new EmptyReplacedElement(1, 1);
                    }
                    case "checkbox": {
                        return new CheckboxFormField(c, box, cssWidth, cssHeight);
                    }
                }
                return new TextFormField(c, box, cssWidth, cssHeight);
            }
            case "bookmark": {
                if (e.hasAttribute("name")) {
                    String name = e.getAttribute("name");
                    c.addBoxId(name, (Box) box);
                    return new BookmarkElement(name);
                }
                return new BookmarkElement(null);
            }
        }
        return null;
    }

    @Override
    public void reset() {
    }

    @Override
    public void remove(Element e) {
    }

    public void remove(String fieldName) {
    }

    @Override
    public void setFormSubmissionListener(FormSubmissionListener listener) {
    }
}
