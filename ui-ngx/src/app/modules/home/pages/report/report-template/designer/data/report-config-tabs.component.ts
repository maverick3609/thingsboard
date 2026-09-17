// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input } from '@angular/core';

export type ReportConfigTabId = 'content' | 'data' | 'layout';

// Shared Content/Data/Layout tab shell (design spec 2026-07-24-reporting-r2c-design.md) every C2
// data-component config panel (ENTITY/ALARM/TIME_SERIES tables, SUB_REPORT, ... - P3/P4) hosts its
// fields inside, and the C1 panels get retrofitted into one at a time - report-divider-config
// .component.html is the first (C2 Task 2): Content = length/borderType/widthPx/color, Layout =
// margins/paddings/backgroundBorder, no Data tab.
//
// Which <mat-tab> renders is driven purely by the `tabs` @Input() the host passes, not by
// inspecting whether content was actually projected into the matching named slot - simplest option
// that satisfies the design contract (spec's own "*ngIf-ed <mat-tab> with named ng-content slots"
// suggestion), so a caller must keep `tabs` in sync with which of [tabContent]/[tabData]/
// [tabLayout] it actually projects into, or an empty tab renders.
@Component({
  selector: 'tb-report-config-tabs',
  templateUrl: './report-config-tabs.component.html',
  styleUrls: ['./report-config-tabs.component.scss'],
  standalone: false
})
export class ReportConfigTabsComponent {

  @Input()
  tabs: ReportConfigTabId[] = [];

  hasTab(tab: ReportConfigTabId): boolean {
    return this.tabs?.includes(tab) ?? false;
  }
}
