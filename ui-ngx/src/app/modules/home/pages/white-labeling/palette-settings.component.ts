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

import { Component, forwardRef } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { TranslateService } from '@ngx-translate/core';
import { Palette, PaletteSettings } from '@shared/models/white-labeling.models';
import { PaletteDialogComponent, PaletteDialogData } from './palette-dialog.component';

@Component({
  standalone: false,
  selector: 'tb-palette-settings',
  templateUrl: './palette-settings.component.html',
  styleUrls: ['./white-labeling.component.scss'],
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => PaletteSettingsComponent),
    multi: true
  }]
})
export class PaletteSettingsComponent implements ControlValueAccessor {

  // material palette name -> its 500 shade, used to preview the palette in the selector
  readonly paletteColors: { [name: string]: string } = {
    red: '#F44336', pink: '#E91E63', purple: '#9C27B0', 'deep-purple': '#673AB7',
    indigo: '#3F51B5', blue: '#2196F3', 'light-blue': '#03A9F4', cyan: '#00BCD4',
    teal: '#009688', green: '#4CAF50', 'light-green': '#8BC34A', lime: '#CDDC39',
    yellow: '#FFEB3B', amber: '#FFC107', orange: '#FF9800', brown: '#795548',
    grey: '#9E9E9E', 'blue-grey': '#607D8B'
  };

  readonly paletteOptions = Object.keys(this.paletteColors);

  value: PaletteSettings = null;
  disabled = false;

  private onChange: (val: PaletteSettings) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(private dialog: MatDialog,
              private translate: TranslateService) {}

  writeValue(val: PaletteSettings): void {
    this.value = val || null;
  }

  registerOnChange(fn: (val: PaletteSettings) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  palette(type: 'primary' | 'accent'): Palette {
    return type === 'primary' ? this.value?.primaryPalette : this.value?.accentPalette;
  }

  setExtends(type: 'primary' | 'accent', extendsPalette: string): void {
    this.update(type, { extendsPalette, colors: this.palette(type)?.colors });
  }

  customize(type: 'primary' | 'accent'): void {
    const labelKey = type === 'primary' ? 'white-labeling.primary-palette' : 'white-labeling.accent-palette';
    this.dialog.open<PaletteDialogComponent, PaletteDialogData, { [shade: string]: string }>(
      PaletteDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          title: this.translate.instant(labelKey),
          colors: this.palette(type)?.colors
        }
      }).afterClosed().subscribe(colors => {
        if (colors) {
          this.update(type, { extendsPalette: this.palette(type)?.extendsPalette, colors });
        }
      });
  }

  private update(type: 'primary' | 'accent', changes: { extendsPalette: string; colors: { [shade: string]: string } }): void {
    const colors = changes.colors && Object.keys(changes.colors).length ? changes.colors : null;
    const palette: Palette = { type: null, extendsPalette: changes.extendsPalette || null, colors };
    this.value = {
      primaryPalette: type === 'primary' ? palette : this.value?.primaryPalette,
      accentPalette: type === 'accent' ? palette : this.value?.accentPalette
    };
    this.onChange(this.value);
    this.onTouched();
  }
}
