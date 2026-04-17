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

import { Component, forwardRef, OnInit } from '@angular/core';
import { ControlValueAccessor, FormBuilder, FormGroup, NG_VALUE_ACCESSOR } from '@angular/forms';
import { PaletteSettings } from '@shared/models/white-labeling.models';

@Component({
  selector: 'tb-palette-settings',
  templateUrl: './palette-settings.component.html',
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => PaletteSettingsComponent),
    multi: true
  }]
})
export class PaletteSettingsComponent implements ControlValueAccessor, OnInit {
  form: FormGroup;
  disabled = false;

  readonly shades = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', 'A100', 'A200', 'A400', 'A700'];
  readonly paletteOptions = [
    'indigo', 'blue', 'red', 'green', 'purple', 'orange', 'teal', 'pink',
    'cyan', 'amber', 'deep-purple', 'light-blue', 'lime', 'yellow',
    'light-green', 'brown', 'grey', 'blue-grey'
  ];

  private onChange: (val: PaletteSettings) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    const controls: { [key: string]: any } = {
      primaryExtends: ['indigo'],
      accentExtends: ['pink']
    };
    for (const shade of this.shades) {
      controls['primary_' + shade] = [''];
      controls['accent_' + shade] = [''];
    }
    this.form = this.fb.group(controls);
    this.form.valueChanges.subscribe(() => this.emitValue());
  }

  writeValue(val: PaletteSettings): void {
    if (!val || !this.form) { return; }
    const patch: { [key: string]: any } = {};
    if (val.primaryPalette) {
      patch.primaryExtends = val.primaryPalette.extendsPalette || 'indigo';
      if (val.primaryPalette.colors) {
        for (const shade of this.shades) {
          patch['primary_' + shade] = val.primaryPalette.colors[shade] || '';
        }
      }
    }
    if (val.accentPalette) {
      patch.accentExtends = val.accentPalette.extendsPalette || 'pink';
      if (val.accentPalette.colors) {
        for (const shade of this.shades) {
          patch['accent_' + shade] = val.accentPalette.colors[shade] || '';
        }
      }
    }
    this.form.patchValue(patch, { emitEvent: false });
  }

  registerOnChange(fn: (val: PaletteSettings) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    isDisabled ? this.form.disable({ emitEvent: false }) : this.form.enable({ emitEvent: false });
  }

  private emitValue(): void {
    const v = this.form.value;
    const primaryColors: { [key: string]: string } = {};
    const accentColors: { [key: string]: string } = {};
    for (const shade of this.shades) {
      if (v['primary_' + shade]) { primaryColors[shade] = v['primary_' + shade]; }
      if (v['accent_' + shade]) { accentColors[shade] = v['accent_' + shade]; }
    }
    const settings: PaletteSettings = {
      primaryPalette: {
        type: null,
        extendsPalette: v.primaryExtends || null,
        colors: Object.keys(primaryColors).length > 0 ? primaryColors : null
      },
      accentPalette: {
        type: null,
        extendsPalette: v.accentExtends || null,
        colors: Object.keys(accentColors).length > 0 ? accentColors : null
      }
    };
    this.onChange(settings);
    this.onTouched();
  }
}
