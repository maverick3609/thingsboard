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

// Plain instantiation (no TestBed), matching this designer's established convention
// (report-template-editor.component.spec.ts, report-insets.component.spec.ts, etc.): the
// reorder/insert/remove/select logic under test lives entirely in plain methods that only touch
// `components` and the two EventEmitters, never the DOM, so a `new`'d instance proves it without
// compiling the cdkDropList/cdkDrag template (and the SharedModule dependency graph that would drag
// in).
import { CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { ReportCanvasComponent, ReportCanvasDragData } from './report-canvas.component';
import { newReportComponent, ReportComponent, ReportComponentType } from '@shared/models/report-configuration.models';
import { REPORT_COMPONENT_PRESETS } from './presets/report-component-presets';

describe('ReportCanvasComponent', () => {

  const componentA: ReportComponent = { type: 'DIVIDER' };
  const componentB: ReportComponent = { type: 'PAGE_BREAK' };

  function newCanvas(): ReportCanvasComponent {
    const canvas = new ReportCanvasComponent();
    canvas.components = [componentA, componentB];
    return canvas;
  }

  // Minimal fake covering only the fields onDrop() reads (previousIndex, currentIndex, container,
  // previousContainer, item.data). A real CdkDropList/CdkDrag needs a live DOM + NgZone, which this
  // spec deliberately avoids (see header comment) - `previousContainer === container` (same object)
  // is what onDrop uses to distinguish an intra-canvas reorder from a cross-list drop from the
  // palette, so a same-vs-different object reference is all the fake needs to get right.
  function dropEvent(previousIndex: number, currentIndex: number, crossContainer: boolean,
                      itemData?: ReportCanvasDragData): CdkDragDrop<ReportComponent[], any, ReportCanvasDragData> {
    const container = {} as CdkDropList<ReportComponent[]>;
    const previousContainer = crossContainer ? ({} as CdkDropList<any>) : container;
    return {
      previousIndex,
      currentIndex,
      container,
      previousContainer,
      item: { data: itemData } as any,
      isPointerOverContainer: true,
      distance: { x: 0, y: 0 },
      dropPoint: { x: 0, y: 0 },
      event: new MouseEvent('mouseup')
    };
  }

  it('reorders in place via moveItemInArray and emits componentsChange when the drop is within the canvas itself', () => {
    const canvas = newCanvas();
    const onChange = jasmine.createSpy('componentsChange');
    canvas.componentsChange.subscribe(onChange);

    canvas.onDrop(dropEvent(0, 1, false));

    expect(canvas.components).toEqual([componentB, componentA]);
    expect(onChange).toHaveBeenCalledWith([componentB, componentA]);
  });

  it('inserts newReportComponent(type) at the drop index and emits componentsChange when the drop comes from the palette', () => {
    const canvas = newCanvas();
    const onChange = jasmine.createSpy('componentsChange');
    canvas.componentsChange.subscribe(onChange);

    canvas.onDrop(dropEvent(0, 2, true, ReportComponentType.HEADING));

    expect(canvas.components).toEqual([componentA, componentB, newReportComponent(ReportComponentType.HEADING)]);
    expect(onChange).toHaveBeenCalledWith(canvas.components);
  });

  it('expands a preset drop via its own factory() (not newReportComponent) and inserts the clone at the drop index, emitting componentsChange (C2 Task 15B)', () => {
    const canvas = newCanvas();
    const onChange = jasmine.createSpy('componentsChange');
    canvas.componentsChange.subscribe(onChange);
    const preset = REPORT_COMPONENT_PRESETS.find(p => p.key === 'pageNumber');

    canvas.onDrop(dropEvent(0, 2, true, { preset }));

    expect(canvas.components).toEqual([componentA, componentB, preset.factory()]);
    expect(onChange).toHaveBeenCalledWith(canvas.components);
  });

  it('splices out the component and emits componentsChange when a card is deleted', () => {
    const canvas = newCanvas();
    const onChange = jasmine.createSpy('componentsChange');
    canvas.componentsChange.subscribe(onChange);

    canvas.removeComponent(0);

    expect(canvas.components).toEqual([componentB]);
    expect(onChange).toHaveBeenCalledWith([componentB]);
  });

  it('emits selected with the clicked component', () => {
    const canvas = newCanvas();
    const onSelect = jasmine.createSpy('selected');
    canvas.selected.subscribe(onSelect);

    canvas.selectComponent(componentB);

    expect(onSelect).toHaveBeenCalledWith(componentB);
  });
});
