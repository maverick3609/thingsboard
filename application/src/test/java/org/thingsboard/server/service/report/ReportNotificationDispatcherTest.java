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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.rule.engine.api.NotificationCenter;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.info.ReportGeneratedNotificationInfo;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.TbReportFormat;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The single delivery contract shared by both report paths — {@link ReportJobProcessor} (templated
 * reports via the job framework) and {@code InferrixSchedulerReportExecutor} (dashboard reports off
 * a fired scheduler event).
 */
@ExtendWith(MockitoExtension.class)
class ReportNotificationDispatcherTest {

    @Mock
    private NotificationCenter notificationCenter;

    private ReportNotificationDispatcher dispatcher;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());
    private final ReportId reportId = new ReportId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        dispatcher = new ReportNotificationDispatcher(notificationCenter);
    }

    private Report report() {
        Report report = new Report(reportId);
        report.setTenantId(tenantId);
        report.setCustomerId(customerId);
        report.setUserId(userId);
        report.setFormat(TbReportFormat.PDF);
        report.setName("weekly.pdf");
        return report;
    }

    @Test
    void buildsARequestCarryingTheReportItsTargetsAndTheChosenTemplate() {
        NotificationTemplateId templateId = new NotificationTemplateId(UUID.randomUUID());
        UUID target = UUID.randomUUID();

        dispatcher.dispatch(tenantId, report(), List.of(target), templateId, "job-1");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationCenter).processNotificationRequest(eq(tenantId), captor.capture(), any());
        NotificationRequest request = captor.getValue();
        assertThat(request.getAdditionalConfig().getReports()).containsExactly(reportId);
        assertThat(request.getTargets()).containsExactly(target);
        assertThat(request.getTemplateId()).isEqualTo(templateId);
        assertThat(request.getOriginatorEntityId()).isEqualTo(userId);
        ReportGeneratedNotificationInfo info = (ReportGeneratedNotificationInfo) request.getInfo();
        assertThat(info.getTenantId()).isEqualTo(tenantId);
        assertThat(info.getCustomerId()).isEqualTo(customerId);
        assertThat(info.getReportFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(info.getReportName()).isEqualTo("weekly.pdf");
        assertThat(info.getUserId()).isEqualTo(userId);
    }

    @Test
    void fallsBackToABuiltInTemplateWhenNoneChosen() {
        dispatcher.dispatch(tenantId, report(), List.of(UUID.randomUUID()), null, "job-1");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationCenter).processNotificationRequest(eq(tenantId), captor.capture(), any());
        NotificationRequest request = captor.getValue();
        assertThat(request.getTemplateId()).isNull();
        // DefaultNotificationCenter reads request.getTemplate() when templateId is null, so the report
        // is still delivered rather than silently dropped.
        assertThat(request.getTemplate()).isNotNull();
        assertThat(request.getTemplate().getConfiguration().getDeliveryMethodsTemplates())
                .containsOnlyKeys(NotificationDeliveryMethod.WEB, NotificationDeliveryMethod.EMAIL);
        assertThat(request.getTemplate().getConfiguration()
                .getDeliveryMethodsTemplates().get(NotificationDeliveryMethod.EMAIL).isEnabled()).isTrue();
    }

    @Test
    void doesNothingWhenThereAreNoTargets() {
        dispatcher.dispatch(tenantId, report(), List.of(), null, "job-1");
        dispatcher.dispatch(tenantId, report(), null, null, "job-2");

        verify(notificationCenter, never()).processNotificationRequest(any(), any(), any());
    }

    @Test
    void swallowsDeliveryFailuresSoAStoredReportIsNeverLostToAMailProblem() {
        doThrow(new IllegalArgumentException("No delivery methods to send notification with"))
                .when(notificationCenter).processNotificationRequest(any(), any(), any());

        assertThatCode(() -> dispatcher.dispatch(tenantId, report(), List.of(UUID.randomUUID()), null, "job-1"))
                .doesNotThrowAnyException();
    }

}
