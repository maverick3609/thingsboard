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
