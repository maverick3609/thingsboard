// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
