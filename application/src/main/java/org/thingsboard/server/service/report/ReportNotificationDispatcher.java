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
package org.thingsboard.server.service.report;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.rule.engine.api.NotificationCenter;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.NotificationRequestConfig;
import org.thingsboard.server.common.data.notification.NotificationType;
import org.thingsboard.server.common.data.notification.info.ReportGeneratedNotificationInfo;
import org.thingsboard.server.common.data.notification.template.EmailDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.notification.template.NotificationTemplate;
import org.thingsboard.server.common.data.notification.template.NotificationTemplateConfig;
import org.thingsboard.server.common.data.notification.template.WebDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.util.CollectionsUtil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers a finished {@link Report} to notification targets. Shared by the two things that finish a
 * report: {@link ReportJobProcessor#onJobFinished} (templated reports, via the job framework) and
 * {@code InferrixSchedulerReportExecutor#executeDashboardReport} (dashboard reports, rendered
 * directly off a fired scheduler event) — one delivery contract for both, so "my scheduled report
 * didn't arrive" has one place to look regardless of which kind it was.
 * <p>
 * Wires unconditionally (no {@code reports.renderer.enabled} guard) because it only talks to the
 * notification center; both callers are themselves renderer-gated.
 */
@Component
@Slf4j
public class ReportNotificationDispatcher {

    private final NotificationCenter notificationCenter;

    public ReportNotificationDispatcher(@Lazy NotificationCenter notificationCenter) {
        this.notificationCenter = notificationCenter;
    }

    /**
     * Best-effort: never throws. A delivery failure must not fail the report that was already
     * rendered and stored — but it is always logged at ERROR with the report id.
     *
     * @param context short label naming the caller ("job <id>", "scheduler event <id>") so the log
     *                line says which firing this was without the caller formatting its own messages
     */
    public void dispatch(TenantId tenantId, Report report, List<UUID> targets,
                         NotificationTemplateId notificationTemplateId, String context) {
        if (CollectionsUtil.isEmpty(targets)) {
            // Targets are the one thing we cannot supply a sane default for. Log it loudly: a silent
            // skip here is exactly what "the report ran but nobody got it" looks like from outside.
            log.warn("[{}][{}] Report [{}] was generated but has no notification targets, so it will not be " +
                    "delivered to anyone. Add at least one notification target to the scheduled report.",
                    tenantId, context, report.getId());
            return;
        }

        NotificationRequestConfig requestConfig = new NotificationRequestConfig();
        requestConfig.setReports(List.of(report.getId()));
        NotificationRequest.NotificationRequestBuilder request = NotificationRequest.builder()
                .tenantId(tenantId)
                .targets(targets)
                .originatorEntityId(report.getUserId())
                .info(ReportGeneratedNotificationInfo.builder()
                        .tenantId(tenantId)
                        .customerId(report.getCustomerId())
                        .reportFormat(report.getFormat())
                        .reportName(report.getName())
                        .userId(report.getUserId())
                        .build())
                .additionalConfig(requestConfig);
        if (notificationTemplateId != null) {
            request.templateId(notificationTemplateId);
        } else {
            // No template picked: deliver with a built-in one instead of dropping the report. The
            // notification center accepts an inline template (DefaultNotificationCenter reads
            // request.getTemplate() whenever templateId is null), so this needs no stored entity and
            // therefore works for tenants that predate the feature.
            request.template(defaultReportTemplate());
        }

        try {
            notificationCenter.processNotificationRequest(tenantId, request.build(), null);
            log.info("[{}][{}] Dispatched report-generated notification for report [{}]", tenantId, context, report.getId());
        } catch (Exception e) {
            log.error("[{}][{}] Failed to dispatch report-generated notification for report [{}]", tenantId, context, report.getId(), e);
        }
    }

    /**
     * Fallback notification template used when a scheduled report names notification targets but no
     * template. Placeholders come from {@link ReportGeneratedNotificationInfo#getTemplateData()}; the
     * rendered report itself rides along as an email attachment via {@link
     * NotificationRequestConfig#setReports} (EmailNotificationChannel -> DefaultMailService), so the
     * body only has to explain what is attached.
     */
    private static NotificationTemplate defaultReportTemplate() {
        WebDeliveryMethodNotificationTemplate web = new WebDeliveryMethodNotificationTemplate();
        web.setSubject("Report generated");
        web.setBody("Report '${reportName}' (${reportFormat}) has been generated.");
        web.setEnabled(true);

        EmailDeliveryMethodNotificationTemplate email = new EmailDeliveryMethodNotificationTemplate();
        email.setSubject("Report '${reportName}'");
        email.setBody("Report '${reportName}' (${reportFormat}) is attached.");
        email.setEnabled(true);

        NotificationTemplateConfig config = new NotificationTemplateConfig();
        config.setDeliveryMethodsTemplates(Map.of(
                NotificationDeliveryMethod.WEB, web,
                NotificationDeliveryMethod.EMAIL, email));

        NotificationTemplate template = new NotificationTemplate();
        template.setName("Report generated");
        template.setNotificationType(NotificationType.GENERAL);
        template.setConfiguration(config);
        return template;
    }

}
