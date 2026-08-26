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

import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@home/components/home-components.module';
import { WidgetConfigComponentsModule } from '@home/components/widget/config/widget-config-components.module';
import { ReportRoutingModule } from '@home/pages/report/report-routing.module';
import { ReportHistoryComponent } from '@home/pages/report/report-history/report-history.component';
import { ReportTemplatesComponent } from '@home/pages/report/report-template/report-templates.component';
import { ReportTemplateDialogComponent } from '@home/pages/report/report-template/report-template-dialog.component';
import { ReportTemplateEditorComponent } from '@home/pages/report/report-template/designer/report-template-editor.component';
import { ReportPageSettingsComponent } from '@home/pages/report/report-template/designer/report-page-settings.component';
import { ReportInsetsComponent } from '@home/pages/report/report-template/designer/widgets/report-insets.component';
import { ReportFontComponent } from '@home/pages/report/report-template/designer/widgets/report-font.component';
import {
  ReportBackgroundBorderComponent
} from '@home/pages/report/report-template/designer/widgets/report-background-border.component';
import { ReportAlignmentComponent } from '@home/pages/report/report-template/designer/widgets/report-alignment.component';
import { ReportPaletteComponent } from '@home/pages/report/report-template/designer/report-palette.component';
import { ReportCanvasComponent } from '@home/pages/report/report-template/designer/report-canvas.component';
import { ReportPreviewComponent } from '@home/pages/report/report-template/designer/report-preview.component';
import {
  ReportHeaderFooterComponent
} from '@home/pages/report/report-template/designer/report-header-footer.component';
import {
  ReportPageBreakConfigComponent
} from '@home/pages/report/report-template/designer/components/report-page-break-config.component';
import {
  ReportDividerConfigComponent
} from '@home/pages/report/report-template/designer/components/report-divider-config.component';
import {
  ReportHeadingConfigComponent
} from '@home/pages/report/report-template/designer/components/report-heading-config.component';
import {
  ReportRichTextConfigComponent
} from '@home/pages/report/report-template/designer/components/report-rich-text-config.component';
import {
  ReportImageConfigComponent
} from '@home/pages/report/report-template/designer/components/report-image-config.component';
import {
  ReportTableConfigComponent
} from '@home/pages/report/report-template/designer/components/report-table-config.component';
import {
  ReportSubReportConfigComponent
} from '@home/pages/report/report-template/designer/components/report-sub-report-config.component';
import {
  ReportDashboardConfigComponent
} from '@home/pages/report/report-template/designer/components/report-dashboard-config.component';
import {
  ReportConfigTabsComponent
} from '@home/pages/report/report-template/designer/data/report-config-tabs.component';
import {
  ReportCellSettingsComponent
} from '@home/pages/report/report-template/designer/data/report-cell-settings.component';
import {
  ReportColumnSettingsComponent
} from '@home/pages/report/report-template/designer/data/report-column-settings.component';
import {
  ReportTableSettingsComponent
} from '@home/pages/report/report-template/designer/data/report-table-settings.component';
import {
  ReportTimewindowComponent
} from '@home/pages/report/report-template/designer/data/report-timewindow.component';
import {
  ReportAlarmFilterComponent
} from '@home/pages/report/report-template/designer/data/report-alarm-filter.component';
import {
  ReportDataKeysComponent
} from '@home/pages/report/report-template/designer/data/report-data-keys.component';
import {
  ReportDatasourceComponent
} from '@home/pages/report/report-template/designer/data/report-datasource.component';
import {
  ReportDatasourcesComponent
} from '@home/pages/report/report-template/designer/data/report-datasources.component';
import {
  ReportEntityAliasesComponent
} from '@home/pages/report/report-template/designer/data/report-entity-aliases.component';
import {
  ReportFiltersComponent
} from '@home/pages/report/report-template/designer/data/report-filters.component';
import {
  ReportAliasesFiltersDialogComponent
} from '@home/pages/report/report-template/designer/data/report-aliases-filters-dialog.component';
import { MatDialog } from '@angular/material/dialog';
import { createReportAliasCallbacks } from '@home/pages/report/report-template/designer/data/report-alias-callbacks';
import {
  REPORT_ALIAS_CALLBACKS_FACTORY,
  ReportAliasCallbacksFactory
} from '@home/pages/report/report-template/designer/data/report-alias-callbacks.models';
import { ReportImportExportService } from '@home/pages/report/report-template/designer/report-import-export';
import { ReportSchedulingComponent } from '@home/pages/report/report-scheduling/report-scheduling.component';
import {
  ReportTemplatesTableHeaderComponent
} from '@home/pages/report/report-template/report-templates-table-header.component';

