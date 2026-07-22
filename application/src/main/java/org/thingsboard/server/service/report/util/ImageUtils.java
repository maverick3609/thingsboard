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

import com.lowagie.text.Image;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.xhtmlrenderer.extend.Size;

public class ImageUtils {
    public static final String EMPTY_IMAGE_URI = "tb-empty-image";

    public static Size getOriginalImageSize(byte[] pngImage) throws IOException {
        Image img = Image.getInstance(pngImage);
        return new Size((int) img.getPlainWidth(), (int) img.getPlainHeight());
    }

    public static boolean isTbImage(String uri) {
        return isInternalTbImage(uri) || isPublicTbImage(uri);
    }

    public static boolean isInternalTbImage(String uri) {
        return uri.startsWith("/api/images/tenant/") || uri.startsWith("/api/images/system/");
    }

    public static boolean isPublicTbImage(String uri) {
        return uri.startsWith("/api/images/public/");
    }

    public static boolean isEmptyImage(String uri) {
        return EMPTY_IMAGE_URI.equals(uri);
    }

    static {
        Logger.getLogger("com.github.weisj.jsvg").setLevel(Level.OFF);
    }
}
