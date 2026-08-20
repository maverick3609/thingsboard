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

import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

export interface PaletteDialogData {
  title: string;
  colors: { [shade: string]: string };
}

@Component({
  standalone: false,
  selector: 'tb-palette-dialog',
  templateUrl: './palette-dialog.component.html',
  styleUrls: ['./white-labeling.component.scss']
})
export class PaletteDialogComponent {

  readonly shades = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900',
    'A100', 'A200', 'A400', 'A700'];

  colors: { [shade: string]: string };

  constructor(@Inject(MAT_DIALOG_DATA) public data: PaletteDialogData,
              private dialogRef: MatDialogRef<PaletteDialogComponent>) {
    this.colors = { ...(data.colors || {}) };
  }

  save(): void {
    this.dialogRef.close(this.colors);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
