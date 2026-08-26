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

import { Component, EventEmitter, forwardRef, Input, Output } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { HeaderFooter, ReportComponent } from '@shared/models/report-configuration.models';

function newHeaderFooter(): HeaderFooter {
  return { enabled: false, components: [] };
}

// Read-only stand-in returned by `current` while the "first page" tab is selected on a HeaderFooter
// that has no `firstPage` yet. It exists so merely LOOKING at that tab never fabricates a firstPage
// object in the saved config (C2 Task 18's byte-fidelity round-trip: an untouched optional must
// stay absent). Frozen because nothing may write through it - every mutating path goes through
// mutableCurrent(), which materialises the real firstPage first. Its `components` array is never
// bound to a canvas: the canvas only renders when current.enabled is true, and this is never
// enabled.
const ABSENT_FIRST_PAGE: HeaderFooter = Object.freeze({ enabled: false, components: Object.freeze([]) as ReportComponent[] });

// PE's ReportHeaderComponent: the header (or footer) block the canvas column renders above (below)
// the report body - NOT a right-panel form. Verified against the PE 4.2.0 bundle
// (docs/jars/.../ui-ngx-4.2.0PE.jar, chunk-UILY2NXB.js `wp` class) and docs/images/reporting-5.png:
// a "Header | First page header" toggle, a Collapse/Expand button and a Disable/Enable button over
// a drop target, with a "Header disabled" prompt in place of the drop target when that variant is
// off.
//
// One instance edits BOTH variants of one HeaderFooter: `value` (the main header/footer) and
// `value.firstPage` (the "different first page" variant), switched by the toggle - which is why
// PE has no recursion here and neither do we any more (the pre-C3 shape nested a second
// tb-report-header-footer for firstPage, capped by an `allowFirstPage` Input). `header` only picks
// the labels.
//
// THE key insight this component exists to exploit: tb-report-canvas already exposes (selected)
// plus [components]/(componentsChange) (two-way, in-place mutation), and the shell edits a selected
// component via Object.assign(this.selected, value) - pure identity mutation that works for a
// component living in ANY array, not just config.components. So the nested <tb-report-canvas>
// routes its (selected) straight up through this component's OWN componentSelected output to the
// shell's `selected` field - the entire existing 10-panel right pane edits header/footer components
// for FREE, with zero panel duplication.
//
// Two channels out, kept deliberately separate:
// - The CVA value channel (propagateChange/ngModelChange) - carries the HeaderFooter itself.
//   Mutations are made IN PLACE on the object the shell handed us (config.header/config.footer), so
//   this channel mostly re-delivers the same reference; it is still what flushes a newly
//   materialised `firstPage`.
// - componentSelected/componentsChanged - plain @Output, no CVA involvement. componentSelected is
//   the selection-routing hop above. componentsChanged is a dedicated "something in one of my
//   arrays changed" signal the shell needs to re-run its cross-array orphan-clear
//   (selectionStillReachable() in report-template-editor.component.ts) - the CVA channel is a
//   same-reference no-op for an in-place canvas mutation and so cannot carry it.
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
export class ReportHeaderFooterComponent implements ControlValueAccessor {

  // Labels only - a header and a footer are the same editor over the same shape.
  @Input()
  header = true;

  // Selection channel, SEPARATE from the CVA value channel - see class header.
  @Output()
  componentSelected = new EventEmitter<ReportComponent>();

  // "An array mutated somewhere under me" signal, SEPARATE from the CVA value channel - see class
  // header.
  @Output()
  componentsChanged = new EventEmitter<void>();

  variant: 'main' | 'firstPage' = 'main';

  expanded = true;

  disabled = false;

  // Bound directly in the template, NOT through a reactive form - tb-report-canvas is not a
  // ControlValueAccessor, and the two toggles are plain buttons.
  value: HeaderFooter = newHeaderFooter();

  private propagateChange: (value: HeaderFooter) => void = () => {};

  // The HeaderFooter the toggle currently points at, for READING only (labels, enabled state, the
  // components the canvas renders). Writers must use mutableCurrent().
  get current(): HeaderFooter {
    return this.variant === 'firstPage' ? (this.value.firstPage ?? ABSENT_FIRST_PAGE) : this.value;
  }

  get titleKey(): string {
    if (this.variant === 'firstPage') {
      return this.header ? 'report.designer.first-page-header' : 'report.designer.first-page-footer';
    }
    return this.header ? 'report.designer.header' : 'report.designer.footer';
  }

  get disabledKey(): string {
    if (this.variant === 'firstPage') {
      return this.header ? 'report.designer.first-page-header-disabled' : 'report.designer.first-page-footer-disabled';
    }
    return this.header ? 'report.designer.header-disabled' : 'report.designer.footer-disabled';
  }

  registerOnChange(fn: (value: HeaderFooter) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(_fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  // Keeps the same object reference the shell owns (config.header/config.footer): every mutation
  // below writes through it, exactly like the body canvas mutates config.components in place.
  writeValue(value: HeaderFooter): void {
    this.value = value ?? newHeaderFooter();
  }

  toggleEnabled(): void {
    const current = this.mutableCurrent();
    current.enabled = !current.enabled;
    this.propagateChange(this.value);
    this.componentsChanged.emit();
  }

  // The canvas mutates the array in place and re-emits the same reference, so this assignment is a
  // no-op in the common case; it is still made so the component works if that ever changes.
  onComponentsChange(components: ReportComponent[]): void {
    this.mutableCurrent().components = components;
    this.propagateChange(this.value);
    this.componentsChanged.emit();
  }

  // Materialises value.firstPage on the first WRITE to the first-page variant - see
  // ABSENT_FIRST_PAGE for why reads must not do this.
  private mutableCurrent(): HeaderFooter {
    if (this.variant === 'firstPage' && !this.value.firstPage) {
      this.value.firstPage = newHeaderFooter();
    }
    return this.current;
  }
}
