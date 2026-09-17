// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
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

// An emptied text field must be persisted as null, not as "". WhiteLabelingParams#merge
// (server side) fills only null fields from the parent scope, so a stored "" reads as a
// deliberate override and blocks inheritance — clearing a field would silently blank it
// for everyone below instead of falling back to the system/tenant value above.
export const blankToNull = (value: string): string => value?.trim() ? value : null;

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
