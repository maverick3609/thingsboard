// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@home/components/home-components.module';
import { HomeDialogsModule } from '@home/dialogs/home-dialogs.module';
import { InferrixRoutingModule } from '@home/pages/inferrix/inferrix-routing.module';
import { AdoptControllerDialogComponent } from '@home/pages/inferrix/adopt-controller-dialog.component';
import { AssignControllerDialogComponent } from '@home/pages/inferrix/assign-controller-dialog.component';
import { ControllerComponent } from '@home/pages/inferrix/controller/controller.component';
import { ControllerTabsComponent } from '@home/pages/inferrix/controller/controller-tabs.component';
import { ControllerPointsComponent } from '@home/pages/inferrix/controller/controller-points.component';
import { ControllerTemplateDialogComponent } from '@home/pages/inferrix/controller/controller-template-dialog.component';
import { ControllerSettingsComponent } from '@home/pages/inferrix/controller/controller-settings.component';
import { ControllerDiagnosticsComponent } from '@home/pages/inferrix/controller/controller-diagnostics.component';
import { ControllerConfigComponent } from '@home/pages/inferrix/controller/controller-config.component';
import { ControllerConfigRecordDialogComponent }
  from '@home/pages/inferrix/controller/controller-config-record-dialog.component';
import { ControllerSoftwareComponent } from '@home/pages/inferrix/controller/controller-software.component';
import { ControllerLogicComponent } from '@home/pages/inferrix/controller/controller-logic.component';
import { ControllerStatementsComponent } from '@home/pages/inferrix/controller/controller-statements.component';
import { ControllerExpressionComponent } from '@home/pages/inferrix/controller/controller-expression.component';
import { ControllerTuneComponent } from '@home/pages/inferrix/controller/controller-tune.component';
import { ControllerPointWriteDialogComponent }
  from '@home/pages/inferrix/controller/controller-point-write-dialog.component';
import { AdoptGatewayDialogComponent } from '@home/pages/inferrix/adopt-gateway-dialog.component';
import { GatewayComponent } from '@home/pages/inferrix/gateway/gateway.component';
import { GatewayConnectionDialogComponent }
  from '@home/pages/inferrix/gateway/gateway-connection-dialog.component';
import { GatewayBrokerDialogComponent }
  from '@home/pages/inferrix/gateway/gateway-broker-dialog.component';
import { GatewayTabsComponent } from '@home/pages/inferrix/gateway/gateway-tabs.component';
import { GatewayHealthComponent } from '@home/pages/inferrix/gateway/gateway-health.component';
import { GatewayDataSourcesComponent }
  from '@home/pages/inferrix/gateway/gateway-data-sources.component';
import { GatewayPublishersComponent }
  from '@home/pages/inferrix/gateway/gateway-publishers.component';
import { GatewayProvisioningComponent }
  from '@home/pages/inferrix/gateway/gateway-provisioning.component';
import { GatewayFormComponent } from '@home/pages/inferrix/gateway/gateway-form.component';
import { VirtualPointFormComponent } from '@home/pages/inferrix/gateway/virtual-point-form.component';
import { BacnetDataSourceFormComponent }
  from '@home/pages/inferrix/gateway/bacnet-data-source-form.component';
import { BacnetPointFormComponent }
  from '@home/pages/inferrix/gateway/bacnet-point-form.component';
import { GatewayModelDialogComponent }
  from '@home/pages/inferrix/gateway/gateway-model-dialog.component';
import { GatewayRecipientsComponent }
  from '@home/pages/inferrix/gateway/gateway-recipients.component';
import { GatewayDetectorsDialogComponent }
  from '@home/pages/inferrix/gateway/gateway-detectors-dialog.component';
import { GatewayEventsComponent } from '@home/pages/inferrix/gateway/gateway-events.component';
import { GatewayEventHandlersComponent }
  from '@home/pages/inferrix/gateway/gateway-event-handlers.component';
import { GatewaySchedulesComponent }
  from '@home/pages/inferrix/gateway/gateway-schedules.component';
import { GatewayScheduleDialogComponent }
  from '@home/pages/inferrix/gateway/gateway-schedule-dialog.component';
import { GatewayRuleSetsComponent }
  from '@home/pages/inferrix/gateway/gateway-rule-sets.component';
import { GatewayRuleSetDialogComponent }
  from '@home/pages/inferrix/gateway/gateway-rule-set-dialog.component';
import { GatewaySystemComponent } from '@home/pages/inferrix/gateway/gateway-system.component';
import { GatewayAlertListsComponent }
  from '@home/pages/inferrix/gateway/gateway-alert-lists.component';
import { WidgetSettingsCommonModule }
  from '@home/components/widget/lib/settings/common/widget-settings-common.module';

@NgModule({
  declarations: [
    AdoptControllerDialogComponent,
    AssignControllerDialogComponent,
    ControllerComponent,
    ControllerTabsComponent,
    ControllerPointsComponent,
    ControllerTemplateDialogComponent,
    ControllerSettingsComponent,
    ControllerDiagnosticsComponent,
    ControllerConfigComponent,
    ControllerConfigRecordDialogComponent,
    ControllerSoftwareComponent,
    ControllerLogicComponent,
    ControllerStatementsComponent,
    ControllerExpressionComponent,
    ControllerTuneComponent,
    ControllerPointWriteDialogComponent,
    AdoptGatewayDialogComponent,
    GatewayComponent,
    GatewayConnectionDialogComponent,
    GatewayBrokerDialogComponent,
    GatewayTabsComponent,
    GatewayHealthComponent,
    GatewayDataSourcesComponent,
    GatewayPublishersComponent,
    GatewayProvisioningComponent,
    GatewayFormComponent,
    VirtualPointFormComponent,
    BacnetDataSourceFormComponent,
    BacnetPointFormComponent,
    GatewayModelDialogComponent,
    GatewayRecipientsComponent,
    GatewayDetectorsDialogComponent,
    GatewayEventsComponent,
    GatewayEventHandlersComponent,
    GatewayAlertListsComponent,
    GatewaySchedulesComponent,
    GatewayScheduleDialogComponent,
    GatewayRuleSetsComponent,
    GatewayRuleSetDialogComponent,
    GatewaySystemComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    HomeDialogsModule,
    // For tb-dynamic-form: a gateway's data source and point forms are built from the schema the
    // device publishes, so the renderer is TB's own rather than a form per protocol module.
    WidgetSettingsCommonModule,
    InferrixRoutingModule
  ]
})
export class InferrixModule { }
