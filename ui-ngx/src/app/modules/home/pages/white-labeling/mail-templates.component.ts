// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TranslateService } from '@ngx-translate/core';
import { EditorOptions } from 'hugerte';
import { defaultHugeRteOptions } from '@shared/models/hugerte/hugerte.models';
import { WhiteLabelingHttpService } from '@core/http/white-labeling.service';
import { MailTemplates, mailTemplateTranslations } from '@shared/models/white-labeling.models';

@Component({
  standalone: false,
  selector: 'tb-mail-templates',
  templateUrl: './mail-templates.component.html',
  styleUrls: ['./white-labeling.component.scss']
})
export class MailTemplatesComponent implements OnInit {

  form: FormGroup;
  templateNames: string[] = [];
  selectedTemplate: string;
  loading = false;

  readonly mailTemplateTranslations = mailTemplateTranslations;

  // hugerte since the 2026-09-03 upstream merge (tinymce removed for CVE-2025-71329/71330);
  // defaultHugeRteOptions supplies base_url/suffix/autofocus/branding, and `promotion` has no
  // hugerte counterpart (there is no upsell to disable).
  hugeRteOptions: Partial<EditorOptions> = defaultHugeRteOptions({
    plugins: ['link', 'table', 'image', 'lists', 'code', 'fullscreen'],
    menubar: 'edit insert tools view format table',
    toolbar: 'undo redo | fontfamily fontsize blocks | bold italic strikethrough | forecolor backcolor ' +
      '| link table image | alignleft aligncenter alignright alignjustify ' +
      '| numlist bullist | outdent indent | removeformat | code | fullscreen',
    toolbar_mode: 'sliding',
    height: 500
  });

  private templates: MailTemplates = {};

  constructor(private fb: FormBuilder,
              private wlHttp: WhiteLabelingHttpService,
              private snackBar: MatSnackBar,
              private translate: TranslateService) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      subject: [null],
      body: [null]
    });
    // keep edits of the currently selected template so switching templates does not lose them.
    // No dirty flag: the editor re-emits its own normalised html right after writeValue, so a
    // dirty-gated Save would be enabled from load anyway (PE's mail tab behaves the same).
    this.form.valueChanges.subscribe(value => {
      if (this.selectedTemplate) {
        this.templates[this.selectedTemplate] = { subject: value.subject, body: value.body };
      }
    });
    this.load();
  }

  selectTemplate(name: string): void {
    this.selectedTemplate = name;
    const template = this.templates[name];
    this.form.patchValue({ subject: template?.subject, body: template?.body }, { emitEvent: false });
  }

  save(): void {
    this.loading = true;
    this.wlHttp.saveMailTemplates(this.templates).subscribe({
      next: () => {
        this.loading = false;
        this.snackBar.open(this.translate.instant('white-labeling.mail-templates-saved'),
          this.translate.instant('action.ok'), { duration: 3000 });
      },
      error: () => this.loading = false
    });
  }

  private load(): void {
    this.loading = true;
    this.wlHttp.getMailTemplates().subscribe({
      next: templates => {
        this.templates = templates || {};
        this.templateNames = Object.keys(this.templates);
        this.loading = false;
        this.selectTemplate(this.templateNames[0]);
      },
      error: () => this.loading = false
    });
  }
}
