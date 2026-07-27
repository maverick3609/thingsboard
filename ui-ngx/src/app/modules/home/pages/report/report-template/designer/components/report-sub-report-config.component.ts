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
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormControl, UntypedFormGroup } from '@angular/forms';
import { Observable, of, Subject } from 'rxjs';
import { catchError, debounceTime, map, share, switchMap, takeUntil } from 'rxjs/operators';
import { SubReportComponent } from '@shared/models/report-configuration.models';
import { ReportTemplateId } from '@shared/models/id/report-template-id';
import { BaseReportTemplate } from '@shared/models/report.models';
import { EntityType } from '@shared/models/entity-type.models';
import { ReportService } from '@core/http/report.service';
import { PageLink } from '@shared/models/page/page-link';
import { Direction } from '@shared/models/page/sort-order';
import { emptyPageData } from '@shared/models/page/page-data';
import { IAliasController } from '@core/api/widget-api.models';

// CVA for components/SubReportComponent.java - the shell's right-panel content when the selected
// canvas component is SUB_REPORT (report-template-editor.component.html). Mirrors T8-T11's
// FormGroup -> propagateChange plumbing (task-13-interfaces.md's "mirror divider/table CVA"); unlike
// DIVIDER/IMAGE/the table family, this model has NO layout fields at all (report-configuration
// .models.ts:411's own header note - it extends ReportComponentDataSources only), so subFormGroup is
// a flat 1:1 with SubReportComponent's fields and toComponent() needs no flattening/renaming, just
// the `type` tag.
//
// templateId (the sub-reported ReportTemplateId) has no ready-made picker. Verified at task start
// (see task-13-report.md for the full trace): tb-entity-autocomplete[entityType=REPORT_TEMPLATE]
// cannot be used - EntityService's load()/getEntitiesByPageLinkObservable()/getEntityObservable()
// switch statements all lack a REPORT_TEMPLATE case, so the picker would always show zero options
// AND (since getEntity() throws when getEntityObservable() returns undefined) writeValue() would
// silently wipe an already-saved templateId to null the moment this panel opens. This panel instead
// hosts a bespoke, self-contained mat-autocomplete over ReportService#getReportTemplateInfos
// (core/http/report.service.ts:92), following the same fetch-by-name-on-type-ahead/fetch-by-id-on-
// writeValue shape as the platform's own DeviceProfileAutocompleteComponent (modules/home/components
// /profile/device-profile-autocomplete.component.ts) - but folded into this one CVA rather than a
// new reusable shared component, since Task 13 only introduces this single panel.
//
// `templateInput` is a SEPARATE, un-grouped FormControl (never added to subFormGroup) holding the
// full display object (a BaseReportTemplate, for the autocomplete's displayWith) or the raw
// in-progress search string - subFormGroup.templateId itself only ever holds the clean
// ReportTemplateId (or null), exactly the flat shape task-13-interfaces.md's subFormGroup literal
// prescribes, so toComponent() can read it back verbatim with no derivation.
//
// avoidPageBreakInside is gated by `@Input() format` (CSV has no page breaks - design spec's
// isPlainFormat rule) but ALWAYS included in toComponent()'s output regardless of visibility: hiding
// its row via *ngIf does not clear the control's value (verified against the installed @angular/forms
// source - FormGroupDirective#removeControl only tears down the value-accessor/validator bindings via
// cleanUpControl; it never calls FormGroup#removeControl, so the control and its last value survive
// the row being hidden) - so toComponent() needs no special-case handling to preserve it.
@Component({
    selector: 'tb-report-sub-report-config',
    templateUrl: './report-sub-report-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportSubReportConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportSubReportConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  subFormGroup: UntypedFormGroup;

  disabled = false;

  // Root config's format discriminator (report-configuration.models.ts:462/473), forwarded by the
  // shell from `config.format` - gates the avoidPageBreakInside row (CSV has no page breaks).
  @Input()
  format: 'PDF' | 'CSV';

  // The report template currently being edited - excluded from the picker's own results so a
  // template can never sub-report itself (the backend already bounds SUB_REPORT depth/fan-out -
  // R2b's MAX_SUB_REPORT_DEPTH/MAX_TOTAL_SUB_REPORT_RENDERS on TbReportCtx - but the picker itself
  // should not even offer the self-referencing option).
  @Input()
  currentTemplateId: string;

  // Forwarded to tb-report-datasources - see Task 9's createReportAliasController (created once by
  // the shell in ngOnInit, threaded down here the same way Task 11's table panel receives it).
  @Input()
  aliasController: IAliasController;

  // Un-grouped display control for the template autocomplete's <input> - see class header. Holds a
  // BaseReportTemplate (a fetched/selected template) or a raw in-progress search string, NEVER part
  // of subFormGroup.
  templateInput = new UntypedFormControl(null);

  filteredTemplates: Observable<BaseReportTemplate[]>;

  searchText = '';

  private destroy$ = new Subject<void>();
  private propagateChange: (value: SubReportComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder, private reportService: ReportService) {
  }

  ngOnInit(): void {
    this.subFormGroup = this.fb.group({
      templateId: [null],
      avoidPageBreakInside: [null],
      dataSources: [null]
    });
    this.subFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toComponent(value)));

    // A DEDICATED, always-active subscription - deliberately NOT folded into the filteredTemplates
    // pipe below via tap(), since that pipe is only driven when something subscribes to it (the
    // template's `| async`). The templateId mapping must apply the moment a template is
    // selected/cleared regardless of whether the dropdown itself is ever rendered/subscribed.
    this.templateInput.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: BaseReportTemplate | string) => {
      const templateId: ReportTemplateId = value && typeof value !== 'string'
        ? { entityType: EntityType.REPORT_TEMPLATE, id: value.id.id }
        : null;
      this.subFormGroup.get('templateId').setValue(templateId);
    });

    this.filteredTemplates = this.templateInput.valueChanges.pipe(
      takeUntil(this.destroy$),
      map(value => value ? (typeof value === 'string' ? value : value.name) : ''),
      debounceTime(150),
      switchMap(name => this.fetchTemplates(name)),
      share()
    );
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: SubReportComponent) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.subFormGroup.disable({emitEvent: false});
      this.templateInput.disable({emitEvent: false});
    } else {
      this.subFormGroup.enable({emitEvent: false});
      this.templateInput.enable({emitEvent: false});
    }
  }

  writeValue(component: SubReportComponent): void {
    const c = component ?? ({} as SubReportComponent);
    this.subFormGroup.reset({
      templateId: c.templateId ?? null,
      avoidPageBreakInside: c.avoidPageBreakInside ?? null,
      dataSources: c.dataSources ?? null
    }, {emitEvent: false});
    // Cleared synchronously first so a stale display name from a previously-selected component never
    // flashes while the fetch below (if any) is in flight - the shell keeps this same panel instance
    // alive across a canvas-selection change from one SUB_REPORT card to another (see class header).
    this.templateInput.setValue(null, {emitEvent: false});
    if (c.templateId?.id) {
      this.reportService.getReportTemplateById(c.templateId.id, {ignoreLoading: true, ignoreErrors: true}).subscribe({
        next: template => this.templateInput.setValue(template, {emitEvent: false}),
        // A deleted/inaccessible template: leave the display blank rather than propagate anything -
        // the stored templateId (already reset onto subFormGroup above) is untouched either way.
        error: () => {}
      });
    }
  }

  get showAvoidPageBreakInside(): boolean {
    return this.format !== 'CSV';
  }

  displayTemplateFn(template?: BaseReportTemplate): string | undefined {
    return template ? template.name : undefined;
  }

  fetchTemplates(searchText?: string): Observable<BaseReportTemplate[]> {
    this.searchText = searchText;
    const pageLink = new PageLink(20, 0, searchText, {property: 'name', direction: Direction.ASC});
    return this.reportService.getReportTemplateInfos(pageLink, {}, {ignoreLoading: true}).pipe(
      catchError(() => of(emptyPageData<BaseReportTemplate>())),
      map(pageData => pageData.data.filter(template => template.id.id !== this.currentTemplateId))
    );
  }

  clearTemplate(): void {
    this.templateInput.setValue(null);
  }

  textIsNotEmpty(text: string): boolean {
    return !!(text && text.length > 0);
  }

  private toComponent(value: any): SubReportComponent {
    return {
      type: 'SUB_REPORT',
      templateId: value.templateId,
      avoidPageBreakInside: value.avoidPageBreakInside,
      dataSources: value.dataSources
    };
  }
}
