// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { UntypedFormArray, UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { GATEWAY_SCHEDULE_DAYS, GATEWAY_WILDCARD_DATE, GATEWAY_WILDCARD_DATE_RANGE,
  GatewayCalendarRule, GatewayCalendarRuleSet } from '@shared/models/inferrix-gateway-schedule.models';

export interface GatewayRuleSetDialogData {
  title: string;
  ruleSet: GatewayCalendarRuleSet;
  readonly: boolean;
}

/** A form value for one wildcard date. Every part is optional; blank means "any". */
interface DateValue {
  year: number;
  month: number;
  day: number;
  dayOfWeek: number;
}

/**
 * A calendar rule set — the dates a schedule makes an exception for.
 *
 * Two rule kinds, both built from the same wildcard date: a single date, where any blank part means
 * "any" (so 25 December of every year is `year` blank, `month` 12, `day` 25), and a range between
 * two of them.
 *
 * **The nested dates of a range carry their own `type`.** Jackson discriminates every `CalendarRule`
 * on it, including the two inside a range rule, and a range whose `startDate` omits it is refused
 * with HTTP 400 `Failed to read request` — an error that names neither the field nor the rule.
 * Verified against a live gateway, which is why `toRule` writes it rather than trusting the shape.
 */
@Component({
  selector: 'tb-gateway-rule-set-dialog',
  templateUrl: './gateway-rule-set-dialog.component.html',
  styleUrls: [],
  standalone: false
})
export class GatewayRuleSetDialogComponent
  extends DialogComponent<GatewayRuleSetDialogComponent, GatewayCalendarRuleSet> {

  readonly isAdd: boolean;
  readonly days = GATEWAY_SCHEDULE_DAYS;
  readonly dateType = GATEWAY_WILDCARD_DATE;
  readonly rangeType = GATEWAY_WILDCARD_DATE_RANGE;

  form: UntypedFormGroup;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: GatewayRuleSetDialogData,
              public dialogRef: MatDialogRef<GatewayRuleSetDialogComponent, GatewayCalendarRuleSet>,
              private fb: UntypedFormBuilder) {
    super(store, router, dialogRef);
    const ruleSet = data.ruleSet ?? {};
    this.isAdd = !ruleSet.xid;
    this.form = this.fb.group({
      name: [ruleSet.name ?? '', [Validators.required, Validators.maxLength(255)]],
      xid: [{value: ruleSet.xid ?? '', disabled: !this.isAdd},
        [Validators.maxLength(64), Validators.pattern(/^[A-Za-z0-9_.-]*$/)]],
      editPermission: [ruleSet.editPermission ?? ''],
      rules: this.fb.array((ruleSet.rules ?? []).map(rule => this.ruleGroup(rule)))
    });
    if (data.readonly) {
      this.form.disable();
    }
  }

  get rules(): UntypedFormArray {
    return this.form.get('rules') as UntypedFormArray;
  }

  addRule(type: string): void {
    this.rules.push(this.ruleGroup({type}));
  }

  removeRule(index: number): void {
    this.rules.removeAt(index);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const saved: GatewayCalendarRuleSet = {
      ...this.data.ruleSet,
      name: value.name,
      editPermission: value.editPermission || null,
      rules: (value.rules as any[]).map(rule => rule.type === GATEWAY_WILDCARD_DATE_RANGE
        ? {type: GATEWAY_WILDCARD_DATE_RANGE,
           startDate: this.toRule(rule.startDate), endDate: this.toRule(rule.endDate)}
        : this.toRule(rule.date))
    };
    if (value.xid) {
      saved.xid = value.xid;
    }
    this.dialogRef.close(saved);
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  private ruleGroup(rule: GatewayCalendarRule): UntypedFormGroup {
    return rule?.type === GATEWAY_WILDCARD_DATE_RANGE
      ? this.fb.group({
          type: [GATEWAY_WILDCARD_DATE_RANGE],
          startDate: this.dateGroup(rule.startDate),
          endDate: this.dateGroup(rule.endDate)
        })
      : this.fb.group({type: [GATEWAY_WILDCARD_DATE], date: this.dateGroup(rule)});
  }

  private dateGroup(date: GatewayCalendarRule): UntypedFormGroup {
    return this.fb.group({
      year: [date?.year ?? null, [Validators.min(1970), Validators.max(9999)]],
      month: [date?.month ?? null, [Validators.min(1), Validators.max(12)]],
      day: [date?.day ?? null, [Validators.min(1), Validators.max(31)]],
      dayOfWeek: [date?.dayOfWeek ?? null]
    });
  }

  /**
   * A form date as a rule the gateway will deserialise.
   *
   * Blank is "any", and a blank number input yields `null` or the empty string depending on how it
   * was cleared — both have to become a real null, because `0` and `""` are not wildcards to the
   * gateway, they are values it will try to validate as a month.
   */
  private toRule(date: DateValue): GatewayCalendarRule {
    const part = (value: any) =>
      value === null || value === undefined || value === '' ? null : Number(value);
    return {type: GATEWAY_WILDCARD_DATE, year: part(date?.year), month: part(date?.month),
      day: part(date?.day), dayOfWeek: part(date?.dayOfWeek)};
  }
}
