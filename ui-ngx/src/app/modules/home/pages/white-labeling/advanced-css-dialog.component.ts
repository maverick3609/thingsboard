// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
