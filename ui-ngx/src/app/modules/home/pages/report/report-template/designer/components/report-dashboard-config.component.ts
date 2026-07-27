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

import { Component, forwardRef, Input, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { DashboardComponent, ImageAlignment, ImageWidthType } from '@shared/models/report-configuration.models';
import { DashboardReportConfig } from '@shared/models/report.models';
import { IAliasController } from '@core/api/widget-api.models';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';

// CVA for components/DashboardComponent.java - the shell's right-panel content when the selected
// canvas component is DASHBOARD (report-template-editor.component.html). Mirrors T11's
// ReportTableConfigComponent/T13's ReportSubReportConfigComponent shell-wiring pattern (hasConfigPanel
// + *ngIf branch + the shell's shared aliasController field). `config` (DashboardReportConfig,
// report.models.ts) is the on-demand-dashboard-report body the renderer feeds into R1's Playwright
// dashboard-image path; margins/paddings/background/borderWidth/borderRadius/borderColor
// (ReportComponentLayout) and widthType/customWidth/alignment (ReportImageSizing,
// components/AbstractImageComponent.java - shared with ImageComponent, so this panel's Layout tab
// inlines the same 3 controls report-image-config.component.html does verbatim rather than a factored
// sub-editor, since C1 never factored one out) sit alongside `config` at this component's OWN top
// level, not inside it - flattened back on the way out exactly as backgroundBorder is elsewhere in
// this family. `dataSources` (ReportComponentDataSources) is a live tb-report-datasources control,
// same composition as T11/T13.
//
// ============================================================================================
// SECURITY BOUND - the entire point of this panel (read twice; this is R2a's CRITICAL finding
// replicated here as FE defense-in-depth, not a new discovery).
//
// The report renderer drives headless Chromium to embed a dashboard per DashboardReportConfig. Three
// of its 10 fields must NEVER be settable by a report author through this UI:
//  - baseUrl                   -> SSRF / auth-token exfiltration (R2a's CRITICAL finding; the server
//                                  already strips a per-component baseUrl override - this panel must
//                                  not even surface or persist one, so there is nothing to strip).
//  - userId                    -> render-as-arbitrary-user identity spoof.
//  - useCurrentUserCredentials -> credential-context override.
//
// toConfig() below builds the emitted `config` by ALLOWLIST (default-deny): a FRESH object literal
// naming only the fields this panel is willing to emit. It is NEVER built by spreading
// `incomingConfig` (`{...incomingConfig, ...edits}` would carry baseUrl/userId/
// useCurrentUserCredentials straight through untouched if a legacy/malicious config ever had them -
// exactly the failure mode this panel exists to close). baseUrl/userId/useCurrentUserCredentials are
// simply never referenced by name anywhere in this file, so there is no code path - correct or
// buggy - by which they could reach the emitted DashboardComponent: the absence is structural, not
// the result of an if-check that could be inverted or forgotten.
//
// `type` (drives R2a's Playwright-PNG render path - DASHBOARD reuses R1's dashboard-image path
// requesting PNG; PE's own default dashboard config is `{type:'png'}`) and the optional
// `timezone`/`namePattern` are the 3 SAFE passthrough fields this panel has no editor for but must
// not drop - writeValue() stashes the incoming config (`incomingConfig`) purely so toConfig() can
// read those 3 named fields off it on every subsequent edit; nothing else about the stashed object
// is ever read.
// ============================================================================================
@Component({
    selector: 'tb-report-dashboard-config',
    templateUrl: './report-dashboard-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportDashboardConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportDashboardConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  dashFormGroup: UntypedFormGroup;

  disabled = false;

  widthTypeEnum = ImageWidthType;
  alignmentEnum = ImageAlignment;

  // Forwarded to tb-report-datasources - see Task 9's createReportAliasController (created once by
  // the shell in ngOnInit, threaded down here the same way Task 11/13's panels receive it).
  @Input()
  aliasController: IAliasController;

  // Stashed on writeValue() - see the class-level SECURITY BOUND comment. Read ONLY by toConfig(),
  // and ONLY for its `type`/`timezone`/`namePattern` fields; never spread wholesale.
  private incomingConfig: DashboardReportConfig | undefined;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: DashboardComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.dashFormGroup = this.fb.group({
      dashboardId: [null],
      state: [null],
      timewindow: [null],
      useDashboardTimewindow: [null],
      dataSources: [null],
      widthType: [null],
      customWidth: [null],
      alignment: [null],
      margins: [null],
      paddings: [null],
      backgroundBorder: [{ background: null, borderWidth: null, borderRadius: null, borderColor: null }]
    });
    this.dashFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toComponent(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: DashboardComponent) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.dashFormGroup.disable({emitEvent: false});
    } else {
      this.dashFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(component: DashboardComponent): void {
    const c = component ?? ({} as DashboardComponent);
    const config: DashboardReportConfig = c.config ?? {};
    this.incomingConfig = c.config;
    this.dashFormGroup.reset({
      dashboardId: config.dashboardId ?? null,
      state: config.state ?? null,
      timewindow: config.timewindow ?? null,
      useDashboardTimewindow: config.useDashboardTimewindow ?? null,
      dataSources: c.dataSources ?? null,
      widthType: c.widthType ?? null,
      customWidth: c.customWidth ?? null,
      alignment: c.alignment ?? null,
      margins: c.margins ?? null,
      paddings: c.paddings ?? null,
      backgroundBorder: {
        background: c.background ?? null,
        borderWidth: c.borderWidth ?? null,
        borderRadius: c.borderRadius ?? null,
        borderColor: c.borderColor ?? null
      }
    }, {emitEvent: false});
  }

  // SECURITY BOUND - see class header. Builds `config` by ALLOWLIST: every key this returns is
  // named explicitly; baseUrl/userId/useCurrentUserCredentials are never referenced, so they cannot
  // appear in the result regardless of what `incoming` (the last writeValue()'d config, which may be
  // attacker- or legacy-supplied) contains. Never spread `incoming`.
  private toConfig(fv: any, incoming: DashboardReportConfig | undefined): DashboardReportConfig {
    return {
      type: incoming?.type ?? 'png',
      ...(incoming?.timezone != null ? { timezone: incoming.timezone } : {}),
      ...(incoming?.namePattern != null ? { namePattern: incoming.namePattern } : {}),
      dashboardId: fv.dashboardId,
      state: fv.state,
      timewindow: fv.timewindow,
      useDashboardTimewindow: fv.useDashboardTimewindow,
    };
  }

  private toComponent(value: any): DashboardComponent {
    const backgroundBorder: ReportBackgroundBorder = value.backgroundBorder ?? {};
    return {
      type: 'DASHBOARD',
      config: this.toConfig(value, this.incomingConfig),
      dataSources: value.dataSources,
      widthType: value.widthType,
      customWidth: value.customWidth ?? undefined,
      alignment: value.alignment,
      margins: value.margins,
      paddings: value.paddings,
      background: backgroundBorder.background,
      borderWidth: backgroundBorder.borderWidth,
      borderRadius: backgroundBorder.borderRadius,
      borderColor: backgroundBorder.borderColor
    };
  }
}
