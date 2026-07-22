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

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS colour normaliser (PE {@code report.util.ColorUtils}). flying-saucer's CSS parser accepts only a
 * subset of colour syntaxes, so component styling (heading colour, divider colour, layout background/
 * border, page background) is normalised to {@code rgb(...)} / {@code rgba(...)} before it reaches the
 * {@code @style}. Supports named / {@code #hex} (3/6/8) / {@code rgb[a]} / {@code hsl[a]} inputs.
 */
public class ColorUtils {

    private static final Pattern HSL_PATTERN = Pattern.compile("hsla?\\(\\s*(\\d+)\\s*,\\s*(\\d+)%\\s*,\\s*(\\d+)%\\s*(,\\s*([0-1]\\.\\d+))?\\s*\\)");

    public static String normalizeCssColor(String color) {
        Color c = parseCssColor(color);
        if (c.getAlpha() == 255) {
            return "rgb(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + ")";
        }
        return "rgba(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "," + (double) Math.round((float) c.getAlpha() / 255.0f * 100.0) / 100.0 + ")";
    }

    public static String normalizeCssColorOrDefault(String color, String defaultColor) {
        return color != null ? normalizeCssColor(color) : defaultColor;
    }

    public static Color parseCssColor(String cssColor) {
        if (cssColor == null || cssColor.trim().isEmpty()) {
            throw new IllegalArgumentException("CSS color cannot be null or empty");
        }
        cssColor = cssColor.trim().toLowerCase();
        Color namedColor = getNamedColor(cssColor);
        if (namedColor != null) {
            return namedColor;
        }
        if (cssColor.startsWith("#")) {
            return parseHexColor(cssColor);
        }
        if (cssColor.startsWith("rgb")) {
            return parseRgbColor(cssColor);
        }
        if (cssColor.startsWith("hsl")) {
            return parseHslColor(cssColor);
        }
        throw new IllegalArgumentException("Unsupported CSS color format: " + cssColor);
    }

    private static Color getNamedColor(String name) {
        return switch (name) {
            case "red" -> Color.RED;
            case "blue" -> Color.BLUE;
            case "green" -> Color.GREEN;
            case "black" -> Color.BLACK;
            case "white" -> Color.WHITE;
            case "yellow" -> Color.YELLOW;
            case "cyan" -> Color.CYAN;
            case "magenta" -> Color.MAGENTA;
            case "gray" -> Color.GRAY;
            default -> null;
        };
    }

    private static Color parseHexColor(String hex) {
        String hexClean = hex.replace("#", "");
        if (hexClean.length() == 3) {
            hexClean = "" + hexClean.charAt(0) + hexClean.charAt(0) + hexClean.charAt(1) + hexClean.charAt(1) + hexClean.charAt(2) + hexClean.charAt(2);
        }
        if (hexClean.length() == 6 || hexClean.length() == 8) {
            try {
                int r = Integer.parseInt(hexClean.substring(0, 2), 16);
                int g = Integer.parseInt(hexClean.substring(2, 4), 16);
                int b = Integer.parseInt(hexClean.substring(4, 6), 16);
                float a = hexClean.length() == 8 ? (float) Integer.parseInt(hexClean.substring(6, 8), 16) / 255.0f : 1.0f;
                return new Color((float) r / 255.0f, (float) g / 255.0f, (float) b / 255.0f, a);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid HEX color: " + hex, e);
            }
        }
        throw new IllegalArgumentException("Invalid HEX color length: " + hex);
    }

    private static Color parseRgbColor(String rgb) {
        Pattern pattern = Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(,\\s*([0-1]\\.\\d+))?\\s*\\)");
        Matcher matcher = pattern.matcher(rgb);
        if (matcher.matches()) {
            int r = Integer.parseInt(matcher.group(1));
            int g = Integer.parseInt(matcher.group(2));
            int b = Integer.parseInt(matcher.group(3));
            float a = matcher.group(5) != null ? Float.parseFloat(matcher.group(5)) : 1.0f;
            validateRgbValues(r, g, b, a);
            return new Color((float) r / 255.0f, (float) g / 255.0f, (float) b / 255.0f, a);
        }
        throw new IllegalArgumentException("Invalid RGB/RGBA color: " + rgb);
    }

    private static Color parseHslColor(String hsl) {
        Matcher matcher = HSL_PATTERN.matcher(hsl);
        if (matcher.matches()) {
            float h = Float.parseFloat(matcher.group(1)) / 360.0f;
            float s = Float.parseFloat(matcher.group(2)) / 100.0f;
            float l = Float.parseFloat(matcher.group(3)) / 100.0f;
            float a = matcher.group(5) != null ? Float.parseFloat(matcher.group(5)) : 1.0f;
            validateHslValues(h * 360.0f, s * 100.0f, l * 100.0f, a);
            return hslToRgb(h, s, l, a);
        }
        throw new IllegalArgumentException("Invalid HSL/HSLA color: " + hsl);
    }

    private static void validateRgbValues(int r, int g, int b, float a) {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255 || a < 0.0f || a > 1.0f) {
            throw new IllegalArgumentException("RGB values must be in [0, 255], alpha in [0, 1]");
        }
    }

    private static void validateHslValues(float h, float s, float l, float a) {
        if (h < 0.0f || h > 360.0f || s < 0.0f || s > 100.0f || l < 0.0f || l > 100.0f || a < 0.0f || a > 1.0f) {
            throw new IllegalArgumentException("HSL values must be: H in [0, 360], S/L in [0, 100], alpha in [0, 1]");
        }
    }

    private static Color hslToRgb(float h, float s, float l, float a) {
        float r;
        float g;
        float c = (1.0f - Math.abs(2.0f * l - 1.0f)) * s;
        float x = c * (1.0f - Math.abs(h * 6.0f % 2.0f - 1.0f));
        float m = l - c / 2.0f;
        int sector = (int) (h * 6.0f);
        float b = switch (sector) {
            case 0 -> {
                r = c;
                g = x;
                yield 0.0f;
            }
            case 1 -> {
                r = x;
                g = c;
                yield 0.0f;
            }
            case 2 -> {
                r = 0.0f;
                g = c;
                yield x;
            }
            case 3 -> {
                r = 0.0f;
                g = x;
                yield c;
            }
            case 4 -> {
                r = x;
                g = 0.0f;
                yield c;
            }
            default -> {
                r = c;
                g = 0.0f;
                yield x;
            }
        };
        r = (r + m) * 255.0f;
        g = (g + m) * 255.0f;
        b = (b + m) * 255.0f;
        return new Color(r / 255.0f, g / 255.0f, b / 255.0f, a);
    }
}
