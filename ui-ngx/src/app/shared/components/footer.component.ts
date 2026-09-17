// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { WhiteLabelingRuntimeService } from '@core/services/white-labeling-runtime.service';

@Component({
    selector: 'tb-footer',
    templateUrl: './footer.component.html',
    styleUrls: ['./footer.component.scss'],
    standalone: false
})
export class FooterComponent {

  year = new Date().getFullYear();

  showNameVersion$ = this.wlRuntime.showNameVersion$;
  platformName$ = this.wlRuntime.platformName$;

  constructor(private wlRuntime: WhiteLabelingRuntimeService) {}

}
