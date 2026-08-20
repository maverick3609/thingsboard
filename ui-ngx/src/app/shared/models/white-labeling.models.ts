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

import { DomainId } from '@shared/models/id/domain-id';

export interface Favicon {
  url: string;
}

export interface Palette {
  type: string;
  extendsPalette: string;
  colors: { [key: string]: string };
}

export interface PaletteSettings {
  primaryPalette: Palette;
  accentPalette: Palette;
}

export interface WhiteLabelingParams {
  logoImageUrl: string;
  logoImageHeight: number;
  appTitle: string;
  favicon: Favicon;
  paletteSettings: PaletteSettings;
  helpLinkBaseUrl: string;
  uiHelpBaseUrl: string;
  enableHelpLinks: boolean;
  whiteLabelingEnabled: boolean;
  showNameVersion: boolean;
  platformName: string;
  platformVersion: string;
  customCss: string;
  hideConnectivityDialog: boolean;
}

export interface MailTemplate {
  subject: string;
  body: string;
}

export type MailTemplates = { [name: string]: MailTemplate };

// keys are the template names served by GET /api/whiteLabel/mailTemplates
export const mailTemplateTranslations = new Map<string, string>([
  ['test', 'white-labeling.mail-template.test'],
  ['activation', 'white-labeling.mail-template.activation'],
  ['account.activated', 'white-labeling.mail-template.account-activated'],
  ['account.lockout', 'white-labeling.mail-template.account-lockout'],
  ['reset.password', 'white-labeling.mail-template.reset-password'],
  ['password.was.reset', 'white-labeling.mail-template.password-was-reset'],
  ['2fa.verification.code', 'white-labeling.mail-template.two-fa-verification'],
  ['state.enabled', 'white-labeling.mail-template.api-usage-state-enabled'],
  ['state.warning', 'white-labeling.mail-template.api-usage-state-warning'],
  ['state.disabled', 'white-labeling.mail-template.api-usage-state-disabled']
]);

export enum PlatformVersionPosition {
  UNDER_LOGO = 'underLogo',
  BOTTOM = 'bottom'
}

export const platformVersionPositionTranslations = new Map<PlatformVersionPosition, string>([
  [PlatformVersionPosition.UNDER_LOGO, 'white-labeling.position.under-logo'],
  [PlatformVersionPosition.BOTTOM, 'white-labeling.position.bottom']
]);

export interface LoginWhiteLabelingParams extends WhiteLabelingParams {
  pageBackgroundColor: string;
  darkForeground: boolean;
  showNameBottom: boolean;
  loginCardColor: string;
  domainId: DomainId;
  baseUrl: string;
}
