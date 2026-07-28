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

import { Component, EventEmitter, forwardRef, Input, OnDestroy, OnInit, Output } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { HeaderFooter, ReportComponent } from '@shared/models/report-configuration.models';

function newHeaderFooter(): HeaderFooter {
  return { enabled: false, components: [] };
}

// CVA for configuration/HeaderFooter.java - report-page-settings.component.html hosts one instance
// bound to config.header and one bound to config.footer (PDF only; task-15a-interfaces.md's frozen,
// verified-PE model - report-configuration.models.ts is UNTOUCHED). Value shape:
// HeaderFooter = {enabled?, components: ReportComponent[], firstPage?: HeaderFooter}.
//
// THE key insight this component exists to exploit (task-15a-interfaces.md's "KEY architectural
// insight"): tb-report-canvas already exposes (selected) (the clicked component) plus
// [components]/(componentsChange) (two-way, in-place mutation), and the shell edits a selected
// component via Object.assign(this.selected, value) - pure identity mutation that works for a
// component living in ANY array, not just config.components. So the nested <tb-report-canvas>
// below routes its (selected) straight up through this component's OWN componentSelected output,
// all the way to the shell's `selected` field (via report-page-settings.component.ts's own
// re-emitting @Output of the same name) - the entire existing 10-panel right pane edits header/
// footer components for FREE, with zero panel duplication.
//
// Two channels out of this component, kept deliberately separate:
// - The CVA value channel (propagateChange/ngModelChange) - carries the HeaderFooter itself. The
//   nested canvas mutates value.components IN PLACE (like the shell's body canvas), so this channel
//   mostly re-delivers the same array reference - but it is still the one channel that flushes the
//   enabled/firstPage TOGGLES (which are plain-field replacements, not in-place mutations).
// - componentSelected/componentsChanged - plain @Output, no CVA involvement. componentSelected is
//   the selection-routing hop described above. componentsChanged is a dedicated "something in one
//   of my arrays changed, at any depth" signal: the shell's cross-array orphan-clear
//   (selectionStillReachable() in report-template-editor.component.ts) needs to re-run on every such
//   mutation, but the CVA channel gives report-page-settings.component.html's
//   `(ngModelChange)="config.header = $event"` binding nowhere natural to hang that recompute (it's
//   a same-reference no-op in the common case) - so this dedicated signal exists instead, re-emitted
//   one more hop by report-page-settings.component.ts's own @Output of the same name.
//
// Recursion cap: `firstPage` is itself a full HeaderFooter (the model is genuinely recursive), but
// the UI only ever exposes ONE level of "different first page". @Input() allowFirstPage, true by
// default, is passed `false` on the nested instance this component hosts for value.firstPage, so
// THAT instance's own "different first page" toggle (and thus a third template level) never
// renders, regardless of what the underlying data contains.
@Component({
    selector: 'tb-report-header-footer',
    templateUrl: './report-header-footer.component.html',
    styleUrls: ['./report-header-footer.component.scss'],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportHeaderFooterComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportHeaderFooterComponent implements ControlValueAccessor, OnInit, OnDestroy {

  // Caps the recursive model at 1 UI level - see class header. false on the nested firstPage
  // instance this component's own template hosts.
  @Input()
  allowFirstPage = true;

  // Selection channel, SEPARATE from the CVA value channel - see class header. Fed by
  // reEmitSelection(), the shared hop for both the nested canvas's (selected) and a nested
  // firstPage editor's own (componentSelected).
  @Output()
  componentSelected = new EventEmitter<ReportComponent>();

  // "An array mutated somewhere under me" signal, SEPARATE from the CVA value channel - see class
  // header. Re-emitted (not re-derived) from a nested firstPage editor's own componentsChanged.
  @Output()
  componentsChanged = new EventEmitter<void>();

  toggleFormGroup: UntypedFormGroup;

  disabled = false;

  // Bound directly in the template ([components]="value.components", *ngIf="...value.firstPage"),
  // NOT part of toggleFormGroup - a nested tb-report-canvas/tb-report-header-footer is not itself a
  // reactive form control (tb-report-canvas isn't a ControlValueAccessor at all), so these fields
  // are plain, propagated by hand via onComponentsChange/onFirstPageChange below.
  value: HeaderFooter = newHeaderFooter();

  private destroy$ = new Subject<void>();
  private propagateChange: (value: HeaderFooter) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.toggleFormGroup = this.fb.group({
      enabled: [false],
      firstPageEnabled: [false]
    });
    this.toggleFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((toggles: { enabled: boolean; firstPageEnabled: boolean }) => {
      this.value = {
        ...this.value,
        enabled: toggles.enabled,
        firstPage: toggles.firstPageEnabled ? (this.value.firstPage ?? newHeaderFooter()) : undefined
      };
      this.propagateChange(this.value);
      this.componentsChanged.emit();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: HeaderFooter) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.toggleFormGroup.disable({emitEvent: false});
    } else {
      this.toggleFormGroup.enable({emitEvent: false});
    }
  }

  // {emitEvent: false} is REQUIRED: without it, .reset() fires toggleFormGroup.valueChanges,
  // re-entering the subscription above and calling propagateChange()/componentsChanged.emit() right
  // back out - a write driven by the PARENT (the shell loading an existing template, or switching
  // `selected` away and back so page-settings remounts from config.header) would then be misread as
  // the USER toggling enabled/firstPage: corrupting `firstPage` (the `?? newHeaderFooter()` branch
  // would fabricate a bogus empty firstPage the moment a stale firstPageEnabled=true echoed back
  // through valueChanges) and re-entering on every load.
  writeValue(value: HeaderFooter): void {
    this.value = value ?? newHeaderFooter();
    this.toggleFormGroup.reset({
      enabled: this.value.enabled ?? false,
      firstPageEnabled: !!this.value.firstPage
    }, {emitEvent: false});
  }

  // tb-report-canvas mutates `components` in place and re-emits the same reference (like the
  // shell's body canvas) - value.components = components is a same-ref no-op in the common case,
  // but still propagated (see class header) so a delete's orphan-clear reaches the shell.
  onComponentsChange(components: ReportComponent[]): void {
    this.value.components = components;
    this.propagateChange(this.value);
    this.componentsChanged.emit();
  }

  // ngModelChange handler for the nested firstPage editor (CVA value channel) - writes its updated
  // HeaderFooter back onto value.firstPage and propagates the OUTER value upward. Deliberately does
  // NOT also emit componentsChanged: the nested editor's own componentsChanged is forwarded by a
  // separate, direct template binding, so re-emitting it here too would double-fire.
  onFirstPageChange(firstPage: HeaderFooter): void {
    this.value.firstPage = firstPage;
    this.propagateChange(this.value);
  }

  // Shared re-emission hop for componentSelected - bound to both the nested canvas's (selected) and
  // a nested firstPage editor's own (componentSelected), so a click at either depth reaches this
  // component's own componentSelected the same way.
  reEmitSelection(component: ReportComponent): void {
    this.componentSelected.emit(component);
  }
}
