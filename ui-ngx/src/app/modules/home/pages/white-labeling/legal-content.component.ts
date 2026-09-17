// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';
import { FormBuilder, FormGroup } from '@angular/forms';

@Component({
  standalone: false,
  selector: 'tb-legal-content',
  templateUrl: './legal-content.component.html'
})
export class LegalContentComponent implements OnInit, OnChanges {
  @Input() content: any;
  @Input() label = 'Content';
  @Output() contentChange = new EventEmitter<any>();
  @Output() save = new EventEmitter<void>();

  form: FormGroup;

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      content: ['']
    });
    this.patchForm();
    this.form.valueChanges.subscribe(() => {
      this.contentChange.emit({ content: this.form.value.content });
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.content && this.form) {
      this.patchForm();
    }
  }

  onSave(): void {
    this.save.emit();
  }

  private patchForm(): void {
    const text = this.content?.content || '';
    this.form.patchValue({ content: text }, { emitEvent: false });
  }
}
