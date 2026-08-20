/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
package org.thingsboard.server.service.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.dao.wl.WhiteLabelingService;
import org.thingsboard.server.exception.DataValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * White-labeled mail templates. The platform default for every template is the bundled
 * {@code templates/<name>.ftl} body plus the {@code messages.properties} subject; a system
 * administrator may override either through White labeling -> Mail templates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailTemplates {

    public static final String TEST = "test";
    public static final String ACTIVATION = "activation";
    public static final String ACCOUNT_ACTIVATED = "account.activated";
    public static final String ACCOUNT_LOCKOUT = "account.lockout";
    public static final String RESET_PASSWORD = "reset.password";
    public static final String PASSWORD_WAS_RESET = "password.was.reset";
    public static final String TWO_FA_VERIFICATION = "2fa.verification.code";
    public static final String API_USAGE_STATE_ENABLED = "state.enabled";
    public static final String API_USAGE_STATE_WARNING = "state.warning";
    public static final String API_USAGE_STATE_DISABLED = "state.disabled";

    private static final String SUBJECT = "subject";
    private static final String BODY = "body";

    /** Template name -> subject key in i18n/messages.properties. */
    private static final Map<String, String> SUBJECT_KEYS = new LinkedHashMap<>();

    static {
        SUBJECT_KEYS.put(TEST, "test.message.subject");
        SUBJECT_KEYS.put(ACTIVATION, "activation.subject");
        SUBJECT_KEYS.put(ACCOUNT_ACTIVATED, "account.activated.subject");
        SUBJECT_KEYS.put(ACCOUNT_LOCKOUT, "account.lockout.subject");
        SUBJECT_KEYS.put(RESET_PASSWORD, "reset.password.subject");
        SUBJECT_KEYS.put(PASSWORD_WAS_RESET, "password.was.reset.subject");
        SUBJECT_KEYS.put(TWO_FA_VERIFICATION, "2fa.verification.code.subject");
        SUBJECT_KEYS.put(API_USAGE_STATE_ENABLED, "api.usage.state");
        SUBJECT_KEYS.put(API_USAGE_STATE_WARNING, "api.usage.state");
        SUBJECT_KEYS.put(API_USAGE_STATE_DISABLED, "api.usage.state");
    }

    private final MessageSource messages;
    private final WhiteLabelingService whiteLabelingService;
    private final Configuration freemarkerConfig;

    /** Every template, stored overrides applied on top of the platform defaults. For the editing UI. */
    public ObjectNode getTemplates() {
        JsonNode stored = whiteLabelingService.getSystemMailTemplates();
        ObjectNode result = JacksonUtil.newObjectNode();
        SUBJECT_KEYS.keySet().forEach(name -> {
            ObjectNode template = result.putObject(name);
            template.put(SUBJECT, subject(name));
            template.put(BODY, body(name));
        });
        if (stored != null) {
            stored.fields().forEachRemaining(entry -> {
                ObjectNode target = (ObjectNode) result.get(entry.getKey());
                if (target != null) {
                    copyText(entry.getValue(), target, SUBJECT);
                    copyText(entry.getValue(), target, BODY);
                }
            });
        }
        return result;
    }

    public JsonNode save(JsonNode templates) {
        // parse every body up front: a template that only fails at send time would break
        // password reset / activation mail long after the admin left this page
        templates.fields().forEachRemaining(entry -> {
            JsonNode body = entry.getValue().get(BODY);
            if (body == null || !body.isTextual()) {
                return;
            }
            try {
                new Template(entry.getKey(), new StringReader(body.asText()), freemarkerConfig);
            } catch (Exception e) {
                throw new DataValidationException("Invalid mail template '" + entry.getKey() + "': "
                        + ExceptionUtils.getRootCauseMessage(e));
            }
        });
        return whiteLabelingService.saveSystemMailTemplates(templates);
    }

    /** Configured subject for the template, falling back to the bundled default. */
    public String subject(String name) {
        String override = override(name, SUBJECT);
        return override != null ? override : messages.getMessage(SUBJECT_KEYS.get(name), null, Locale.US);
    }

    /** Configured freemarker body for the template, falling back to the bundled {@code .ftl}. */
    public String body(String name) {
        String override = override(name, BODY);
        return override != null ? override : defaultBody(name);
    }

    private String override(String name, String field) {
        JsonNode stored = whiteLabelingService.getSystemMailTemplates();
        JsonNode template = stored != null ? stored.get(name) : null;
        JsonNode value = template != null ? template.get(field) : null;
        return value != null && value.isTextual() && !value.asText().isEmpty() ? value.asText() : null;
    }

    private String defaultBody(String name) {
        try (InputStream in = new ClassPathResource("templates/" + name + ".ftl").getInputStream()) {
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // drop the leading freemarker license comment - it is noise in the WYSIWYG editor
            return source.replaceFirst("(?s)^\\s*<#--.*?-->\\s*", "");
        } catch (IOException e) {
            log.error("Failed to read default mail template [{}]", name, e);
            return "";
        }
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isEmpty()) {
            target.put(field, value.asText());
        }
    }
}
