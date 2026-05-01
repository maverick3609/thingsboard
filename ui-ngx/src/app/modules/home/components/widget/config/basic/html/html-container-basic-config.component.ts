///
/// Copyright © 2016-2026 The Thingsboard Authors
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

import { ChangeDetectorRef, Component, Injector } from '@angular/core';
import { UntypedFormArray, UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { BasicWidgetConfigComponent } from '@home/components/widget/config/widget-config.component.models';
import { WidgetConfigComponentData } from '@home/models/widget-component.models';
import { WidgetConfigComponent } from '@home/components/widget/widget-config.component';
import {
  ContainerFunctionEditorCompleter,
  htmlContainerDefaultSettings,
  HtmlContainerWidgetSettings, HtmlContainerWidgetType
} from '@home/components/widget/lib/html/html-container-widget.models';
import { WidgetService } from '@core/http/widget.service';
import { WidgetResource } from '@shared/models/widget.models';
import { isJSResource, ResourceSubType } from '@shared/models/resource.models';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

@Component({
  selector: 'tb-html-container-basic-config',
  templateUrl: './html-container-basic-config.component.html',
  styleUrls: ['../basic-config.scss'],
  standalone: false
})
export class HtmlContainerBasicConfigComponent extends BasicWidgetConfigComponent {

  HtmlContainerWidgetType = HtmlContainerWidgetType;

  htmlContainerWidgetConfigForm: UntypedFormGroup;

  functionScopeVariables = this.widgetService.getWidgetScopeVariables();

  containerFunctionEditorCompleter = ContainerFunctionEditorCompleter;

  get resourcesFormArray(): UntypedFormArray {
    return this.htmlContainerWidgetConfigForm.get('resources') as UntypedFormArray;
  }

  get resourcesControls(): UntypedFormGroup[] {
    return this.resourcesFormArray.controls as UntypedFormGroup[];
  }

  constructor(protected store: Store<AppState>,
              protected widgetConfigComponent: WidgetConfigComponent,
              private widgetService: WidgetService,
              private cd: ChangeDetectorRef,
              private $injector: Injector,
              private fb: UntypedFormBuilder) {
    super(store, widgetConfigComponent);
  }

  protected configForm(): UntypedFormGroup {
    return this.htmlContainerWidgetConfigForm;
  }

  protected onConfigSet(configData: WidgetConfigComponentData) {
    const settings: HtmlContainerWidgetSettings = {...htmlContainerDefaultSettings, ...(configData.config.settings || {})};
    this.htmlContainerWidgetConfigForm = this.fb.group({
      type: [settings.type, []],
      html: [settings.html, []],
      css: [settings.css, []],
      js: [settings.js, []],
      resources: this.fb.array(settings.resources.map(r => this.buildResourceFormGroup(r)))
    });
    this.htmlContainerWidgetConfigForm.get('type').valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => this.updateResources());
  }

  protected prepareOutputConfig(config: any): WidgetConfigComponentData {
    this.widgetConfig.config.settings = this.widgetConfig.config.settings || {};
    this.widgetConfig.config.settings.type = config.type;
    this.widgetConfig.config.settings.html = config.html;
    this.widgetConfig.config.settings.css = config.css;
    this.widgetConfig.config.settings.js = config.js;
    this.widgetConfig.config.settings.resources = config.resources;
    return this.widgetConfig;
  }

  private updateResources() {
    if (this.htmlContainerWidgetConfigForm.get('type').value === HtmlContainerWidgetType.PLAIN) {
      const resources: WidgetResource[] = this.resourcesFormArray.value;
      const filtered = resources.filter(r => !isJSResource(r.url));
      let updated = filtered.length !== resources.length;
      filtered.forEach((r) => {
        if (r.isModule) {
          r.isModule = false;
          updated = true;
        }
      });
      if (updated) {
        this.resourcesFormArray.clear();
        filtered.forEach(r => {
          this.resourcesFormArray.push(this.buildResourceFormGroup(r));
        });
      }
    }
  }

  addResource() {
    const newResource: WidgetResource = {
      url: '',
      isModule: false
    };
    this.resourcesFormArray.push(this.buildResourceFormGroup(newResource));
  }

  removeResource(index: number) {
    this.resourcesFormArray.removeAt(index);
  }

  private buildResourceFormGroup(resource: WidgetResource): UntypedFormGroup {
    return this.fb.group({
      url: [resource.url, [Validators.required]],
      isModule: [resource.isModule]
    });
  }

  protected readonly ResourceSubType = ResourceSubType;
}
