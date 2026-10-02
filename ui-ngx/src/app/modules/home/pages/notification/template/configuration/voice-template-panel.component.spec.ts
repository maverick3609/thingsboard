// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ComponentFixture, fakeAsync, flush, TestBed } from '@angular/core/testing';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslateModule } from '@ngx-translate/core';
import { config, Observable, of, Subject, throwError } from 'rxjs';
import { InferrixVoiceService } from '@core/http/inferrix-voice.service';
import { VOICE_TRANSLATED_LANGUAGES, VoiceTranslateResponse } from '@shared/models/inferrix-voice.models';
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

  afterEach(() => {
    config.onUnhandledError = null;
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

  it('accepts a translation whose placeholders match', () => {
    panel.form.get('localizedBodies.hi').setValue('अलार्म ${alarmOriginatorName}');

    expect(panel.placeholdersLost('hi')).toBeFalse();
    expect(panel.form.hasError('voicePlaceholders')).toBeFalse();
    expect(panel.form.valid).toBeTrue();
  });

  it('can translate again after the request fails', fakeAsync(() => {
    const unhandled: any[] = [];
    config.onUnhandledError = error => unhandled.push(error);
    answer = throwError(() => new Error('Voice calls are not enabled'));

    panel.translate();
    flush();

    expect(panel.translating).toBeFalse();
    expect(panel.canTranslate).toBeTrue();
    expect(panel.nothingCameBack).toBeFalse();
    // The panel has no handler: the HTTP interceptor shows the message, RxJS reports the rest
    expect(unhandled.map(error => error.message)).toEqual(['Voice calls are not enabled']);
  }));

  it('does not blank hand-written text with an empty translation', () => {
    panel.form.get('localizedBodies.hi').setValue('हाथ से लिखा ${alarmOriginatorName}');
    answer = of({translations: {hi: {text: '', placeholdersOk: true}}});

    panel.translate();

    expect(panel.form.get('localizedBodies.hi').value).toBe('हाथ से लिखा ${alarmOriginatorName}');
  });

  it('sends one request however often translate is called', () => {
    answer = new Subject<VoiceTranslateResponse>();

    panel.translate();
    panel.translate();

    expect(translated).toEqual([english]);
  });

  it('sends nothing for a message that cannot be translated', () => {
    panel.form.get('body').setValue('');

    panel.translate();

    expect(translated).toEqual([]);
    expect(panel.translating).toBeFalse();
  });

  it('is not stale once the translation was cleared by hand', () => {
    panel.translate();
    panel.form.get('localizedBodies.hi').setValue('');
    panel.form.get('body').setValue('Fire alarm on ${alarmOriginatorName}.');

    expect(panel.stale).toBeFalse();
  });

  it('builds a control, and a placeholder check, for every language in the list', () => {
    VOICE_TRANSLATED_LANGUAGES.push({code: 'xx', name: 'notification.voice-xx'});
    try {
      const form = voiceTemplateForm(new FormBuilder());
      form.get('body').setValue(english);
      const bodies = form.get('localizedBodies') as FormGroup;

      expect(Object.keys(bodies.controls)).toEqual(['hi', 'xx']);
      bodies.get('xx').setValue('x'.repeat(601));
      expect(bodies.get('xx').hasError('maxlength')).toBeTrue();
      bodies.get('xx').setValue('no placeholder');
      expect(form.hasError('voicePlaceholders')).toBeTrue();
    } finally {
      VOICE_TRANSLATED_LANGUAGES.pop();
    }
  });

  it('says so when nothing came back, until the next translate', () => {
    answer = of({translations: {}});

    panel.translate();

    expect(panel.nothingCameBack).toBeTrue();
    expect(panel.form.dirty).toBeFalse();
    expect(panel.form.get('localizedBodies.hi').touched).toBeFalse();
    expect(panel.form.get('localizedSource').value).toBe('');

    answer = new Subject<VoiceTranslateResponse>();
    panel.translate();

    expect(panel.nothingCameBack).toBeFalse();
  });

  it('counts an empty translation as nothing coming back', () => {
    answer = of({translations: {hi: {text: '', placeholdersOk: true}}});

    panel.translate();

    expect(panel.nothingCameBack).toBeTrue();
  });

  it('stops saying so once there is text, and says so again if the text is cleared', () => {
    answer = of({translations: {}});
    panel.translate();
    expect(panel.nothingCameBack).toBeTrue();

    panel.form.get('localizedBodies.hi').setValue('हाथ से लिखा ${alarmOriginatorName}');
    expect(panel.nothingCameBack).toBeFalse();

    panel.form.get('localizedBodies.hi').setValue('');
    expect(panel.nothingCameBack).toBeTrue();
  });

  it('does not say nothing came back while hand-written text stands', () => {
    panel.form.get('localizedBodies.hi').setValue('हाथ से लिखा ${alarmOriginatorName}');
    answer = of({translations: {}});

    panel.translate();

    expect(panel.nothingCameBack).toBeFalse();
  });

  it('does not say nothing came back when a translation did', () => {
    panel.translate();

    expect(panel.nothingCameBack).toBeFalse();
  });

});

