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
package org.thingsboard.rule.engine.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.DirectListeningExecutor;
import org.thingsboard.rule.engine.api.DashboardReportService;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class TbGenerateReportNodeTest {

    private static final String DASHBOARD_ID = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final DeviceId originator = new DeviceId(UUID.randomUUID());
    private final ReportId reportId = new ReportId(UUID.randomUUID());

    private TbGenerateReportNode node;
    private TbGenerateReportNodeConfiguration config;

    @Mock
    private TbContext ctx;
    @Mock
    private DashboardReportService dashboardReportService;

    @BeforeEach
    void setUp() {
        node = new TbGenerateReportNode();
        config = new TbGenerateReportNodeConfiguration().defaultConfiguration();
        ReflectionTestUtils.setField(node, "config", config);
    }

    private TbMsg newMsg(String data, TbMsgMetaData metaData) {
        return TbMsg.newMsg()
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(originator)
                .copyMetaData(metaData)
                .queueName("Main")
                .data(data)
                .build();
    }

    /** The exact body a {@code generateDashboardReport} scheduler event pushes: msgBody.reportConfig. */
    private String schedulerEventBody() {
        return "{\"reportConfig\":{\"baseUrl\":\"https://cortex.inferrix.com\",\"dashboardId\":\"" + DASHBOARD_ID +
                "\",\"state\":\"s\",\"timezone\":\"Asia/Calcutta\",\"useDashboardTimewindow\":true," +
                "\"namePattern\":\"weekly\",\"type\":\"pdf\",\"useCurrentUserCredentials\":false,\"userId\":\"" + USER_ID + "\"}}";
    }

    private Report savedReport() {
        Report report = new Report(reportId);
        report.setTenantId(tenantId);
        return report;
    }

    @Test
    void givenSchedulerEventBody_whenOnMsg_thenRendersAndStampsReportId() {
        given(ctx.getTenantId()).willReturn(tenantId);
        given(ctx.getDashboardReportService()).willReturn(dashboardReportService);
        given(ctx.getExternalCallExecutor()).willReturn(DirectListeningExecutor.INSTANCE);
        given(dashboardReportService.generateAndSaveReport(eq(tenantId), any(DashboardReportConfig.class)))
                .willReturn(savedReport());

        node.onMsg(ctx, newMsg(schedulerEventBody(), TbMsgMetaData.EMPTY));

        ArgumentCaptor<DashboardReportConfig> configCaptor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        then(dashboardReportService).should().generateAndSaveReport(eq(tenantId), configCaptor.capture());
        DashboardReportConfig used = configCaptor.getValue();
        assertThat(used.getDashboardId()).isEqualTo(DASHBOARD_ID);
        assertThat(used.getUserId()).isEqualTo(USER_ID);
        assertThat(used.isUseDashboardTimewindow()).isTrue();

        ArgumentCaptor<TbMsg> msgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctx).should().tellSuccess(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getMetaData().getValue("reports")).isEqualTo(reportId.getId().toString());
    }

    @Test
    void givenExistingReportsMetadata_whenOnMsg_thenAppendsRatherThanOverwrites() {
        String existing = UUID.randomUUID().toString();
        given(ctx.getTenantId()).willReturn(tenantId);
        given(ctx.getDashboardReportService()).willReturn(dashboardReportService);
        given(ctx.getExternalCallExecutor()).willReturn(DirectListeningExecutor.INSTANCE);
        given(dashboardReportService.generateAndSaveReport(any(), any())).willReturn(savedReport());

        node.onMsg(ctx, newMsg(schedulerEventBody(), new TbMsgMetaData(Map.of("reports", existing))));

        ArgumentCaptor<TbMsg> msgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctx).should().tellSuccess(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getMetaData().getValue("reports")).isEqualTo(existing + "," + reportId.getId());
    }

    @Test
    void givenRendererDisabled_whenOnMsg_thenFailsWithoutRendering() {
        given(ctx.getDashboardReportService()).willReturn(null);

        node.onMsg(ctx, newMsg(schedulerEventBody(), TbMsgMetaData.EMPTY));

        then(ctx).should().tellFailure(any(TbMsg.class), any(IllegalStateException.class));
        then(ctx).should(never()).tellSuccess(any());
    }

    @Test
    void givenMessageWithoutReportConfig_whenOnMsg_thenFails() {
        node.onMsg(ctx, newMsg(TbMsg.EMPTY_JSON_OBJECT, TbMsgMetaData.EMPTY));

        then(ctx).should().tellFailure(any(TbMsg.class), any(Throwable.class));
        then(dashboardReportService).shouldHaveNoInteractions();
    }

    @Test
    void givenStaticConfigAndNoneSet_whenOnMsg_thenFails() {
        config.setUseReportConfigFromMessage(false);
        config.setReportConfig(null);

        node.onMsg(ctx, newMsg(schedulerEventBody(), TbMsgMetaData.EMPTY));

        then(ctx).should().tellFailure(any(TbMsg.class), any(IllegalArgumentException.class));
        then(dashboardReportService).shouldHaveNoInteractions();
    }

}