@NgModule({
  declarations: [
    ReportHistoryComponent,
    ReportSchedulingComponent,
    ReportTemplatesComponent,
    // The templates list's toolbar filter, instantiated dynamically through
    // EntityTableConfig#headerComponent.
    ReportTemplatesTableHeaderComponent,
    // The root-fields step in front of the designer: opened by the templates list for both
    // "Create new report template" (format/type decide the config shape the designer builds) and
    // the per-row edit action (name/description only).
    ReportTemplateDialogComponent,
    ReportTemplateEditorComponent,
    // C1 Task 5: right-panel page/template settings shown when no canvas component is selected.
    ReportPageSettingsComponent,
    // C1 Task 4: shared ControlValueAccessor sub-editors, embedded via formControlName by the
    // page-settings/component-config panels (T5, T8-T11).
    ReportInsetsComponent,
    ReportFontComponent,
    ReportBackgroundBorderComponent,
    ReportAlignmentComponent,
    // C1 Task 6: left palette (draggable component library) + center canvas (ordered, reorderable
    // cdkDropList of component cards), hosted side by side by the shell.
    ReportPaletteComponent,
    ReportCanvasComponent,
    // C1 Task 7: whole-template PDF preview pane, shown by the shell instead of the body above when
    // viewMode === 'preview'.
    ReportPreviewComponent,
    // C2 Task 15A: header/footer editor, hosted twice by ReportPageSettingsComponent (config.header/
    // config.footer, PDF only) - a nested tb-report-canvas reusing the SAME 10-panel right pane as
    // the body canvas (see its own class header), plus one capped level of recursion for the
    // "different first page" variant.
    ReportHeaderFooterComponent,
    // C1 Task 8: first two per-type right-panel config panels (shown when a canvas component is
    // selected), establishing the per-type switch T9-T11 extend.
    ReportPageBreakConfigComponent,
    ReportDividerConfigComponent,
    // C1 Task 9: HEADING right-panel config panel.
    ReportHeadingConfigComponent,
    // C1 Task 10: RICH_TEXT right-panel config panel.
    ReportRichTextConfigComponent,
    // C1 Task 11: IMAGE right-panel config panel.
    ReportImageConfigComponent,
    // C2 Task 2: shared Content/Data/Layout tab shell, hosted by C2 data-component config panels
    // (P3-P4) and retrofitted into C1 panels (report-divider-config.component.html is the first).
    ReportConfigTabsComponent,
    // C2 Task 3: leaf CVAs for report table header/cell text styling (CellSettings) and the
    // DataKeySettings COLUMN variant (ColumnSettings) - consumed by P1's table settings panel and
    // T7's data-keys glue.
    ReportCellSettingsComponent,
    ReportColumnSettingsComponent,
    // C2 Task 4: leaf CVA for the shared table-settings block (show heading / table-heading style /
    // sort order) reused by every table-shaped component - consumed by P1's generic table config
    // panel (Task 11).
    ReportTableSettingsComponent,
    // C2 Task 5: leaf CVA mapping shim between TimeWindowConfiguration (report wire model) and the
    // platform tb-timewindow widget's Timewindow CVA value - consumed by ALARM_TABLE's and
    // TIME_SERIES_TABLE's panels (Task 11).
    ReportTimewindowComponent,
    // C2 Task 6: leaf CVA mapping shim between AlarmFilterConfig (report wire model) and the
    // platform tb-alarm-filter-config widget's AlarmFilterConfig CVA value - consumed by
    // ALARM_TABLE's alarmSource panel (Task 11).
    ReportAlarmFilterComponent,
    // C2 Task 7: wraps the platform tb-data-keys widget (base fields) + a per-key
    // tb-report-column-settings panel (report-only ColumnSettings) behind the report DataKey[] CVA
    // boundary - consumed by report-datasource (Task 8) and ALARM_TABLE's alarmSource (Task 11).
    ReportDataKeysComponent,
    // C2 Task 8: the datasource integration hub - tb-report-datasource (single DataSource, type
    // switch over the Task 6/7/9 leaves + tb-entity-autocomplete/tb-entity-alias-select/
    // tb-filter-select) and tb-report-datasources (add/remove DataSource[] list) - consumed by
    // ENTITY_TABLE/TIME_SERIES_TABLE's dataSources and ALARM_TABLE's alarmSource (Task 11).
    ReportDatasourceComponent,
    ReportDatasourcesComponent,
    // C2 Task 10: template-level "manage aliases"/"manage filters" lists (config.entityAliases[]/
    // config.filters[] add/edit/delete via the platform's single-item EntityAliasDialogComponent/
    // FilterDialogComponent, both already reachable through HomeComponentsModule below) - hosted by
    // ReportPageSettingsComponent's two new expansion-panel entry points.
    ReportEntityAliasesComponent,
    ReportFiltersComponent,
    // C3: the designer toolbar's Aliases/Filters buttons - one dialog hosting whichever of the two
    // lists above was asked for (PE puts both behind toolbar buttons, docs/images/reporting-5.png).
    ReportAliasesFiltersDialogComponent,
    // C2 Task 11: generic right-panel config panel for the 3 table-shaped components
    // (ENTITY_TABLE/ALARM_TABLE/TIME_SERIES_TABLE) - assembles Tasks 2/4/5/8/9's sub-editors behind
    // one CVA shaped by `componentType`.
    ReportTableConfigComponent,
    // C2 Task 13: right-panel config panel for SUB_REPORT - a bespoke report-template
    // mat-autocomplete (no generic-entity-query REPORT_TEMPLATE support exists) + the
    // avoidPageBreakInside CSV gate + Task 8's tb-report-datasources.
    ReportSubReportConfigComponent,
    // C2 Task 14: right-panel config panel for DASHBOARD - tb-dashboard-autocomplete +
    // tb-timewindow (bound raw, no shim) + Task 8's tb-report-datasources + the inline image-sizing
    // controls report-image-config.component.html established, all behind a security-critical
    // allowlist that structurally excludes baseUrl/userId/useCurrentUserCredentials from the
    // emitted DashboardReportConfig (R2a's CRITICAL SSRF/token-exfil finding, replicated here as FE
    // defense-in-depth).
    ReportDashboardConfigComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    // C2 Task 6: tb-alarm-filter-config (wrapped by ReportAlarmFilterComponent) is declared/exported
    // by WidgetConfigComponentsModule, which HomeComponentsModule imports but does NOT re-export -
    // needed directly here for the selector to resolve in this module's templates. Also covers
    // C2 Task 7's tb-data-keys AND C2 Task 8's tb-entity-alias-select/tb-filter-select: all three are
    // declared/exported by WidgetSettingsCommonModule, which WidgetConfigComponentsModule imports AND
    // re-exports (widget-config-components.module.ts's own `exports` array) - so no further module
    // import is needed for any of them to resolve here. Task 8's fourth leaf, tb-entity-autocomplete,
    // is declared/exported by SharedModule (imported above) - already in scope.
    WidgetConfigComponentsModule,
    ReportRoutingModule
  ],
  providers: [
    // C2 Task 10 fix: the ONE place that wires the REAL EntityAliasDialogComponent/
    // FilterDialogComponent (via report-alias-callbacks.ts's createReportAliasCallbacks) into the DI
    // token report-entity-aliases.component.ts/report-filters.component.ts depend on
    // (report-alias-callbacks.models.ts). Deliberately kept out of those two components' own static
    // imports - see their headers - so their specs can inject a fake factory and run under karma
    // without dragging in HomeComponentsModule's full compilation scope. This file has no spec of
    // its own, so nothing regresses by importing the concrete classes here.
    {
      provide: REPORT_ALIAS_CALLBACKS_FACTORY,
      useFactory: (dialog: MatDialog): ReportAliasCallbacksFactory =>
        (aliasController) => createReportAliasCallbacks(dialog, aliasController),
      deps: [MatDialog]
    },
    // C2 Task 17: the shell (ReportTemplateEditorComponent) injects this for toolbar Import/Export -
    // see report-import-export.ts for why it's a plain @Injectable() (no providedIn:'root') kept
    // deliberately free of the platform ImportExportService/DialogService import chain.
    ReportImportExportService
  ],
  exports: [
    ReportPageSettingsComponent,
    ReportInsetsComponent,
    ReportFontComponent,
    ReportBackgroundBorderComponent,
    ReportAlignmentComponent,
    ReportPaletteComponent,
    ReportCanvasComponent,
    ReportPreviewComponent,
    ReportHeaderFooterComponent,
    ReportPageBreakConfigComponent,
    ReportDividerConfigComponent,
    ReportHeadingConfigComponent,
    ReportRichTextConfigComponent,
    ReportImageConfigComponent,
    ReportConfigTabsComponent,
    ReportCellSettingsComponent,
    ReportColumnSettingsComponent,
    ReportTableSettingsComponent,
    ReportTimewindowComponent,
    ReportAlarmFilterComponent,
    ReportDataKeysComponent,
    ReportDatasourceComponent,
    ReportDatasourcesComponent,
    ReportEntityAliasesComponent,
    ReportFiltersComponent,
    ReportTableConfigComponent,
    ReportSubReportConfigComponent,
    ReportDashboardConfigComponent
  ]
})
export class ReportModule { }
