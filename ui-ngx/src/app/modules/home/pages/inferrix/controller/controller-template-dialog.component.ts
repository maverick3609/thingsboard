// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { DialogComponent } from '@shared/components/dialog.component';
import { AppState } from '@core/core.state';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { ControllerTemplate, ControllerTemplateSummary } from '@shared/models/inferrix-controller.models';
import { LogicProgram } from '@shared/models/inferrix-logic.models';

export interface ControllerTemplateDialogData {
  /** 'save' names and stores what the caller has already read; 'apply' hands one back to the caller. */
  mode: 'save' | 'apply';
  /** What a template must carry to be worth offering, and what a save is storing. */
  kind: 'config' | 'logic';
  /** Suggested name in save mode: the controller and today's date. */
  suggestedName?: string;
  sourceName?: string;
  config?: {[sectionKey: string]: any[]};
  logic?: LogicProgram;
}

/**
 * The saved-configuration picker: one dialog for storing a template and for choosing one.
 *
 * It does not touch a controller in either direction. In 'save' mode the caller has already read
 * what is being stored; in 'apply' mode the dialog closes with the chosen template and the caller —
 * which owns the device connection, its progress display and its draft — writes it.
 */
@Component({
  selector: 'tb-controller-template-dialog',
  templateUrl: './controller-template-dialog.component.html',
  styleUrls: ['./controller-table.scss'],
  standalone: false
})
export class ControllerTemplateDialogComponent extends DialogComponent<ControllerTemplateDialogComponent,
  ControllerTemplate> {

  readonly columns = ['name', 'holds', 'createdTime', 'actions'];

  templates: ControllerTemplateSummary[] = [];
  name = '';
  loading = false;
  errorMessage: string;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ControllerTemplateDialogData,
              public dialogRef: MatDialogRef<ControllerTemplateDialogComponent, ControllerTemplate>,
              private controllerService: InferrixControllerService,
              private translate: TranslateService) {
    super(store, router, dialogRef);
    this.name = data.suggestedName || '';
    this.load();
  }

  get isSave(): boolean {
    return this.data.mode === 'save';
  }

  /** A logic picker offers only templates that hold a program, and a config picker only those with records. */
  get offered(): ControllerTemplateSummary[] {
    return this.data.kind === 'logic'
      ? this.templates.filter(template => template.hasLogic)
      : this.templates.filter(template => template.recordCount > 0);
  }

  /** Saving over an existing name replaces it, so the operator is told before it happens. */
  get replaces(): boolean {
    const name = this.name.trim();
    return this.isSave && !!name && this.templates.some(template => template.name === name);
  }

  load(): void {
    this.loading = true;
    this.controllerService.getControllerTemplates({ignoreErrors: true}).subscribe({
      next: templates => {
        this.templates = templates || [];
        this.loading = false;
      },
      error: error => {
        this.errorMessage = this.messageOf(error);
        this.loading = false;
      }
    });
  }

  save(): void {
    const name = this.name.trim();
    if (!name) {
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.controllerService.saveControllerTemplate({
      name,
      sourceName: this.data.sourceName,
      config: this.data.config,
      logic: this.data.logic
    }, {ignoreErrors: true}).subscribe({
      next: template => this.dialogRef.close(template),
      error: error => {
        this.errorMessage = this.messageOf(error);
        this.loading = false;
      }
    });
  }

  /** The list carries no payload, so the one being applied is fetched here. */
  apply(summary: ControllerTemplateSummary): void {
    this.loading = true;
    this.errorMessage = null;
    this.controllerService.getControllerTemplate(summary.id, {ignoreErrors: true}).subscribe({
      next: template => this.dialogRef.close(template),
      error: error => {
        this.errorMessage = this.messageOf(error);
        this.loading = false;
      }
    });
  }

  delete(summary: ControllerTemplateSummary, event: MouseEvent): void {
    event.stopPropagation();
    this.loading = true;
    this.errorMessage = null;
    this.controllerService.deleteControllerTemplate(summary.id, {ignoreErrors: true}).subscribe({
      next: () => this.load(),
      error: error => {
        this.errorMessage = this.messageOf(error);
        this.loading = false;
      }
    });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  private messageOf(error: any): string {
    return error?.error?.message || error?.message || this.translate.instant('inferrix.template-failed');
  }

}
