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

import { Component, Inject, SkipSelf } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormBuilder, FormControl, FormGroup, FormGroupDirective, NgForm, Validators } from '@angular/forms';
import { ErrorStateMatcher } from '@angular/material/core';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { EntityType } from '@shared/models/entity-type.models';
import {
  SchedulerEvent,
  schedulerEventConfigTypes,
  SchedulerEventConfigType
} from '@shared/models/scheduler-event.models';
import { SchedulerEventService } from '@core/http/scheduler-event.service';

export interface SchedulerEventDialogData {
  schedulerEvent: SchedulerEvent | null;
  isAdd: boolean;
  isCustomerUser: boolean;
}

@Component({
  selector: 'tb-scheduler-event-dialog',
  templateUrl: './scheduler-event-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: SchedulerEventDialogComponent}],
  standalone: false
})
export class SchedulerEventDialogComponent extends DialogComponent<SchedulerEventDialogComponent, SchedulerEvent>
  implements ErrorStateMatcher {

  schedulerEventFormGroup: FormGroup;
  isAdd: boolean;
  entityType = EntityType;
  configTypes: {[type: string]: SchedulerEventConfigType};
  configTypeKeys: string[];
  submitted = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: SchedulerEventDialogData,
              public dialogRef: MatDialogRef<SchedulerEventDialogComponent, SchedulerEvent>,
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              private fb: FormBuilder,
              private schedulerEventService: SchedulerEventService) {
    super(store, router, dialogRef);
    this.isAdd = data.isAdd;
    this.configTypes = {...schedulerEventConfigTypes};
    if (data.isCustomerUser) {
      delete this.configTypes.generateReport;   // PE parity: hidden from customer users
    }
    this.configTypeKeys = Object.keys(this.configTypes);

    const event = data.schedulerEvent;
    this.schedulerEventFormGroup = this.fb.group({
      name: [event ? event.name : '', [Validators.required, Validators.maxLength(255)]],
      type: [event ? event.type : null, [Validators.required]],
      enabled: [event ? event.enabled : true],
      customerId: [event ? event.customerId : null],
      schedule: [event ? event.schedule : {
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        startTime: Date.now() + 3600 * 1000
      }, [Validators.required]],
      // configuration.originatorId is a TRANSIENT bridge: seeded here from the top-level
      // event.originatorId bean field so the per-type config form's originator picker
      // (tb-scheduler-event-config) shows the current value. save() lifts it back out
      // to the top-level bean field and strips it from the persisted configuration.
      configuration: [event
        ? {...event.configuration, ...(event.originatorId ? {originatorId: event.originatorId} : {})}
        : {msgType: null, msgBody: {}, metadata: null}, [Validators.required]]
    });

    this.schedulerEventFormGroup.get('type').valueChanges.subscribe(() => {
      this.schedulerEventFormGroup.get('configuration').setValue(
        {msgType: null, msgBody: {}, metadata: null});
    });
  }

  isErrorState(control: FormControl | null, form: FormGroupDirective | NgForm | null): boolean {
    const originalErrorState = this.errorStateMatcher.isErrorState(control, form);
    const customErrorState = !!(control && control.invalid && this.submitted);
    return originalErrorState || customErrorState;
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  save(): void {
    this.submitted = true;
    if (this.schedulerEventFormGroup.invalid) {
      return;
    }
    const formValue = this.schedulerEventFormGroup.getRawValue();
    const configuration = {...formValue.configuration};
    const originatorId = configuration.originatorId || null;
    delete configuration.originatorId;
    const event: SchedulerEvent = {
      ...(this.data.schedulerEvent || {} as SchedulerEvent),
      ...formValue,
      originatorId,
      configuration
    };
    this.schedulerEventService.saveSchedulerEvent(event).subscribe({
      next: saved => this.dialogRef.close(saved),
      error: err => console.error('[Scheduler] save failed', err)
    });
  }
}
