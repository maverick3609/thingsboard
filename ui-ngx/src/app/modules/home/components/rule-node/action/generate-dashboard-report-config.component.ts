///
/// Copyright © 2016-2026 The Inferrix Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Component } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { RuleNodeConfiguration, RuleNodeConfigurationComponent } from '@app/shared/models/rule-node.models';
import { EntityType } from '@shared/models/entity-type.models';

// Config UI for the "generate dashboard report" rule node. Backend contract
// (TbGenerateReportNodeConfiguration): { useReportConfigFromMessage: boolean, reportConfig: DashboardReportConfig }.
//
// useReportConfigFromMessage defaults ON because the dominant producer is a 'generateDashboardReport'
// scheduler event, which carries its own DashboardReportConfig in the message body; the static
// sub-form below is for chains that render the same dashboard on every message. Same
// hide-and-drop-validators pattern as the V2 "generate report" node.
//
// PE's useSystemReportsServer / reportsServerEndpointUrl fields are absent: this fork renders
// in-process (no external tb-web-report server to point at). useCurrentUserCredentials and `type`
// are absent too - the renderer always mints credentials from the configured user, and only PDF
// dashboard reports can be stored (see DefaultDashboardReportService#toFormat), so neither control
// would change anything. baseUrl is absent for the same reason: the service pins it to the
// configured reports.renderer.base_url rather than trusting the config or the message.
@Component({
  selector: 'tb-action-node-generate-dashboard-report-config',
  templateUrl: './generate-dashboard-report-config.component.html',
  styleUrls: [],
  standalone: false
})
export class GenerateDashboardReportConfigComponent extends RuleNodeConfigurationComponent {

  entityType = EntityType;

  generateDashboardReportConfigForm: UntypedFormGroup;

  constructor(private fb: UntypedFormBuilder) {
    super();
  }

  protected configForm(): UntypedFormGroup {
    return this.generateDashboardReportConfigForm;
  }

  protected onConfigurationSet(configuration: RuleNodeConfiguration) {
    const cfg = configuration.reportConfig;
    this.generateDashboardReportConfigForm = this.fb.group({
      useReportConfigFromMessage: [configuration.useReportConfigFromMessage, []],
      reportConfig: this.fb.group({
        dashboardId: [cfg.dashboardId, []],      // bare uuid - tb-dashboard-autocomplete's useIdValue shape
        state: [cfg.state, []],
        timezone: [cfg.timezone, []],
        useDashboardTimewindow: [cfg.useDashboardTimewindow, []],
        timewindow: [cfg.timewindow, []],
        namePattern: [cfg.namePattern, []],
        userId: [cfg.userId, []]                 // full EntityId for tb-entity-select; unwrapped to a bare uuid on emit
      })
    });
  }

  protected prepareInputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    const c = configuration || {};
    const cfg = c.reportConfig || {};
    return {
      // A node created before this component existed has no flag at all; default it ON to match
      // TbGenerateReportNodeConfiguration#defaultConfiguration rather than silently flipping to
      // "use the (empty) static config".
      useReportConfigFromMessage: c.useReportConfigFromMessage !== false,
      reportConfig: {
        dashboardId: cfg.dashboardId || null,
        state: cfg.state || null,
        timezone: cfg.timezone || null,
        useDashboardTimewindow: cfg.useDashboardTimewindow !== false,
        timewindow: cfg.timewindow || null,
        namePattern: cfg.namePattern || null,
        // DashboardReportConfig.userId is a bare uuid string; tb-entity-select binds an EntityId, so
        // wrap on the way in and unwrap on the way out (same shape dance the V2 node does for
        // reportTemplateId).
        userId: cfg.userId ? {entityType: EntityType.USER, id: cfg.userId} : null
      }
    };
  }

  protected prepareOutputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    const cfg = configuration.reportConfig || {};
    return {
      useReportConfigFromMessage: !!configuration.useReportConfigFromMessage,
      reportConfig: {
        dashboardId: cfg.dashboardId || null,
        state: cfg.state || null,
        timezone: cfg.timezone || null,
        useDashboardTimewindow: !!cfg.useDashboardTimewindow,
        // Only meaningful when the dashboard's own window is not used; drop it otherwise so the
        // persisted config doesn't carry a stale window the backend would ignore anyway.
        timewindow: cfg.useDashboardTimewindow ? null : (cfg.timewindow || null),
        namePattern: cfg.namePattern || null,
        userId: cfg.userId?.id || cfg.userId || null
      }
    };
  }

  protected validatorTriggers(): string[] {
    return ['useReportConfigFromMessage'];
  }

  protected updateValidators(emitEvent: boolean) {
    // Taking the config from the message means the static sub-form is unused - drop its required
    // validators so the node validates while hidden.
    const fromMessage: boolean = this.generateDashboardReportConfigForm.get('useReportConfigFromMessage').value;
    const required = fromMessage ? [] : [Validators.required];
    for (const controlName of ['dashboardId', 'namePattern', 'userId']) {
      const control = this.generateDashboardReportConfigForm.get(['reportConfig', controlName]);
      control.setValidators(required);
      control.updateValueAndValidity({emitEvent});
    }
  }
}
