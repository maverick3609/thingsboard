// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { RuleNodeConfiguration, RuleNodeConfigurationComponent } from '@app/shared/models/rule-node.models';
import { EntityType } from '@shared/models/entity-type.models';
import { ReportService } from '@core/http/report.service';
import { ReportTemplateInfo } from '@shared/models/report.models';
import { ReportTemplateId } from '@shared/models/id/report-template-id';
import { PageLink } from '@shared/models/page/page-link';
import { emptyPageData } from '@shared/models/page/page-data';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';

// Config UI for the "generate report" (V2) rule node. Backend contract
// (TbGenerateReportV2NodeConfiguration): { useConfigFromMessage: boolean, config: ReportConfig }.
// The nested `config` group mirrors the scheduler generateReport form (ReportConfig, PE-verbatim);
// when useConfigFromMessage=true the ReportConfig is parsed from the incoming message at runtime, so
// the static sub-form is hidden and its required validators are dropped (log-config's scriptLang
// pattern).
@Component({
  selector: 'tb-action-node-generate-report-config',
  templateUrl: './generate-report-config.component.html',
  styleUrls: [],
  standalone: false
})
export class GenerateReportConfigComponent extends RuleNodeConfigurationComponent {

  entityType = EntityType;

  generateReportConfigForm: UntypedFormGroup;

  // tb-entity-select can't list REPORT_TEMPLATE (EntityService has no case for it), so a plain
  // mat-select backed directly by ReportService - mirrors scheduler-event-config 'report' mode.
  reportTemplates: ReportTemplateInfo[] = [];

  constructor(private fb: UntypedFormBuilder,
              private reportService: ReportService) {
    super();
  }

  protected configForm(): UntypedFormGroup {
    return this.generateReportConfigForm;
  }

  protected onConfigurationSet(configuration: RuleNodeConfiguration) {
    // configuration is already normalized to the form shape by prepareInputConfig (reportTemplateId
    // unwrapped to a bare uuid, defaults filled), so it is always a non-null {useConfigFromMessage, config}.
    const cfg = configuration.config;
    this.generateReportConfigForm = this.fb.group({
      useConfigFromMessage: [configuration.useConfigFromMessage, []],
      config: this.fb.group({
        reportTemplateId: [cfg.reportTemplateId, []],   // bare uuid; re-wrapped into a ReportTemplateId on emit
        userId: [cfg.userId, []],                        // full EntityId ({entityType:'USER', id}) via tb-entity-select
        timezone: [cfg.timezone, []],
        targets: [cfg.targets, []],                      // string[] of NOTIFICATION_TARGET uuids via tb-entity-list
        notificationTemplateId: [cfg.notificationTemplateId, []]   // full EntityId via tb-template-autocomplete, or null
      })
    });
    this.loadReportTemplates();
  }

  protected prepareInputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    // Normalize the persisted/output shape into the form shape: default a new node, and unwrap
    // reportTemplateId (a ReportTemplateId {entityType,id}) into the bare uuid the mat-select binds to.
    const c = configuration || {};
    const cfg = c.config || {};
    return {
      useConfigFromMessage: !!c.useConfigFromMessage,
      config: {
        reportTemplateId: cfg.reportTemplateId?.id || cfg.reportTemplateId || null,
        userId: cfg.userId || null,
        timezone: cfg.timezone || null,
        targets: cfg.targets || null,
        notificationTemplateId: cfg.notificationTemplateId || null
      }
    };
  }

  protected prepareOutputConfig(configuration: RuleNodeConfiguration): RuleNodeConfiguration {
    // Re-wrap the bare reportTemplateId uuid into a ReportTemplateId; userId/notificationTemplateId
    // are already full EntityId objects from their pickers. Matches ReportConfig.java deserialization.
    const cfg = configuration.config || {};
    return {
      useConfigFromMessage: !!configuration.useConfigFromMessage,
      config: {
        reportTemplateId: cfg.reportTemplateId ? new ReportTemplateId(cfg.reportTemplateId) : null,
        userId: cfg.userId || null,
        timezone: cfg.timezone || null,
        targets: cfg.targets || null,
        notificationTemplateId: cfg.notificationTemplateId || null
      }
    };
  }

  protected validatorTriggers(): string[] {
    return ['useConfigFromMessage'];
  }

  protected updateValidators(emitEvent: boolean) {
    // When the config comes from the message, the static ReportConfig is unused - drop its required
    // validators so the node validates. Otherwise reportTemplateId/userId/timezone are required
    // (mirrors the scheduler generateReport validate() rule).
    const useConfigFromMessage: boolean = this.generateReportConfigForm.get('useConfigFromMessage').value;
    const required = useConfigFromMessage ? [] : [Validators.required];
    for (const controlName of ['reportTemplateId', 'userId', 'timezone']) {
      const control = this.generateReportConfigForm.get(['config', controlName]);
      control.setValidators(required);
      control.updateValueAndValidity({emitEvent});
    }
  }

  private loadReportTemplates(): void {
    this.reportService.getReportTemplateInfos(new PageLink(100), {}, {ignoreLoading: true}).pipe(
      catchError(err => {
        console.error('[Reporting] Failed to load report templates', err);
        return of(emptyPageData<ReportTemplateInfo>());
      })
    ).subscribe(pageData => this.reportTemplates = pageData.data);
  }
}
