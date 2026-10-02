// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { InferrixVoiceService } from '@core/http/inferrix-voice.service';
import { NotificationDeliveryMethod, NotificationType } from '@shared/models/notification.models';
import {
  NotificationTemplateConfigurationComponent
} from '@home/pages/notification/template/configuration/notification-template-configuration.component';
import {
  VoiceTemplatePanelComponent
} from '@home/pages/notification/template/configuration/voice-template-panel.component';

/**
 * Mounts TB's real template editor with the real VOICE panel, driven the way the template dialog drives it.
 * A lost V5(b), V6 or V7 patch (INFERRIX-PATCHES.md) fails here, and nothing in the build would notice.
 */
describe('VOICE in the template editor', () => {

  const english = 'Alarm on ${alarmOriginatorName}.';
  const hindi = 'अलार्म ${alarmOriginatorName}';
  let fixture: ComponentFixture<NotificationTemplateConfigurationComponent>;
  let editor: NotificationTemplateConfigurationComponent;
  let emitted: any[];

  const panel = () => fixture.nativeElement.querySelector('tb-voice-template-panel') as HTMLElement;
  const textareas = () => Array.from(panel().querySelectorAll('textarea')) as HTMLTextAreaElement[];
  const voice = () => editor.templateConfigurationForm.get('VOICE') as FormGroup;
  /** What the dialog does: the toggles' value goes in through predefinedDeliveryMethodsTemplate. */
  const toggles = (...on: NotificationDeliveryMethod[]) => {
    const value: any = {};
    for (const method of Object.values(NotificationDeliveryMethod)) {
      value[method] = {enabled: on.includes(method)};
    }
    editor.predefinedDeliveryMethodsTemplate = value;
    fixture.detectChanges();
  };
  const type = (textarea: HTMLTextAreaElement, text: string) => {
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [NotificationTemplateConfigurationComponent, VoiceTemplatePanelComponent],
      imports: [ReactiveFormsModule, NoopAnimationsModule, MatExpansionModule, MatInputModule, MatButtonModule,
        MatSlideToggleModule, TranslateModule.forRoot()],
      providers: [{provide: InferrixVoiceService, useValue: {translate: () => of({translations: {}})}}],
      // The editor's template is full of TB components this test has no use for. The VOICE panel is declared for real.
      schemas: [NO_ERRORS_SCHEMA]
    });
    fixture = TestBed.createComponent(NotificationTemplateConfigurationComponent);
    editor = fixture.componentInstance;
    editor.notificationType = NotificationType.GENERAL;
    emitted = [];
    editor.registerOnChange((value: any) => emitted.push(value));
    fixture.detectChanges();
  });

  it('V6: builds a VOICE group beside the other methods', () => {
    expect(voice()).toBeTruthy();
    expect(Object.keys(voice().controls).sort())
      .toEqual(['ackRequired', 'body', 'enabled', 'localizedBodies', 'localizedSource', 'method']);
    expect(voice().get('method').value).toBe('VOICE');
    expect(Object.keys(voice().get('localizedBodies').value)).toEqual(['hi']);
  });

  it('V7: shows no VOICE section while VOICE is off', () => {
    toggles(NotificationDeliveryMethod.SMS);
    expect(panel()).toBeNull();
  });

  it('V7: puts the real panel inside the VOICE section once VOICE is on', () => {
    toggles(NotificationDeliveryMethod.VOICE);
    expect(panel()).not.toBeNull();
    expect(textareas().length).toBe(2);
    const title = fixture.nativeElement.querySelector('mat-panel-title').textContent;
    expect(title).toContain('phone-in-talk');
    expect(title).toContain('notification.delivery-method.voice');
  });

  it('V7: fills the panel from a stored template', () => {
    editor.writeValue({VOICE: {
      body: english, localizedBodies: {hi: hindi}, localizedSource: english, ackRequired: true, method: 'VOICE'
    }} as any);
    toggles(NotificationDeliveryMethod.VOICE);
    // A valid stored template opens collapsed, as every method does: the panel is lazy content
    expect(panel()).toBeNull();
    (fixture.nativeElement.querySelector('mat-expansion-panel-header') as HTMLElement).click();
    fixture.detectChanges();
    expect(textareas().map(t => t.value)).toEqual([english, hindi]);
    expect(voice().get('ackRequired').value).toBeTrue();
    expect(editor.validate()).toBeNull();
  });

  it('refuses to save VOICE with no message, and accepts it once a message is typed', () => {
    toggles(NotificationDeliveryMethod.VOICE);
    expect(editor.validate()).not.toBeNull();
    type(textareas()[0], english);
    expect(editor.validate()).toBeNull();
    expect(emitted[emitted.length - 1].VOICE).toEqual({
      body: english, localizedBodies: {hi: ''}, localizedSource: '', ackRequired: false, enabled: true, method: 'VOICE'
    });
  });

  it('refuses to save a translation whose placeholders differ', () => {
    toggles(NotificationDeliveryMethod.VOICE);
    type(textareas()[0], english);
    type(textareas()[1], 'अलार्म');
    expect(editor.validate()).not.toBeNull();
    type(textareas()[1], hindi);
    expect(editor.validate()).toBeNull();
  });

  it('leaves a disabled VOICE out of the saved value and out of validation', () => {
    editor.writeValue({SMS: {body: 'x'}} as any);
    toggles(NotificationDeliveryMethod.SMS);
    expect(voice().disabled).toBeTrue();
    expect(editor.validate()).toBeNull();
    editor.templateConfigurationForm.get('SMS.body').setValue('Alarm');
    expect(Object.keys(emitted[emitted.length - 1])).toEqual(['SMS']);
  });
});
