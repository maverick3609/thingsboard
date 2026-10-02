// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * Voice call templates. Only the English text is written by hand. Hindi is translated from it
 * once, in the template editor, and stored; nothing is translated during a call.
 */

export const VOICE_MAX_LENGTH = 600;

export interface VoiceLanguage {
  code: 'hi';
  name: string;
}

export const VOICE_TRANSLATED_LANGUAGES: VoiceLanguage[] = [
  {code: 'hi', name: 'notification.voice-hindi'}
];

export interface VoiceTranslation {
  text: string;
  placeholdersOk: boolean;
}

export interface VoiceTranslateResponse {
  translations: {[language: string]: VoiceTranslation};
}

const PLACEHOLDER = /\$\{[^}]*}/g;

/**
 * Whether a translation kept every `${...}` of the English, each as often, in any order. TB fills
 * them in after translation, so a lost one means the caller never hears the device or the value.
 */
export function placeholdersMatch(source: string, translated: string): boolean {
  const found = (text: string) => (text?.match(PLACEHOLDER) ?? []).sort().join('\u0000');
  return found(source) === found(translated);
}
