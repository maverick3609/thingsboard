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

import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CdkDrag, CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { newReportComponent, ReportComponent, ReportComponentType } from '@shared/models/report-configuration.models';
import { reportComponentTypeInfo, ReportComponentTypeInfo } from '@home/pages/report/report-template/designer/report-palette.component';
import { ReportComponentPreset } from '@home/pages/report/report-template/designer/presets/report-component-presets';

// Discriminated drag-data shape onDrop() accepts (C2 Task 15B): a plain ReportComponentType string
// for a palette TYPE item (UNCHANGED since C1 - report-palette.component.html's
// [cdkDragData]="item.type"), or an object carrying a ReportComponentPreset for a palette PRESET item
// (report-palette.component.html's [cdkDragData]="{ preset: item }"). Exported so
// report-canvas.component.spec.ts can type its fake drop events without re-declaring the union.
export type ReportCanvasDragData = ReportComponentType | { preset: ReportComponentPreset };

function isPresetDragData(data: ReportCanvasDragData): data is { preset: ReportComponentPreset } {
  return typeof data === 'object' && data !== null && 'preset' in data;
}

// Center pane of the R2c designer (design spec 2026-07-24-reporting-r2c-design.md §1) - an ordered
// cdkDropList of component cards bound to the shell's config.components (T3, via
// [(components)]="config.components"). Two drop sources feed the same (cdkDropListDropped) handler
// because both this list and report-palette.component.ts's list(s) sit inside the shell's
// cdkDropListGroup (report-template-editor.component.html): a drop whose previousContainer is this
// list itself is an intra-canvas reorder (moveItemInArray); any other previousContainer is a palette
// item. A palette item is either a plain ReportComponentType, expanded via T1's newReportComponent()
// factory, or (C2 Task 15B) a { preset } descriptor, expanded via the preset's OWN factory() (a fresh
// deepClone of its PE default-config fixture) instead - see isPresetDragData() above. Either way the
// resulting component is spliced in at the drop index. All mutation is in-place on the @Input() array
// (mirrors T5's Object.assign-not-replace choice for config) so the single componentsChange emit
// after each mutation always carries the same reference the shell's two-way binding already holds.
// This same component is also reused, unmodified, as the nested header/footer canvas (Task 15A) - a
// preset drop there is expanded the same way, automatically, with no special-casing needed here.
@Component({
  selector: 'tb-report-canvas',
  templateUrl: './report-canvas.component.html',
  styleUrls: ['./report-canvas.component.scss'],
  standalone: false
})
export class ReportCanvasComponent {

  @Input()
  components: ReportComponent[] = [];

  @Output()
  componentsChange = new EventEmitter<ReportComponent[]>();

  // Named `selected`, not `select` (the brief's literal name): @angular-eslint/no-output-native
  // flags `select` as a native DOM event name collision (fired on text-selection in form controls).
  // `selected` keeps the same meaning and reads symmetrically at the one call site that matters -
  // the shell's (selected)="selected = $event" (report-template-editor.component.html).
  @Output()
  selected = new EventEmitter<ReportComponent>();

  // C2 final-review Fix F1 (merge-blocker): this canvas and the shell's other canvas instances
  // (the header/footer nested canvases, Task 15A) all share the shell's cdkDropListGroup with no
  // enterPredicate, so a card dragged OUT of one canvas is a valid dragged-item as far as CDK is
  // concerned when it hovers a DIFFERENT canvas. Dropping it there used to reach onDrop()'s
  // cross-container else-branch with a full ReportComponent object as event.item.data - neither
  // isPresetDragData (needs a `preset` key) nor newReportComponent()'s switch (needs a
  // ReportComponentType string) recognize that shape, so newReportComponent() fell through to its
  // `default:` case and threw, aborting the gesture with an uncaught error. Only a palette TYPE
  // string or a preset `{ preset }` descriptor are droppable INTO a canvas from outside; a card from
  // another canvas is rejected here before onDrop ever sees it. Intra-canvas reordering is
  // unaffected - CDK does not consult enterPredicate when previousContainer === container.
  readonly paletteOnlyEnterPredicate = (drag: CdkDrag): boolean => {
    const data = drag.data;
    return typeof data === 'string' || (!!data && typeof data === 'object' && 'preset' in data);
  };

  onDrop(event: CdkDragDrop<ReportComponent[], any, ReportCanvasDragData>): void {
    if (event.previousContainer === event.container) {
      moveItemInArray(this.components, event.previousIndex, event.currentIndex);
    } else {
      const data = event.item.data;
      const component = isPresetDragData(data) ? data.preset.factory() : newReportComponent(data);
      this.components.splice(event.currentIndex, 0, component);
    }
    this.componentsChange.emit(this.components);
  }

  selectComponent(component: ReportComponent): void {
    this.selected.emit(component);
  }

  removeComponent(index: number): void {
    this.components.splice(index, 1);
    this.componentsChange.emit(this.components);
  }

  componentTypeInfo(component: ReportComponent): ReportComponentTypeInfo | undefined {
    return reportComponentTypeInfo(component.type);
  }

  trackByComponent(index: number, component: ReportComponent): ReportComponent {
    return component;
  }
}
