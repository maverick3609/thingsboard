// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GATEWAY_WILDCARD_DATE, GATEWAY_WILDCARD_DATE_RANGE, gatewayCalendarRuleDayKey,
  gatewayCalendarRuleLabel, gatewayDayText, gatewayDayTimes, gatewayScheduleDayValid,
  gatewayScheduleHasOffsets,
  gatewayWeek } from '@shared/models/inferrix-gateway-schedule.models';
import { gatewaySettingRows, gatewaySettingValue,
  gatewaySettingWithheld } from '@shared/models/inferrix-gateway-system.models';

/**
 * Schedules, calendar rule sets and the system reads.
 *
 * Two of these are guards against things a live gateway was actually observed doing, and they are
 * the reason this file exists rather than a round of manual testing: a week of the wrong length is
 * accepted and then breaks on enable, and a schedule saved without an `exceptions` array is
 * refused every time with a validation error naming a field the form never showed.
 */
describe('InferrixGatewayService schedules', () => {

  const DEVICE = 'device-1';
  const proxy = (path: string) => `/api/inferrix/gateways/${DEVICE}/proxy${path}`;

  let service: InferrixGatewayService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [InferrixGatewayService]
    });
    service = TestBed.inject(InferrixGatewayService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sends seven days however few the caller gave', () => {
    // The gateway takes a three-day week with HTTP 201 and then answers
    // `IndexOutOfBoundsException: Index 3 out of bounds for length 3` when the schedule is
    // enabled, because WeeklyScheduleRT reads index 0 through 6 unconditionally. Observed live.
    service.saveSchedule(DEVICE, {name: 'Plant hours',
      defaultSchedule: [['08:00'], ['08:00'], ['08:00']]} as any).subscribe();

    const request = httpMock.expectOne(proxy('/v2/schedules'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body.defaultSchedule.length).toBe(7);
    expect(request.request.body.defaultSchedule[6]).toEqual([]);
    request.flush({xid: 'SCH_1'});
  });

  it('always sends an exceptions array', () => {
    // Its absence is HTTP 422 {"property":"exceptions","message":"Required value"} on every save,
    // which an operator cannot act on because no form field corresponds to it.
    service.saveSchedule(DEVICE, {name: 'Plant hours'}).subscribe();

    const request = httpMock.expectOne(proxy('/v2/schedules'));
    expect(request.request.body.exceptions).toEqual([]);
    request.flush({xid: 'SCH_1'});
  });

  it('updates an existing schedule by xid and creates a new one without', () => {
    service.saveSchedule(DEVICE, {name: 'New'}).subscribe();
    const create = httpMock.expectOne(proxy('/v2/schedules'));
    expect(create.request.method).toBe('POST');
    create.flush({xid: 'SCH_1'});

    service.saveSchedule(DEVICE, {xid: 'SCH_1', name: 'Renamed'}).subscribe();
    const update = httpMock.expectOne(proxy('/v2/schedules/SCH_1'));
    expect(update.request.method).toBe('PUT');
    update.flush({xid: 'SCH_1'});
  });

  it('enables a schedule with PUT, not the PATCH every other domain uses', () => {
    service.setScheduleEnabled(DEVICE, 'SCH_1', true).subscribe();

    const request = httpMock.expectOne(r => r.url === proxy('/v2/schedules/enable-disable/SCH_1'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.params.get('enabled')).toBe('true');
    // Unlike data sources and points, a schedule's enable route takes no restart flag.
    expect(request.request.params.get('restart')).toBeNull();
    request.flush({});
  });

  it('saves a rule set by xid or creates one', () => {
    service.saveRuleSet(DEVICE, {name: 'Holidays', rules: []}).subscribe();
    const create = httpMock.expectOne(proxy('/v2/schedule-rule-sets'));
    expect(create.request.method).toBe('POST');
    create.flush({xid: 'CRS_1'});

    service.saveRuleSet(DEVICE, {xid: 'CRS_1', name: 'Holidays'}).subscribe();
    const update = httpMock.expectOne(proxy('/v2/schedule-rule-sets/CRS_1'));
    expect(update.request.method).toBe('PUT');
    update.flush({xid: 'CRS_1'});
  });

  it('reads the bare-array endpoints without expecting a page', () => {
    // These three answer a JSON array, not {items, total}. Anything that unwrapped a page here
    // would render an empty card against a gateway that answered correctly.
    let interfaces: any;
    service.getNetworkInterfaces(DEVICE).subscribe(value => interfaces = value);
    httpMock.expectOne(proxy('/v2/server/network-interfaces'))
      .flush([{interfaceName: 'en0', hostAddress: '10.0.0.1'}]);
    expect(interfaces.length).toBe(1);

    let languages: any;
    service.getLanguages(DEVICE).subscribe(value => languages = value);
    httpMock.expectOne(proxy('/v2/server/languages')).flush([{key: 'en', value: 'English'}]);
    expect(languages[0].key).toBe('en');

    let monitor: any;
    service.getMonitorValues(DEVICE).subscribe(value => monitor = value);
    httpMock.expectOne(proxy('/v2/stack-monitor')).flush([{name: 'Active Db Connections', value: 0}]);
    expect(monitor[0].value).toBe(0);
  });
});

