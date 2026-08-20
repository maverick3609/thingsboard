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

@Component({
  standalone: false,
  selector: 'tb-advanced-css-dialog',
  templateUrl: './advanced-css-dialog.component.html'
})
export class AdvancedCssDialogComponent {

  css: string;

  constructor(@Inject(MAT_DIALOG_DATA) public data: { css: string },
              private dialogRef: MatDialogRef<AdvancedCssDialogComponent>) {
    this.css = data.css;
  }

  save(): void {
    // '' and null both mean "no custom css"; normalise so the dirty check stays honest
    this.dialogRef.close(this.css || null);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
