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

// Plain instantiation (no TestBed), mirroring report-alias-controller.spec.ts / report-page-settings
// .component.spec.ts: a MatDialog spy stands in for EntityAliasDialogComponent (never actually
// rendered), so this proves the component's own wiring (config -> aliasController -> callbacks ->
// list) without compiling a template that would drag in the real dialog + Store/Router/
// TranslateService.
import { of } from 'rxjs';
import { ReportEntityAliasesComponent } from './report-entity-aliases.component';
import { newPdfReportTemplateConfig, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { AliasFilterType, EntityAlias } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { EntityAliasDialogComponent } from '@home/components/alias/entity-alias-dialog.component';

function configWithOneAlias(): ReportTemplateConfigModel {
  return {
    ...newPdfReportTemplateConfig(),
    entityAliases: [
      {id: 'a1', alias: 'Devices', filter: {type: AliasFilterType.entityType, entityType: EntityType.DEVICE}}
    ]
  };
}

function newComponent(config: ReportTemplateConfigModel, dialog: jasmine.SpyObj<any>): ReportEntityAliasesComponent {
  const component = new ReportEntityAliasesComponent(dialog);
  component.config = config;
  component.ngOnInit();
  return component;
}

function fakeDialog(returnValue: any): jasmine.SpyObj<any> {
  const dialog = jasmine.createSpyObj('MatDialog', ['open']);
  dialog.open.and.returnValue({afterClosed: () => returnValue});
  return dialog;
}

describe('ReportEntityAliasesComponent', () => {

  it('lists config.entityAliases on init', () => {
    const config = configWithOneAlias();
    const component = newComponent(config, fakeDialog(of(null)));

    expect(component.aliases.length).toBe(1);
    expect(component.aliases[0].alias).toBe('Devices');
  });

  it('addAlias() opens EntityAliasDialogComponent, and the returned EntityAlias is appended to config.entityAliases', () => {
    const config = configWithOneAlias();
    const newAlias: EntityAlias = {id: 'a2', alias: 'Assets', filter: {type: AliasFilterType.entityType, entityType: EntityType.ASSET}};
    const dialog = fakeDialog(of(newAlias));
    const component = newComponent(config, dialog);

    component.addAlias();

    expect(dialog.open).toHaveBeenCalledWith(EntityAliasDialogComponent, jasmine.objectContaining({
      data: jasmine.objectContaining({isAdd: true})
    }));
    expect(config.entityAliases.find(a => a.id === 'a2')).toEqual(newAlias as any);
    expect(config.entityAliases.length).toBe(2);
    expect(component.aliases.length).toBe(2);
  });

  it('addAlias() does not change config.entityAliases when the dialog is cancelled', () => {
    const config = configWithOneAlias();
    const dialog = fakeDialog(of(null));
    const component = newComponent(config, dialog);

    component.addAlias();

    expect(config.entityAliases.length).toBe(1);
    expect(component.aliases.length).toBe(1);
  });

  it('editAlias() opens EntityAliasDialogComponent with isAdd false, and the result replaces the entry in config.entityAliases', () => {
    const config = configWithOneAlias();
    const edited: EntityAlias = {id: 'a1', alias: 'Devices renamed', filter: {type: AliasFilterType.entityType, entityType: EntityType.DEVICE}};
    const dialog = fakeDialog(of(edited));
    const component = newComponent(config, dialog);

    component.editAlias(component.aliases[0]);

    expect(dialog.open).toHaveBeenCalledWith(EntityAliasDialogComponent, jasmine.objectContaining({
      data: jasmine.objectContaining({isAdd: false})
    }));
    expect(config.entityAliases.length).toBe(1);
    expect(config.entityAliases[0].alias).toBe('Devices renamed');
    expect(component.aliases[0].alias).toBe('Devices renamed');
  });

  it('deleteAlias() removes the entry from config.entityAliases without opening a dialog', () => {
    const config = configWithOneAlias();
    const dialog = fakeDialog(of(null));
    const component = newComponent(config, dialog);

    component.deleteAlias(component.aliases[0]);

    expect(dialog.open).not.toHaveBeenCalled();
    expect(config.entityAliases.length).toBe(0);
    expect(component.aliases.length).toBe(0);
  });
});