describe('gateway schedule model', () => {

  it('pads, truncates and cleans a week to exactly seven days', () => {
    expect(gatewayWeek(null).length).toBe(7);
    expect(gatewayWeek([]).length).toBe(7);
    expect(gatewayWeek([['08:00'], ['08:00'], ['08:00'], ['08:00'], ['08:00'], ['08:00'],
      ['08:00'], ['09:00'], ['10:00']]).length).toBe(7);
    // A gateway that sent something other than an array of strings for a day must not put it back.
    expect(gatewayWeek([['08:00', 7 as any, null]])[0]).toEqual(['08:00']);
  });

  it('accepts the three time patterns the gateway parses and refuses the rest', () => {
    expect(gatewayScheduleDayValid([])).toBe(true);
    expect(gatewayScheduleDayValid(['08:00', '17:00'])).toBe(true);
    expect(gatewayScheduleDayValid(['08:00:30', '17:00:00.500'])).toBe(true);
    expect(gatewayScheduleDayValid(['8:00'])).toBe(false);
    expect(gatewayScheduleDayValid(['24:00'])).toBe(false);
    expect(gatewayScheduleDayValid(['08:60'])).toBe(false);
    expect(gatewayScheduleDayValid(['08:00 <script>'])).toBe(false);
  });

  it('refuses times that do not increase', () => {
    // The gateway's own rule, and its error is the untranslated key
    // `advancedScheduler.validate.offsetsOutOfOrder`.
    expect(gatewayScheduleDayValid(['17:00', '08:00'])).toBe(false);
    expect(gatewayScheduleDayValid(['08:00', '08:00'])).toBe(false);
    expect(gatewayScheduleDayValid(['08:00', '08:00:01'])).toBe(true);
  });

  it('round-trips a day between text and times', () => {
    expect(gatewayDayTimes(' 08:00 , 17:00 ')).toEqual(['08:00', '17:00']);
    expect(gatewayDayTimes('')).toEqual([]);
    expect(gatewayDayTimes(null)).toEqual([]);
    expect(gatewayDayText(['08:00', '17:00'])).toBe('08:00, 17:00');
    expect(gatewayDayText(null)).toBe('');
  });

  it('writes a wildcard date with a star for every part left open', () => {
    expect(gatewayCalendarRuleLabel({type: GATEWAY_WILDCARD_DATE, month: 12, day: 25}))
      .toBe('****-12-25');
    expect(gatewayCalendarRuleLabel({type: GATEWAY_WILDCARD_DATE, year: 2026, month: 1, day: 1}))
      .toBe('2026-01-01');
    expect(gatewayCalendarRuleLabel({
      type: GATEWAY_WILDCARD_DATE_RANGE,
      startDate: {type: GATEWAY_WILDCARD_DATE, month: 1, day: 1},
      endDate: {type: GATEWAY_WILDCARD_DATE, month: 1, day: 2}
    })).toBe('****-01-01 … ****-01-02');
    expect(gatewayCalendarRuleLabel(null)).toBe('');
  });

  it('maps a rule day-of-week to a key a template can translate', () => {
    // 1 is Sunday, as ScheduleUtils.getDayOfWeekIndex numbers them.
    expect(gatewayCalendarRuleDayKey({type: GATEWAY_WILDCARD_DATE, dayOfWeek: 1})).toBe('sunday');
    expect(gatewayCalendarRuleDayKey({type: GATEWAY_WILDCARD_DATE, dayOfWeek: 7})).toBe('saturday');
    expect(gatewayCalendarRuleDayKey({type: GATEWAY_WILDCARD_DATE})).toBeNull();
    expect(gatewayCalendarRuleDayKey({type: GATEWAY_WILDCARD_DATE, dayOfWeek: 99})).toBeNull();
  });
});

