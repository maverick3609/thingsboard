// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormBuilder } from '@angular/forms';
import { Observable, of, Subject } from 'rxjs';
import { VoiceTranslateResponse } from '@shared/models/inferrix-voice.models';
import {
  voiceTemplateForm,
  VoiceTemplatePanelComponent
} from '@home/pages/notification/template/configuration/voice-template-panel.component';

describe('VoiceTemplatePanelComponent', () => {

  const english = 'Alarm on ${alarmOriginatorName}.';
  let translated: string[];
  let answer: Observable<VoiceTranslateResponse>;
  let panel: VoiceTemplatePanelComponent;

  beforeEach(() => {
    translated = [];
    answer = of({translations: {
      hi: {text: 'अलार्म ${alarmOriginatorName}', placeholdersOk: true}
    }});
    const service = {translate: (text: string) => { translated.push(text); return answer; }};
    panel = new VoiceTemplatePanelComponent(service as any);
    panel.form = voiceTemplateForm(new FormBuilder());
    panel.form.get('body').setValue(english);
  });

  it('fills the Hindi translation and remembers what it was translated from', () => {
    panel.translate();

    expect(translated).toEqual([english]);
    expect(panel.form.get('localizedBodies').value).toEqual({hi: 'अलार्म ${alarmOriginatorName}'});
    expect(panel.form.get('localizedSource').value).toBe(english);
    expect(panel.form.dirty).toBeTrue();
    expect(panel.translating).toBeFalse();
    expect(panel.stale).toBeFalse();
  });

  it('says the translations are stale once the English changes', () => {
    panel.translate();
    panel.form.get('body').setValue('Fire alarm on ${alarmOriginatorName}.');

    expect(panel.stale).toBeTrue();
  });

  it('never calls hand-written translations stale', () => {
    panel.form.get('localizedBodies.hi').setValue('अलार्म ${alarmOriginatorName}');

    expect(panel.stale).toBeFalse();
  });

  it('refuses to save a translation whose placeholders differ', () => {
    panel.form.get('localizedBodies.hi').setValue('अलार्म');

    expect(panel.placeholdersLost('hi')).toBeTrue();
    expect(panel.form.hasError('voicePlaceholders')).toBeTrue();
    expect(panel.form.valid).toBeFalse();
  });

  it('keeps a translation the dialer did not return', () => {
    panel.form.get('localizedBodies.hi').setValue('हाथ से लिखा ${alarmOriginatorName}');
    answer = of({translations: {}});

    panel.translate();

    expect(panel.form.get('localizedBodies.hi').value).toBe('हाथ से लिखा ${alarmOriginatorName}');
  });

  it('stays stale when the dialer returns nothing for an old translation', () => {
    panel.translate();
    panel.form.get('body').setValue('Fire alarm on ${alarmOriginatorName}.');
    answer = of({translations: {}});

    panel.translate();

    expect(panel.form.get('localizedSource').value).toBe(english);
    expect(panel.stale).toBeTrue();
  });

  it('does not call hand-written text a translation when the dialer returns nothing', () => {
    panel.form.get('localizedBodies.hi').setValue('हाथ से लिखा ${alarmOriginatorName}');
    answer = of({translations: {}});

    panel.translate();

    expect(panel.form.get('localizedSource').value).toBe('');
    expect(panel.form.dirty).toBeFalse();
  });

  it('shows the length error of a translation that came back too long', () => {
    answer = of({translations: {hi: {text: 'अ'.repeat(600) + ' ${alarmOriginatorName}', placeholdersOk: true}}});

    panel.translate();

    expect(panel.form.get('localizedBodies.hi').hasError('maxlength')).toBeTrue();
    expect(panel.form.get('localizedBodies.hi').touched).toBeTrue();
  });

  it('cannot translate an empty message, or twice at once', () => {
    expect(panel.canTranslate).toBeTrue();
    panel.form.get('body').setValue('');
    expect(panel.canTranslate).toBeFalse();

    panel.form.get('body').setValue(english);
    const pending = new Subject<VoiceTranslateResponse>();
    answer = pending;
    panel.translate();
    expect(panel.canTranslate).toBeFalse();
    pending.complete();
    expect(panel.translating).toBeFalse();
  });

});
