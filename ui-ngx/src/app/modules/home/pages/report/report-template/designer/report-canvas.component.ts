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
import { CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { newReportComponent, ReportComponent, ReportComponentType } from '@shared/models/report-configuration.models';
import { reportComponentTypeInfo, ReportComponentTypeInfo } from '@home/pages/report/report-template/designer/report-palette.component';

// Center pane of the R2c designer (design spec 2026-07-24-reporting-r2c-design.md §1) - an ordered
// cdkDropList of component cards bound to the shell's config.components (T3, via
// [(components)]="config.components"). Two drop sources feed the same (cdkDropListDropped) handler
// because both this list and report-palette.component.ts's list sit inside the shell's
// cdkDropListGroup (report-template-editor.component.html): a drop whose previousContainer is this
// list itself is an intra-canvas reorder (moveItemInArray); any other previousContainer is a palette
// item, carrying a ReportComponentType as its cdkDragData, expanded via T1's newReportComponent()
// factory and spliced in at the drop index. All mutation is in-place on the @Input() array (mirrors
// T5's Object.assign-not-replace choice for config) so the single componentsChange emit after each
// mutation always carries the same reference the shell's two-way binding already holds.
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

  onDrop(event: CdkDragDrop<ReportComponent[], any, ReportComponentType>): void {
    if (event.previousContainer === event.container) {
      moveItemInArray(this.components, event.previousIndex, event.currentIndex);
    } else {
      this.components.splice(event.currentIndex, 0, newReportComponent(event.item.data));
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
