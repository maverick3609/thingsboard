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

import com.fasterxml.jackson.databind.node.ObjectNode;
import freemarker.template.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.dao.wl.WhiteLabelingService;
import org.thingsboard.server.exception.DataValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailTemplatesTest {

    private WhiteLabelingService whiteLabelingService;
    private MailTemplates mailTemplates;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("classpath:/i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        whiteLabelingService = mock(WhiteLabelingService.class);
        mailTemplates = new MailTemplates(messages, whiteLabelingService,
                new Configuration(Configuration.VERSION_2_3_32));
    }

    @Test
    void givenNoOverrides_whenGetTemplates_thenPlatformDefaultsAreReturned() {
        when(whiteLabelingService.getSystemMailTemplates()).thenReturn(null);

        ObjectNode templates = mailTemplates.getTemplates();

        assertThat(templates.size()).isEqualTo(10);
        assertThat(templates.get(MailTemplates.TEST).get("subject").asText())
                .isEqualTo("Test message from Inferrix Cortex");
        assertThat(templates.get(MailTemplates.TEST).get("body").asText())
                .doesNotContain("<#--")
                .contains("${targetEmail}");
        assertThat(mailTemplates.subject(MailTemplates.ACTIVATION))
                .isEqualTo("Your account activation on Inferrix Cortex");
    }

    @Test
    void givenPartialOverride_whenGetTemplates_thenOnlyOverriddenFieldsWin() {
        ObjectNode stored = JacksonUtil.newObjectNode();
        stored.putObject(MailTemplates.TEST).put("subject", "Test message from Inferrix");
        stored.putObject("no.such.template").put("subject", "ignored");
        when(whiteLabelingService.getSystemMailTemplates()).thenReturn(stored);

        ObjectNode templates = mailTemplates.getTemplates();

        assertThat(templates.has("no.such.template")).isFalse();
        assertThat(mailTemplates.subject(MailTemplates.TEST)).isEqualTo("Test message from Inferrix");
        // body was not overridden, so the bundled template still applies
        assertThat(mailTemplates.body(MailTemplates.TEST)).contains("${targetEmail}");
        // an untouched template keeps both defaults
        assertThat(mailTemplates.subject(MailTemplates.ACCOUNT_LOCKOUT))
                .isEqualTo("Inferrix Cortex - User account has been lockout");
    }

    @Test
    void givenNoOverrides_whenGetTemplates_thenNothingNamesTheUpstreamProduct() {
        when(whiteLabelingService.getSystemMailTemplates()).thenReturn(null);

        ObjectNode templates = mailTemplates.getTemplates();

        // The bundled .ftl bodies and their subjects are what a recipient sees; a merge that
        // restores an upstream template would silently put the upstream brand — and an image
        // fetched from its CDN — back into outgoing mail.
        templates.fields().forEachRemaining(entry -> {
            String subject = entry.getValue().get("subject").asText();
            String body = entry.getValue().get("body").asText();
            assertThat(subject).as("subject of %s", entry.getKey()).doesNotContainIgnoringCase("thingsboard");
            assertThat(body).as("body of %s", entry.getKey()).doesNotContainIgnoringCase("thingsboard");
        });
    }

    @Test
    void givenUnparseableBody_whenSaving_thenRejectedUpFront() {
        ObjectNode broken = JacksonUtil.newObjectNode();
        broken.putObject(MailTemplates.TEST).put("body", "hello ${unclosed");

        assertThatThrownBy(() -> mailTemplates.save(broken))
                .isInstanceOf(DataValidationException.class)
                .hasMessageContaining(MailTemplates.TEST);
    }

    @Test
    void givenBlankOverride_whenResolving_thenDefaultApplies() {
        ObjectNode stored = JacksonUtil.newObjectNode();
        stored.putObject(MailTemplates.TEST).put("subject", "").put("body", "");
        when(whiteLabelingService.getSystemMailTemplates()).thenReturn(stored);

        assertThat(mailTemplates.subject(MailTemplates.TEST)).isEqualTo("Test message from Inferrix Cortex");
        assertThat(mailTemplates.body(MailTemplates.TEST)).contains("${targetEmail}");
    }
}
