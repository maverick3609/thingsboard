///
/// Copyright © 2016-2026 The Inferrix Authors
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

// The app builds with @angular-builders/custom-esbuild, whose tb-define-variables plugin
// (esbuild/tb-esbuild-plugins.ts) defines TB_VERSION / SUPPORTED_LANGS at compile time.
// `ng test` runs the stock webpack karma builder with no such define, so environment.ts
// throws ReferenceError the moment any spec imports it. Loading this file as a test polyfill
// (angular.json test target) puts the identifiers on the global scope before app modules
// evaluate. Values are placeholders: no spec asserts on them.
(globalThis as any).TB_VERSION = '0.0.0-test';
(globalThis as any).SUPPORTED_LANGS = ['en_US'];

// Same gap for jQuery: the esbuild resolveJQueryPlugin injects it app-wide, and
// AppComponent's constructor calls initCustomJQueryEvents, which touches the global $.
import $ from 'jquery';
(globalThis as any).$ = $;
(globalThis as any).jQuery = $;
