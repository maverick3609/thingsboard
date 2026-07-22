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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.xhtmlrenderer.css.constants.IdentValue;
import org.xhtmlrenderer.pdf.FontDescription;

public class PdfReportFontFamily {
    private static final int SM_EXACT = 1;
    private static final int SM_LIGHTER_OR_DARKER = 2;
    private static final int SM_DARKER_OR_LIGHTER = 3;
    private final String _name;
    private final List<FontDescription> _fontDescriptions = new ArrayList<>();

    PdfReportFontFamily(String name) {
        this._name = name;
    }

    public String getName() {
        return this._name;
    }

    public List<FontDescription> getFontDescriptions() {
        return this._fontDescriptions;
    }

    public void addFontDescription(FontDescription description) {
        this._fontDescriptions.add(description);
        this._fontDescriptions.sort(Comparator.comparingInt(FontDescription::getWeight));
    }

    public FontDescription match(int desiredWeight, IdentValue style) {
        FontDescription result;
        ArrayList<FontDescription> candidates = new ArrayList<>();
        for (FontDescription description : this._fontDescriptions) {
            if (description.getStyle() != style) continue;
            candidates.add(description);
        }
        if (candidates.isEmpty()) {
            if (style == IdentValue.ITALIC) {
                return this.match(desiredWeight, IdentValue.OBLIQUE);
            }
            if (style == IdentValue.OBLIQUE) {
                return this.match(desiredWeight, IdentValue.NORMAL);
            }
            candidates.addAll(this._fontDescriptions);
        }
        if ((result = this.findByWeight(candidates, desiredWeight, SM_EXACT)) != null) {
            return result;
        }
        if (desiredWeight <= 500) {
            return this.findByWeight(candidates, desiredWeight, SM_LIGHTER_OR_DARKER);
        }
        return this.findByWeight(candidates, desiredWeight, SM_DARKER_OR_LIGHTER);
    }

    private FontDescription findByWeight(List<FontDescription> matches, int desiredWeight, int searchMode) {
        if (searchMode == SM_EXACT) {
            for (FontDescription description : matches) {
                if (description.getWeight() != desiredWeight) continue;
                return description;
            }
            return null;
        }
        if (searchMode == SM_LIGHTER_OR_DARKER) {
            int offset;
            FontDescription description = null;
            for (offset = 0; offset < matches.size() && (description = matches.get(offset)).getWeight() <= desiredWeight; ++offset) {
            }
            if (offset > 0 && description.getWeight() > desiredWeight) {
                return matches.get(offset - 1);
            }
            return description;
        }
        if (searchMode == SM_DARKER_OR_LIGHTER) {
            int offset;
            FontDescription description = null;
            for (offset = matches.size() - 1; offset >= 0 && (description = matches.get(offset)).getWeight() >= desiredWeight; --offset) {
            }
            if (offset != matches.size() - 1 && description != null && description.getWeight() < desiredWeight) {
                return matches.get(offset + 1);
            }
            return description;
        }
        return null;
    }
}