describe('VoiceTemplatePanelComponent template', () => {

  const english = 'Alarm on ${alarmOriginatorName}.';
  const hindi = 'अलार्म ${alarmOriginatorName}';
  const red = 'rgb(244, 67, 54)';
  const grey = 'rgb(128, 128, 128)';
  let fixture: ComponentFixture<VoiceTemplatePanelComponent>;
  let panel: VoiceTemplatePanelComponent;
  let root: HTMLElement;
  let requests: string[];
  let answer: Observable<VoiceTranslateResponse>;
  let tbStyle: HTMLStyleElement;

  const translation = (text: string): VoiceTranslateResponse => ({translations: {hi: {text, placeholdersOk: true}}});
  const translateButton = () => root.querySelector<HTMLButtonElement>('button[mat-stroked-button]');
  const textareas = () => Array.from(root.querySelectorAll('textarea'));
  const hint = (key: string) => Array.from(root.querySelectorAll<HTMLElement>('.tb-form-panel-hint'))
    .find(element => element.textContent.includes(key)) ?? null;
  const colour = (element: Element) => getComputedStyle(element).color;
  const type = (textarea: HTMLTextAreaElement, text: string) => {
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  beforeEach(() => {
    requests = [];
    answer = of(translation(hindi));
    TestBed.configureTestingModule({
      declarations: [VoiceTemplatePanelComponent],
      imports: [ReactiveFormsModule, MatInputModule, MatButtonModule, MatSlideToggleModule, TranslateModule.forRoot()],
      providers: [{provide: InferrixVoiceService, useValue: {translate: (text: string) => { requests.push(text); return answer; }}}],
      // Declared here and not in NotificationModule, so a typo in the template must fail the render
      errorOnUnknownElements: true,
      errorOnUnknownProperties: true
    });
    fixture = TestBed.createComponent(VoiceTemplatePanelComponent);
    panel = fixture.componentInstance;
    panel.form = voiceTemplateForm(new FormBuilder());
    root = fixture.nativeElement;
    // TB's grey for every hint (form.scss: `.tb-default .tb-form-panel-hint`), which the warnings must outrank
    tbStyle = document.head.appendChild(document.createElement('style'));
    tbStyle.textContent = '.tb-default .tb-form-panel-hint {color: rgb(128, 128, 128);}';
    document.body.classList.add('tb-default');
    fixture.detectChanges();
  });

  afterEach(() => {
    tbStyle.remove();
    document.body.classList.remove('tb-default');
    config.onUnhandledError = null;
  });

  it('writes what is typed into the form, and shows what the form holds', () => {
    type(textareas()[0], english);
    expect(panel.form.get('body').value).toBe(english);
    type(textareas()[1], hindi);
    expect(panel.form.get('localizedBodies.hi').value).toBe(hindi);

    panel.form.get('body').setValue('Fire. ' + english);
    panel.form.get('localizedBodies.hi').setValue('आग। ' + hindi);
    fixture.detectChanges();

    expect(textareas().map(textarea => textarea.value)).toEqual(['Fire. ' + english, 'आग। ' + hindi]);
  });

  it('binds the toggle to ackRequired', () => {
    const toggle = () => root.querySelector<HTMLButtonElement>('mat-slide-toggle button');

    toggle().click();
    fixture.detectChanges();
    expect(panel.form.get('ackRequired').value).toBeTrue();
    expect(toggle().getAttribute('aria-checked')).toBe('true');

    panel.form.get('ackRequired').setValue(false);
    fixture.detectChanges();
    expect(toggle().getAttribute('aria-checked')).toBe('false');
  });

  it('enables Translate only for a message that can be translated', () => {
    expect(translateButton().disabled).toBeTrue();

    type(textareas()[0], english);
    expect(translateButton().disabled).toBeFalse();

    type(textareas()[0], 'x'.repeat(601));
    expect(translateButton().disabled).toBeTrue();

    type(textareas()[0], english);
    panel.form.disable();
    fixture.detectChanges();
    expect(translateButton().disabled).toBeTrue();
  });

  it('starts one translation for two quick clicks, and enables Translate again afterwards', () => {
    const pending = new Subject<VoiceTranslateResponse>();
    answer = pending;
    panel.form.get('body').setValue(english);
    // As in the app: the view is checked after each event handler, so the second click meets a disabled button
    fixture.autoDetectChanges(true);

    translateButton().click();
    translateButton().click();

    expect(requests).toEqual([english]);
    expect(translateButton().disabled).toBeTrue();

    // An XHR callback runs inside the Angular zone
    fixture.ngZone.run(() => {
      pending.next(translation(hindi));
      pending.complete();
    });
    expect(translateButton().disabled).toBeFalse();
  });

  it('enables Translate again after a failed request', fakeAsync(() => {
    config.onUnhandledError = () => {};
    panel.form.get('body').setValue(english);
    fixture.detectChanges();
    answer = throwError(() => new Error('Voice calls are not enabled'));

    translateButton().click();
    flush();
    fixture.detectChanges();

    expect(translateButton().disabled).toBeFalse();
  }));

  it('shows the stale warning in the error colour, until the translation is cleared', () => {
    panel.form.get('body').setValue(english);
    fixture.detectChanges();
    translateButton().click();
    fixture.detectChanges();
    expect(hint('voice-translation-stale')).toBeNull();

    panel.form.get('body').setValue('Fire. ' + english);
    fixture.detectChanges();
    expect(colour(hint('voice-translation-stale'))).toBe(red);
    expect(hint('voice-translation-stale').getAttribute('aria-live')).toBe('polite'); // a screen reader announces it

    panel.form.get('localizedBodies.hi').setValue('');
    fixture.detectChanges();
    expect(hint('voice-translation-stale')).toBeNull();
  });

  it('shows the lost-placeholder warning in the error colour', () => {
    panel.form.get('body').setValue(english);
    panel.form.get('localizedBodies.hi').setValue('अलार्म');
    fixture.detectChanges();
    expect(colour(hint('voice-placeholders-lost'))).toBe(red);

    panel.form.get('localizedBodies.hi').setValue(hindi);
    fixture.detectChanges();
    expect(hint('voice-placeholders-lost')).toBeNull();
  });

  it('leaves the plain hint in the ordinary hint colour', () => {
    expect(colour(hint('voice-ack-required-hint'))).toBe(grey);
  });

  it('says when nothing came back, until there is text or the next translate', () => {
    panel.form.get('body').setValue(english);
    answer = of({translations: {}});
    fixture.detectChanges();
    expect(hint('voice-translation-none')).toBeNull();

    translateButton().click();
    fixture.detectChanges();
    expect(colour(hint('voice-translation-none'))).toBe(red);
    expect(hint('voice-translation-none').getAttribute('aria-live')).toBe('polite');
    expect(panel.form.dirty).toBeFalse();

    type(textareas()[1], 'हाथ से लिखा ${alarmOriginatorName}');
    expect(hint('voice-translation-none')).toBeNull();

    type(textareas()[1], '');
    expect(hint('voice-translation-none')).not.toBeNull();

    answer = new Subject<VoiceTranslateResponse>();
    translateButton().click();
    fixture.detectChanges();
    expect(hint('voice-translation-none')).toBeNull();
  });

  it('shows the required and length errors of the English once it is touched', () => {
    panel.form.get('body').markAsTouched();
    fixture.detectChanges();
    expect(root.textContent).toContain('notification.message-required');

    type(textareas()[0], 'x'.repeat(601));
    expect(root.textContent).toContain('notification.message-max-length');
    expect(root.textContent).not.toContain('notification.message-required');
  });

  it('shows the length error of a translation that came back over the limit', () => {
    panel.form.get('body').setValue(english);
    answer = of(translation('अ'.repeat(600) + ' ${alarmOriginatorName}'));
    fixture.detectChanges();

    translateButton().click();
    fixture.detectChanges();

    const message = root.querySelectorAll('mat-form-field')[1].querySelector('mat-error');
    expect(message?.textContent).toContain('notification.message-max-length');
    expect(panel.form.valid).toBeFalse();
  });

  it('puts what the dialer returns into the field as text, never as markup', () => {
    const hostile = '<img src=x onerror="window.__voiceXss=1"><script>window.__voiceXss=2</script> ${alarmOriginatorName}';
    panel.form.get('body').setValue(english);
    answer = of(translation(hostile));
    fixture.detectChanges();

    translateButton().click();
    fixture.detectChanges();

    expect(textareas()[1].value).toBe(hostile);
    expect(root.querySelector('img, script')).toBeNull();
    expect((window as any).__voiceXss).toBeUndefined();
  });

  it('gives both fields dynamic subscript sizing', () => {
    const fields = Array.from(root.querySelectorAll('mat-form-field'));

    expect(fields.length).toBe(2);
    for (const field of fields) {
      // Material puts the class on the field's inner subscript wrapper, not on the host
      expect(field.querySelector('.mat-mdc-form-field-subscript-dynamic-size')).not.toBeNull();
    }
  });

});
