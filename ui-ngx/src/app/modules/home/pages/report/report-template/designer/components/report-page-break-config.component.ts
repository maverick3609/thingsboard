// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
