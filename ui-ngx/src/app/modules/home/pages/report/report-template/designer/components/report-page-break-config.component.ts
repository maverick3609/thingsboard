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

import { Component } from '@angular/core';

// Right-panel content for a selected PAGE_BREAK component (report-template-editor.component.html,
// selected?.type === 'PAGE_BREAK' branch). components/PageBreakComponent.java has no fields, so
// unlike every other per-type config panel (T8's own DIVIDER, T9-T11), this is not a
// ControlValueAccessor - it's a static informational card, bound with no model at all.
@Component({
    selector: 'tb-report-page-break-config',
    templateUrl: './report-page-break-config.component.html',
    styleUrls: [],
    standalone: false
})
export class ReportPageBreakConfigComponent {
}
