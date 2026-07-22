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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.CharEncoding;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

public class ThymeleafUtil {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern IMAGE_SRC_PATTERN = Pattern.compile("<img[^>]+src=[\"']\\$\\{([^\"'}]+)}[\"']", 2);
    private static final TemplateEngine htmlClassEngine = new TemplateEngine();
    private static final TemplateEngine htmlStringEngine;
    private static final TemplateEngine textStringEngine;
    private static final TemplateEngine svgClassEngine;

    public static String renderFromHtmlTemplate(String templateHtml, Map<String, Object> variables) {
        Context context = new Context(Locale.getDefault(), variables);
        return htmlClassEngine.process(templateHtml, (IContext) context);
    }

    public static String renderFromHtmlString(String html, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        return htmlStringEngine.process(convertToThymeleafInline(sanitize(html)), (IContext) context);
    }

    public static String renderFromTextString(String html, Map<String, Object> variables) {
        populateMissingImagePlaceholders(html, variables);
        Context context = new Context();
        context.setVariables(variables);
        return textStringEngine.process(convertToThymeleafInline(sanitize(html)), (IContext) context);
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

    public static String renderFromSvgTemplate(String templateSvg, Map<String, Object> variables) {
        Context context = new Context(Locale.getDefault(), variables);
        return svgClassEngine.process(templateSvg, (IContext) context);
    }

    public static String convertToThymeleafInline(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String var;
            matcher.appendReplacement(result, Matcher.quoteReplacement(switch (var = matcher.group(1).trim()) {
                case "pageNumber" -> "<span class=\"page-number\"></span>";
                case "totalPages" -> "<span class=\"page-count\"></span>";
                default -> {
                    String safeKey = normalizeVariableName(var);
                    yield "[(${" + safeKey + "})]";
                }
            }));
        }
        matcher.appendTail(result);
        return result.toString();
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
        htmlStringEngine = new TemplateEngine();
        StringTemplateResolver stringResolver = new StringTemplateResolver();
        stringResolver.setTemplateMode(TemplateMode.HTML);
        stringResolver.setResolvablePatterns(Set.of("*"));
        stringResolver.setCacheable(false);
        stringResolver.setOrder(2);
        htmlStringEngine.setTemplateResolver((ITemplateResolver) stringResolver);
        textStringEngine = new TemplateEngine();
        StringTemplateResolver textStringResolver = new StringTemplateResolver();
        textStringResolver.setTemplateMode(TemplateMode.TEXT);
        textStringResolver.setResolvablePatterns(Set.of("*"));
        textStringResolver.setCacheable(false);
        textStringResolver.setOrder(3);
        textStringEngine.setTemplateResolver((ITemplateResolver) textStringResolver);
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
