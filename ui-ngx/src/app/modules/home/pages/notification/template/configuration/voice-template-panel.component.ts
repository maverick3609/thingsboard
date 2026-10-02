// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Input } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ValidationErrors, Validators } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { InferrixVoiceService } from '@core/http/inferrix-voice.service';
import {
  placeholdersMatch,
  VOICE_MAX_LENGTH,
  VOICE_TRANSLATED_LANGUAGES
} from '@shared/models/inferrix-voice.models';

/** The VOICE delivery method's form. TB's template editor builds it beside its other methods. */
export function voiceTemplateForm(fb: FormBuilder): FormGroup {
  return fb.group({
    body: ['', [Validators.required, Validators.maxLength(VOICE_MAX_LENGTH)]],
    localizedBodies: fb.group(Object.fromEntries(
      VOICE_TRANSLATED_LANGUAGES.map(({code}) => [code, ['', Validators.maxLength(VOICE_MAX_LENGTH)]])
    )),
    localizedSource: [''],
    ackRequired: [false]
  }, {validators: voicePlaceholders});
}

/** A translation that lost or gained a placeholder would not say which device or what value: it cannot be saved. */
function voicePlaceholders(form: AbstractControl): ValidationErrors | null {
  const body = form.get('body').value;
  const lost = VOICE_TRANSLATED_LANGUAGES.some(({code}) => {
    const text = form.get(['localizedBodies', code]).value;
    return !!text && !placeholdersMatch(body, text);
  });
  return lost ? {voicePlaceholders: true} : null;
}

@Component({
  selector: 'tb-voice-template-panel',
  templateUrl: './voice-template-panel.component.html',
  styleUrls: ['./voice-template-panel.component.scss'],
  standalone: false
})
export class VoiceTemplatePanelComponent {

  @Input() form: FormGroup;

  readonly languages = VOICE_TRANSLATED_LANGUAGES;
  translating = false;
  /** The last translate was answered with no translation in it. The next translate resets it. */
  private lastAnswerEmpty = false;

  constructor(private voiceService: InferrixVoiceService) {}

  /** A disabled control is never `valid`, so a read-only template cannot translate either. */
  get canTranslate(): boolean {
    return !this.translating && this.form.get('body').valid;
  }

  /** Whether any language has text, however it got there. */
  private get hasTranslation(): boolean {
    return this.languages.some(({code}) => !!this.form.get(['localizedBodies', code]).value);
  }

  /**
   * Machine-translated before, the English has changed since, and a translation is still there to be out of date.
   * Hand-written translations never count.
   */
  get stale(): boolean {
    const source = this.form.get('localizedSource').value;
    return !!source && source !== this.form.get('body').value && this.hasTranslation;
  }

  /** The last translate brought nothing, and there is still no text to show for it: typing some by hand ends the message. */
  get nothingCameBack(): boolean {
    return this.lastAnswerEmpty && !this.hasTranslation;
  }

  placeholdersLost(code: string): boolean {
    const text = this.form.get(['localizedBodies', code]).value;
    return !!text && !placeholdersMatch(this.form.get('body').value, text);
  }

  translate() {
    if (!this.canTranslate) {
      return;
    }
    const body: string = this.form.get('body').value;
    this.translating = true;
    this.lastAnswerEmpty = false;
    this.voiceService.translate(body).pipe(
      finalize(() => this.translating = false)
    ).subscribe(({translations}) => {
      let filled = false;
      for (const {code} of this.languages) {
        const text = translations?.[code]?.text;
        if (text) {
          const control = this.form.get(['localizedBodies', code]);
          control.setValue(text);
          // An untouched field shows no error, and a translation can come back over the limit
          control.markAsTouched();
          filled = true;
        }
      }
      if (filled) {
        this.form.get('localizedSource').setValue(body);
        this.form.markAsDirty();
      } else {
        // The stored text, and the English it came from, stay as they were: only the admin is told
        this.lastAnswerEmpty = true;
      }
    });
  }

}
