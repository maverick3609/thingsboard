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

// Plain instantiation (no TestBed). The component takes its ReportAliasCallbacksFactory as a plain
// constructor param (@Inject(REPORT_ALIAS_CALLBACKS_FACTORY)), so a spec can hand it a fake factory
// directly with no DI container at all. This - not TestBed - is what matters for karma-runnability:
// the component's own imports never reach EntityAliasDialogComponent/FilterDialogComponent (only the
// dialog-class-agnostic report-alias-callbacks.models.ts), so this spec never does either. Whether
// "Add alias" really opens EntityAliasDialogComponent with the right data, and the keyFilters
// conversion for filters, is proven separately in report-alias-callbacks.spec.ts (which tests the
// exact same data-flow code, parametrized by a fake dialog class instead of a fake callbacks object -
// see that file's header). This spec's job is the component's OWN wiring: config -> aliasController,
// list rendering, and add/edit/delete correctly delegating to (and reacting to) the injected
// callbacks.
import { of } from 'rxjs';
import { ReportEntityAliasesComponent } from './report-entity-aliases.component';
import { newPdfReportTemplateConfig, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { AliasFilterType, EntityAlias } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { ReportAliasCallbacks, ReportAliasCallbacksFactory } from './report-alias-callbacks.models';

function configWithOneAlias(): ReportTemplateConfigModel {
  return {
    ...newPdfReportTemplateConfig(),
    entityAliases: [
      {id: 'a1', alias: 'Devices', filter: {type: AliasFilterType.entityType, entityType: EntityType.DEVICE}}
    ]
  };
}

function newComponent(config: ReportTemplateConfigModel): {
  component: ReportEntityAliasesComponent;
  callbacks: jasmine.SpyObj<ReportAliasCallbacks>;
} {
  const callbacks: jasmine.SpyObj<ReportAliasCallbacks> =
    jasmine.createSpyObj('ReportAliasCallbacks', ['createEntityAlias', 'editEntityAlias', 'createFilter', 'editFilter']);
  callbacks.createEntityAlias.and.returnValue(of(null));
  callbacks.editEntityAlias.and.returnValue(of(null));
  const factory: ReportAliasCallbacksFactory = () => callbacks;
  const component = new ReportEntityAliasesComponent(factory);
  component.config = config;
  component.ngOnInit();
  return {component, callbacks};
}

describe('ReportEntityAliasesComponent', () => {

  it('lists config.entityAliases on init', () => {
    const {component} = newComponent(configWithOneAlias());

    expect(component.aliases.length).toBe(1);
    expect(component.aliases[0].alias).toBe('Devices');
  });

  it('addAlias() calls callbacks.createEntityAlias(), and when it resolves the returned EntityAlias is appended to config.entityAliases', () => {
    const config = configWithOneAlias();
    const {component, callbacks} = newComponent(config);
    const newAlias: EntityAlias = {id: 'a2', alias: 'Assets', filter: {type: AliasFilterType.entityType, entityType: EntityType.ASSET}};
    // Mimics what the real factory (report-alias-callbacks.ts, proven separately) does on success:
    // writes the new alias into config. This spec is about the component reacting correctly to that
    // outcome (re-reading config via aliasController and refreshing its list), not re-proving the
    // write-back logic itself.
    callbacks.createEntityAlias.and.callFake(() => {
      config.entityAliases = [...config.entityAliases, newAlias];
      return of(newAlias);
    });

    component.addAlias();

    expect(callbacks.createEntityAlias).toHaveBeenCalledWith('', undefined);
    expect(config.entityAliases.length).toBe(2);
    expect(component.aliases.length).toBe(2);
    expect(component.aliases.find(a => a.id === 'a2').alias).toBe('Assets');
  });

  it('addAlias() does not refresh (or change config) when the dialog is cancelled (callbacks resolves null)', () => {
    const config = configWithOneAlias();
    const {component, callbacks} = newComponent(config);

    component.addAlias();

    expect(callbacks.createEntityAlias).toHaveBeenCalled();
    expect(config.entityAliases.length).toBe(1);
    expect(component.aliases.length).toBe(1);
  });

  it('editAlias() calls callbacks.editEntityAlias() with the selected alias, and when it resolves the result replaces the entry in config', () => {
    const config = configWithOneAlias();
    const {component, callbacks} = newComponent(config);
    const edited: EntityAlias = {id: 'a1', alias: 'Devices renamed', filter: {type: AliasFilterType.entityType, entityType: EntityType.DEVICE}};
    callbacks.editEntityAlias.and.callFake(() => {
      config.entityAliases = config.entityAliases.map(a => a.id === 'a1' ? edited : a);
      return of(edited);
    });

    component.editAlias(component.aliases[0]);

    expect(callbacks.editEntityAlias).toHaveBeenCalledWith(jasmine.objectContaining({id: 'a1', alias: 'Devices'}), undefined);
    expect(config.entityAliases[0].alias).toBe('Devices renamed');
    expect(component.aliases[0].alias).toBe('Devices renamed');
  });

  it('deleteAlias() removes the entry from config.entityAliases without calling any callback', () => {
    const config = configWithOneAlias();
    const {component, callbacks} = newComponent(config);

    component.deleteAlias(component.aliases[0]);

    expect(callbacks.createEntityAlias).not.toHaveBeenCalled();
    expect(callbacks.editEntityAlias).not.toHaveBeenCalled();
    expect(config.entityAliases.length).toBe(0);
    expect(component.aliases.length).toBe(0);
  });
});
