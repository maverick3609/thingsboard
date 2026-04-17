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

export interface LoginWhiteLabelingParams extends WhiteLabelingParams {
  pageBackgroundColor: string;
  darkForeground: boolean;
  showNameBottom: boolean;
  domainId: string;
  baseUrl: string;
}
