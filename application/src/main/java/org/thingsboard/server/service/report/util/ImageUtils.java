// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
