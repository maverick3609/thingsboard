// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { AbstractControl, UntypedFormArray, UntypedFormBuilder, UntypedFormGroup,
  ValidationErrors, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { GATEWAY_ALARM_LEVELS } from '@shared/models/inferrix-gateway-event.models';
import { GATEWAY_SCHEDULE_DAYS, GatewayCalendarRuleSet, GatewaySchedule,
  gatewayDayText, gatewayDayTimes, gatewayScheduleDayValid,
  gatewayWeek } from '@shared/models/inferrix-gateway-schedule.models';

export interface GatewayScheduleDialogData {
  title: string;
  schedule: GatewaySchedule;
  /** The rule sets an exception may point at. Empty means exceptions cannot be added. */
  ruleSets: GatewayCalendarRuleSet[];
  readonly: boolean;
}

/**
 * Whether a day's comma-separated change times are ones the gateway will take.
 *
 * `gatewayScheduleDayValid` is the authority on the rule (format, then strictly increasing); this
 * only adapts it to a control holding one line of text.
 */
const dayTimes = (control: AbstractControl): ValidationErrors | null =>
  gatewayScheduleDayValid(gatewayDayTimes(control.value)) ? null : {dayTimes: true};

/**
 * One schedule, hand-written rather than schema-driven.
 *
 * Schedules are not a schema *family* — `ModelSchemaFamily` covers data sources, point locators,
 * publishers, event detectors and event handlers — so `/v2/model-schemas` describes nothing here and
 * there is no form to map. It would also not help if it did: swagger reports `WeeklySchedule` as
 * `{dailySchedules, offsetCount}` because that is the Java class, while `@JsonValue` puts a bare
 * array of arrays of strings on the wire. A schema-built form would offer inputs for fields that do
 * not exist and miss the one that does.
 *
 * **A week is always seven days.** The service pads it, but this form shows seven rows for the same
 * reason: the gateway accepts a shorter week and then fails on enable with an index error, so a
 * schedule saved from a form that let an operator leave days out would be one they could never turn
 * on.
 *
 * Change times *toggle* the schedule rather than switching it on or off, and the state carries over
 * from the previous day — so `08:00, 17:00` is an active window only if the day began inactive. That
 * is the gateway's model, not a simplification of it, and the form does not pretend otherwise.
 */
@Component({
  selector: 'tb-gateway-schedule-dialog',
  templateUrl: './gateway-schedule-dialog.component.html',
  styleUrls: [],
  standalone: false
})
export class GatewayScheduleDialogComponent
  extends DialogComponent<GatewayScheduleDialogComponent, GatewaySchedule> {

  readonly isAdd: boolean;
  readonly days = GATEWAY_SCHEDULE_DAYS;
  readonly alarmLevels = GATEWAY_ALARM_LEVELS;

  form: UntypedFormGroup;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: GatewayScheduleDialogData,
              public dialogRef: MatDialogRef<GatewayScheduleDialogComponent, GatewaySchedule>,
              private fb: UntypedFormBuilder) {
    super(store, router, dialogRef);
    const schedule = data.schedule ?? {};
    this.isAdd = !schedule.xid;
    this.form = this.fb.group({
      name: [schedule.name ?? '', [Validators.required, Validators.maxLength(255)]],
      // Blank on an add so the gateway generates one; frozen afterwards, because the xid is what
      // every set-point handler on the device refers to this schedule by.
      xid: [{value: schedule.xid ?? '', disabled: !this.isAdd},
        [Validators.maxLength(64), Validators.pattern(/^[A-Za-z0-9_.-]*$/)]],
      enabled: [schedule.enabled ?? false],
      alarmLevel: [schedule.alarmLevel ?? 'NONE'],
      errorAlarmLevel: [schedule.errorAlarmLevel ?? 'URGENT'],
      readPermission: [schedule.readPermission ?? ''],
      editPermission: [schedule.editPermission ?? ''],
      week: this.fb.array(gatewayWeek(schedule.defaultSchedule)
        .map(day => this.fb.control(gatewayDayText(day), [dayTimes]))),
      exceptions: this.fb.array((schedule.exceptions ?? []).map(exception => this.exceptionGroup(
        exception.ruleSet?.xid ?? '', gatewayDayText(exception.schedule))))
    });
    if (data.readonly) {
      this.form.disable();
    }
  }

  /** Never undefined, because a throwing template is how this feature blanks a whole tab strip. */
  get ruleSets(): GatewayCalendarRuleSet[] {
    return this.data.ruleSets ?? [];
  }

  get week(): UntypedFormArray {
    return this.form.get('week') as UntypedFormArray;
  }

  get exceptions(): UntypedFormArray {
    return this.form.get('exceptions') as UntypedFormArray;
  }

  addException(): void {
    this.exceptions.push(this.exceptionGroup(this.ruleSets[0]?.xid ?? '', ''));
  }

  removeException(index: number): void {
    this.exceptions.removeAt(index);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    // The original first, so the surrogate id and anything a newer gateway added survive a form
    // that does not show them. `rtData` rides along and is ignored: `ScheduleModel.toVO` never
    // reads it, so the runtime state cannot be rewritten from here even by a stale copy.
    const saved: GatewaySchedule = {
      ...this.data.schedule,
      name: value.name,
      enabled: value.enabled,
      alarmLevel: value.alarmLevel,
      errorAlarmLevel: value.errorAlarmLevel,
      readPermission: value.readPermission || null,
      editPermission: value.editPermission || null,
      defaultSchedule: (value.week as string[]).map(day => gatewayDayTimes(day)),
      // A rule set is sent as `{xid}` alone. The gateway resolves it, which is what keeps an edit
      // from carrying — and potentially rewriting — the rule set's own rules.
      exceptions: (value.exceptions as {ruleSetXid: string; times: string}[])
        .filter(exception => !!exception.ruleSetXid)
        .map(exception => ({schedule: gatewayDayTimes(exception.times),
          ruleSet: {xid: exception.ruleSetXid}}))
    };
    if (value.xid) {
      saved.xid = value.xid;
    }
    this.dialogRef.close(saved);
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  private exceptionGroup(ruleSetXid: string, times: string): UntypedFormGroup {
    return this.fb.group({
      ruleSetXid: [ruleSetXid, [Validators.required]],
      times: [times, [dayTimes]]
    });
  }
}
