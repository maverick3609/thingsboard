// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0

/**
 * One rule in a calendar rule set.
 *
 * Jackson discriminates on `type`, and **a nested rule carries its own**: a range rule whose
 * `startDate` omits it is refused with HTTP 400 `Failed to read request`, which says nothing about
 * which field was wrong. Verified against a live gateway.
 */
export interface GatewayCalendarRule {
  type: string;
  /** `WildcardDateRule1`. Any subset may be null — a null field is the wildcard. */
  year?: number;
  month?: number;
  day?: number;
  /** 1 = Sunday … 7 = Saturday, as `ScheduleUtils.getDayOfWeekIndex` numbers them. */
  dayOfWeek?: number;
  /** `WildcardDateRangeRule1`. Both ends are themselves `WildcardDateRule1`s. */
  startDate?: GatewayCalendarRule;
  endDate?: GatewayCalendarRule;
}

export const GATEWAY_WILDCARD_DATE = 'WildcardDateRule1';
export const GATEWAY_WILDCARD_DATE_RANGE = 'WildcardDateRangeRule1';

export interface GatewayCalendarRuleSet {
  id?: number;
  xid?: string;
  name?: string;
  editPermission?: string;
  rules?: GatewayCalendarRule[];
}

/**
 * A day's schedule that replaces the weekly one on the dates a rule set selects.
 *
 * The gateway accepts a `ruleSet` given as `{xid}` alone and resolves it, which is what lets the
 * dialog reference an existing rule set without carrying its rules through an edit.
 */
export interface GatewayScheduleException {
  schedule?: string[];
  ruleSet?: GatewayCalendarRuleSet;
}

export interface GatewaySchedule {
  id?: number;
  xid?: string;
  name?: string;
  enabled?: boolean;
  alarmLevel?: string;
  errorAlarmLevel?: string;
  /** Exactly seven days, Sunday first. See {@link gatewayWeek}. */
  defaultSchedule?: string[][];
  exceptions?: GatewayScheduleException[];
  readPermission?: string;
  editPermission?: string;
  /** Runtime state the gateway computes. Read-only — sent back untouched. */
  rtData?: {timestamp?: number; active?: boolean; finished?: boolean; rule?: GatewayCalendarRule};
}

/**
 * The days of a weekly schedule, in the order the gateway indexes them.
 *
 * `WeeklyScheduleRT` reads `dailySchedules.get(0)` through `.get(6)` and calls them Sunday through
 * Saturday, so the order is not a display choice — it is the wire format.
 */
export const GATEWAY_SCHEDULE_DAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday',
  'friday', 'saturday'];

/**
 * A weekly schedule of exactly seven days.
 *
 * **This is a correctness guard, not a convenience.** The gateway accepts a `defaultSchedule` of
 * any length on create and only indexes it when the schedule is *enabled*, at which point
 * `WeeklyScheduleRT` reads index 6 of whatever it was given. Verified live: a three-day schedule
 * is created with HTTP 201 and then answers `IndexOutOfBoundsException: Index 3 out of bounds for
 * length 3` on enable — leaving a saved schedule that can never be turned on and an operator with
 * a stack trace instead of a message. So every week this UI sends is padded to seven here.
 */
export const gatewayWeek = (value: string[][]): string[][] => {
  const week = Array.isArray(value) ? value : [];
  return GATEWAY_SCHEDULE_DAYS.map((_, day) =>
    Array.isArray(week[day]) ? week[day].filter(time => typeof time === 'string') : []);
};

/** `HH:mm`, `HH:mm:ss` and `HH:mm:ss.SSS` — the three patterns `ScheduleUtils` parses. */
export const GATEWAY_SCHEDULE_TIME = /^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9](\.[0-9]{3})?)?$/;

/**
 * Whether one day's change times are something the gateway will accept.
 *
 * The gateway validates this too, and is the authority — but it answers with the untranslated key
 * `advancedScheduler.validate.offsetsOutOfOrder`, so catching it here is the difference between a
 * readable form error and a raw i18n key in a dialog.
 */
export const gatewayScheduleDayValid = (day: string[]): boolean => {
  let previous = -1;
  for (const time of day ?? []) {
    if (!GATEWAY_SCHEDULE_TIME.test(time)) {
      return false;
    }
    const [h, m, rest] = time.split(':');
    const seconds = (+h * 3600) + (+m * 60) + (rest ? parseFloat(rest) : 0);
    if (seconds <= previous) {
      return false;
    }
    previous = seconds;
  }
  return true;
};

/**
 * A calendar rule as one line of text.
 *
 * Every field of a wildcard rule is optional and a missing one means "any", so `2026-*-25` is the
 * 25th of every month of 2026. Written with `*` rather than words because it lands in a table cell
 * in a translated application and a date pattern needs no dictionary; the day-of-week part is a
 * separate field on the row precisely so that it can be translated.
 */
export const gatewayCalendarRuleLabel = (rule: GatewayCalendarRule): string => {
  if (!rule) {
    return '';
  }
  if (rule.type === GATEWAY_WILDCARD_DATE_RANGE) {
    return `${gatewayCalendarRuleLabel(rule.startDate)} \u2026 ${gatewayCalendarRuleLabel(rule.endDate)}`;
  }
  const pad = (value: number, width: number) =>
    value == null ? '*'.repeat(width) : String(value).padStart(width, '0');
  return `${pad(rule.year, 4)}-${pad(rule.month, 2)}-${pad(rule.day, 2)}`;
};

/** The day-of-week key of a rule, for a template that translates it. Null when the rule is any day. */
export const gatewayCalendarRuleDayKey = (rule: GatewayCalendarRule): string | null =>
  rule?.dayOfWeek != null ? GATEWAY_SCHEDULE_DAYS[rule.dayOfWeek - 1] ?? null : null;

/**
 * A day's change times as one line of text, and back.
 *
 * A day is a short ordered list of times, which a comma-separated field expresses exactly — so
 * there is no chip control here and no per-time row. `08:00, 17:00` is also what an operator would
 * write down, and it is one input per day rather than a widget per time.
 */
export const gatewayDayTimes = (text: string): string[] =>
  (text ?? '').split(',').map(time => time.trim()).filter(time => time.length > 0);

export const gatewayDayText = (times: string[]): string => (times ?? []).join(', ');
