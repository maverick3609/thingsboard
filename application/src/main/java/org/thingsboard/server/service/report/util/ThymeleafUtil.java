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

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.CharEncoding;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

/**
 * Report text rendering. Two very different trust levels live here, deliberately kept apart:
 * <ul>
 *   <li><b>Trusted classpath templates</b> ({@link #renderFromHtmlTemplate} / {@link
 *       #renderFromSvgTemplate}) — the shipped report layout / component-wrapper / error-box / error-image
 *       files. These ARE Thymeleaf templates (full expression support), which is safe because their source
 *       is our own code, never user input.</li>
 *   <li><b>Designer-authored user content</b> ({@link #renderFromHtmlString} HEADING, {@link
 *       #renderFromTextString} RICH_TEXT) — this is <b>not</b> compiled as a template. It is scanned for
 *       {@code ${name}} tokens and the value is substituted from the variables map by {@link
 *       #substituteVariables}. Nothing in user content is ever evaluated as an expression (no OGNL, no
 *       method calls, no {@code T(...)}), which closes the SSTI sink a string template engine would open.</li>
 * </ul>
 */
public class ThymeleafUtil {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern IMAGE_SRC_PATTERN = Pattern.compile("<img[^>]+src=[\"']\\$\\{([^\"'}]+)}[\"']", 2);
    private static final TemplateEngine htmlClassEngine = new TemplateEngine();
    private static final TemplateEngine svgClassEngine;

    /**
     * Renders a TRUSTED classpath HTML template (report layout, component wrappers, error box). These are
     * shipped templates, never user content, so full Thymeleaf expression support is intentional here.
     */
    public static String renderFromHtmlTemplate(String templateHtml, Map<String, Object> variables) {
        Context context = new Context(Locale.getDefault(), variables);
        return htmlClassEngine.process(templateHtml, (IContext) context);
    }

    /**
     * HEADING user content. This is designer-authored text, <b>not</b> a template: it is scanned for
     * {@code ${name}} tokens whose values are substituted from {@code variables} and HTML-escaped (this is
     * an HTML text/attribute context). No expression/OGNL evaluation. A {@code null} value renders empty.
     */
    public static String renderFromHtmlString(String html, Map<String, Object> variables) {
        if (html == null) {
            return "";
        }
        return substituteVariables(sanitize(html), variables, true);
    }

    /**
     * RICH_TEXT user content. Same data-only {@code ${name}} substitution as {@link #renderFromHtmlString},
     * but the substituted value is left raw — RICH_TEXT's existing contract is HTML. Missing {@code
     * <img src="${key}">} sources fall back to the {@code noImage} placeholder. A {@code null} value renders
     * empty. No expression/OGNL evaluation.
     */
    public static String renderFromTextString(String html, Map<String, Object> variables) {
        if (html == null) {
            return "";
        }
        populateMissingImagePlaceholders(html, variables);
        return substituteVariables(sanitize(html), variables, false);
    }

    /**
     * Data-only {@code ${...}} substitution — deliberately NOT a template engine. Each {@code ${name}}
     * token is either a preserved page-counter token ({@code pageNumber}/{@code totalPages}, emitted as
     * flying-saucer counter spans) or a variable lookup: {@code name} is normalised and read from {@code
     * variables}, and its value substituted (empty when absent). Nothing is ever evaluated as an expression
     * (no OGNL, no method calls, no {@code T(...)}) — this is what closes the SSTI sink. Literal text and
     * unmatched tokens (stray {@code $}, {@code {}) are left inert.
     *
     * @param escapeHtml HTML-escape each substituted value (HEADING); {@code false} leaves it raw (RICH_TEXT).
     */
    private static String substituteVariables(String input, Map<String, Object> variables, boolean escapeHtml) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String replacement = switch (token) {
                case "pageNumber" -> "<span class=\"page-number\"></span>";
                case "totalPages" -> "<span class=\"page-count\"></span>";
                default -> {
                    Object value = variables != null ? variables.get(normalizeVariableName(token)) : null;
                    String text = value != null ? value.toString() : "";
                    yield escapeHtml ? HtmlUtils.htmlEscape(text) : text;
                }
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void populateMissingImagePlaceholders(String html, Map<String, Object> variables) {
        Matcher matcher = IMAGE_SRC_PATTERN.matcher(html);
        while (matcher.find()) {
            String str;
            String key = matcher.group(1);
            Object value = variables.get(key);
            if (value != null && (!(value instanceof String) || !(str = (String) value).isEmpty())) continue;
            variables.put(key, "noImage");
        }
    }

    /**
     * Renders a TRUSTED classpath SVG template (the error-image glyph). Shipped template, not user content.
     */
    public static String renderFromSvgTemplate(String templateSvg, Map<String, Object> variables) {
        Context context = new Context(Locale.getDefault(), variables);
        return svgClassEngine.process(templateSvg, (IContext) context);
    }

    public static String normalizeVariableName(String var) {
        return var.trim().replaceAll("\\s+", "_");
    }

    private static String sanitize(String html) {
        return html.replace("\n", "");
    }

    static {
        ClassLoaderTemplateResolver classResolver = new ClassLoaderTemplateResolver();
        classResolver.setPrefix("/");
        classResolver.setSuffix(".html");
        classResolver.setTemplateMode(TemplateMode.HTML);
        classResolver.setOrder(1);
        classResolver.setCharacterEncoding(CharEncoding.UTF_8);
        htmlClassEngine.setTemplateResolver((ITemplateResolver) classResolver);
        svgClassEngine = new TemplateEngine();
        ClassLoaderTemplateResolver svgClassResolver = new ClassLoaderTemplateResolver();
        svgClassResolver.setPrefix("/");
        svgClassResolver.setSuffix(".svg");
        svgClassResolver.setTemplateMode(TemplateMode.XML);
        svgClassResolver.setOrder(4);
        svgClassResolver.setCharacterEncoding(CharEncoding.UTF_8);
        svgClassEngine.setTemplateResolver((ITemplateResolver) svgClassResolver);
    }
}
