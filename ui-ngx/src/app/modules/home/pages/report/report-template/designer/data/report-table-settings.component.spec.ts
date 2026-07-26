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

// Plain instantiation (no TestBed), mirroring report-cell-settings.component.spec.ts /
// report-heading-config.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder, never the DOM, so this proves the writeValue -> form -> propagateChange
// wiring - including the nested tableHeading.alignment flatten/unflatten mapping toTableSettings()
// does - without compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportTableSettingsComponent } from './report-table-settings.component';
import {
  FontStyle,
  FontWeight,
  ReportComponentTableSettings,
  TableSortDirection,
  TextAlignment,
  VerticalAlignment
} from '@shared/models/report-configuration.models';

describe('ReportTableSettingsComponent', () => {

  function newComponent(): ReportTableSettingsComponent {
    const component = new ReportTableSettingsComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const initial: ReportComponentTableSettings = {
    showTableHeading: true,
    tableHeading: {
      text: 'T',
      font: { size: 12, weight: FontWeight.BOLD, style: FontStyle.NORMAL, family: 'Roboto' },
      color: '#000',
      textAlignment: TextAlignment.LEFT,
      verticalAlignment: VerticalAlignment.TOP,
      height: 24
    },
    tableSortOrder: { column: 'ts', direction: TableSortDirection.DESC }
  };

  it('round-trips a full ReportComponentTableSettings', () => {
    const component = newComponent();
    component.writeValue(initial);
    let emitted: ReportComponentTableSettings;
    component.registerOnChange(v => emitted = v);

    component.tableFormGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(initial);
  });

  it('flattens the nested tableHeading alignment control back onto the top-level textAlignment/verticalAlignment fields', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial);
    component.registerOnChange(onChange);

    component.tableFormGroup.patchValue({
      tableHeading: { alignment: { textAlignment: TextAlignment.RIGHT, verticalAlignment: VerticalAlignment.BOTTOM } }
    });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      tableHeading: jasmine.objectContaining({
        textAlignment: TextAlignment.RIGHT,
        verticalAlignment: VerticalAlignment.BOTTOM
      })
    }));
  });

  it('leaves every field null - not a forced default - when written with an empty object', () => {
    const component = newComponent();

    component.writeValue({});

    expect(component.tableFormGroup.value).toEqual({
      showTableHeading: null,
      tableHeading: {
        text: null,
        font: null,
        color: null,
        alignment: { textAlignment: null, verticalAlignment: null },
        height: null
      },
      tableSortOrder: { column: null, direction: null }
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.tableFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.tableFormGroup.disabled).toBe(false);
  });
});