describe('a schedule the gateway will accept', () => {

  // The gateway answers 422 "A schedule needs at least one time offset, in the weekly schedule or
  // in an exception." Verified against stack 5.1.0 -- a schedule with an empty week and no
  // exceptions is refused, the same payload with one day filled is created.
  it('refuses a schedule with no change time anywhere', () => {
    expect(gatewayScheduleHasOffsets([[], [], [], [], [], [], []], [])).toBe(false);
    expect(gatewayScheduleHasOffsets([], [])).toBe(false);
    expect(gatewayScheduleHasOffsets(null, null)).toBe(false);
  });

  it('accepts a time in the week or in an exception', () => {
    expect(gatewayScheduleHasOffsets([[], ['08:00', '17:00'], [], [], [], [], []], [])).toBe(true);
    expect(gatewayScheduleHasOffsets([[], [], [], [], [], [], []], [['09:00']])).toBe(true);
  });
});

describe('gateway system settings', () => {

  it('withholds the values that are credentials', () => {
    // The gateway filters `emailSmtpPassword` and `httpClientProxyPassword` itself. It does not
    // filter these two, and a diagnostics table is not where a licence blob or a third-party API
    // token belongs.
    expect(gatewaySettingWithheld('license')).toBe(true);
    expect(gatewaySettingWithheld('poeLightingToken')).toBe(true);
    expect(gatewaySettingWithheld('emailSmtpPassword')).toBe(true);
    // Not credentials, and all real keys of a live gateway's settings object.
    expect(gatewaySettingWithheld('licenseEntered')).toBe(false);
    expect(gatewaySettingWithheld('licenseeName')).toBe(false);
    expect(gatewaySettingWithheld('meshCommPortId')).toBe(false);
    expect(gatewaySettingWithheld('emailSmtpHost')).toBe(false);
  });

  it('drops the withheld keys from the rendered rows', () => {
    const rows = gatewaySettingRows({
      license: 'secret-blob', poeLightingToken: 'secret', emailSmtpHost: 'mail.example.net'
    });
    expect(rows.map(row => row.key)).toEqual(['emailSmtpHost']);
  });

  it('renders a value of any type, and an empty string as a dash', () => {
    // Empty string is a real and common value here; a blank cell would read as a missing row.
    expect(gatewaySettingValue('')).toBe('—');
    expect(gatewaySettingValue(null)).toBe('—');
    expect(gatewaySettingValue(false)).toBe('false');
    expect(gatewaySettingValue(0)).toBe('0');
    expect(gatewaySettingValue({a: 1})).toBe('{"a":1}');
  });
});
