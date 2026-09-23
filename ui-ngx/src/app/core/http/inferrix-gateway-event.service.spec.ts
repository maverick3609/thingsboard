// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { buildRecipient, gatewayAlarmTone, gatewayDetectorTypes, gatewayHandlerRunsCommands,
  gatewayHandlerTypes, recipientValue } from '@shared/models/inferrix-gateway-event.models';
import { GatewaySchemaDocument } from '@shared/models/inferrix-gateway-schema.models';
import fixture from '@shared/models/inferrix-gateway-schema.fixture.json';
import liveFixture from '@shared/models/inferrix-gateway-schema.live.json';

/**
 * Events, detectors, handlers and alert routing.
 *
 * The properties worth pinning are the ones a reader would otherwise have to take on trust: that a
 * detector list is scoped to its point on the **device** rather than filtered in the browser, that
 * the event log never asks for a free-text search it would be refused for, and that acknowledging
 * uses the one identifier in this whole feature that is a number rather than an xid.
 */
describe('InferrixGatewayService events', () => {

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

  it('scopes a detector list to its point on the device', () => {
    service.getDetectorsForPoint(DEVICE, 'DP_1', {pageSize: 20, page: 0}).subscribe();

    const request = httpMock.expectOne(r => r.url === proxy('/v2/event-detector'));
    // sourceId is the point's xid. A detector watching nothing is not a thing the gateway stores,
    // so this filter is the list's identity, not a convenience.
    expect(request.request.params.get('filterField')).toBe('sourceId');
    expect(request.request.params.get('filterValue')).toBe('DP_1');
    expect(request.request.params.get('pageSize')).toBe('20');
    request.flush({items: [], total: 0});
  });

  it('will not let a caller override the detector scope', () => {
    // The scope is applied after the caller's query, so a filter passed in cannot widen the list
    // to every detector on the gateway.
    service.getDetectorsForPoint(DEVICE, 'DP_1',
      {pageSize: 20, page: 0, filterField: 'alarmLevel', filterValue: 'NONE'} as any).subscribe();
    const request = httpMock.expectOne(r => r.url === proxy('/v2/event-detector'));
    expect(request.request.params.get('filterField')).toBe('sourceId');
    expect(request.request.params.get('filterValue')).toBe('DP_1');
    request.flush({items: [], total: 0});
  });

  it('asks the gateway which detector types suit a data type', () => {
    service.getDetectorTypes(DEVICE, 'BINARY').subscribe();
    const request = httpMock.expectOne(proxy('/v2/event-detector-type/BINARY'));
    expect(request.request.method).toBe('GET');
    request.flush({items: [{type: 'BINARY_STATE_DETECTOR', name: 'Binary state'}], total: 1});
  });

  it('never sends a free-text search to the event log', () => {
    // The events table has no `name` column, and the platform turns textSearch into
    // match(name, ...) -- which the gateway answers with an unknown-property failure. Dropping it
    // here is what keeps a shared search box from breaking one tab.
    service.getEvents(DEVICE, {pageSize: 20, page: 0, textSearch: 'boiler',
      sortProperty: 'activeTimestamp', sortOrder: 'DESC'}).subscribe();

    const request = httpMock.expectOne(r => r.url === proxy('/v2/events'));
    expect(request.request.params.has('textSearch')).toBeFalse();
    expect(request.request.params.get('sortProperty')).toBe('activeTimestamp');
    expect(request.request.params.get('sortOrder')).toBe('DESC');
    request.flush({items: [], total: 0});
  });

  it('acknowledges by numeric id, not by xid', () => {
    service.acknowledgeEvent(DEVICE, 4711).subscribe();
    const request = httpMock.expectOne(proxy('/v2/events/acknowledge/4711'));
    expect(request.request.method).toBe('PUT');
    request.flush({});
  });

  it('round-trips an alert list without touching its do-not-disturb week', () => {
    // 672 slots that nothing in this UI edits. A save that dropped them would silently make a list
    // which was quiet at night start paging people at 03:00.
    const schedule = {monday: [{start: 0, end: 96}]};
    service.saveAlertList(DEVICE,
      {xid: 'AL_1', name: 'Night shift', inactiveSchedule: schedule, recipients: []}).subscribe();
    const request = httpMock.expectOne(proxy('/v2/alert-list/AL_1'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.inactiveSchedule).toEqual(schedule);
    request.flush({});
  });

  it('creates a handler against the collection and updates in place', () => {
    service.saveEventHandler(DEVICE, {name: 'Page duty', handlerType: 'EMAIL_HANDLER'}).subscribe();
    const create = httpMock.expectOne(proxy('/v2/event-handler'));
    expect(create.request.method).toBe('POST');
    expect(create.request.body.handlerType).toBe('EMAIL_HANDLER');
    create.flush({xid: 'EH_1', name: 'Page duty', handlerType: 'EMAIL_HANDLER'});

    service.saveEventHandler(DEVICE, {xid: 'EH_1', name: 'Page duty', handlerType: 'EMAIL_HANDLER'})
      .subscribe();
    const update = httpMock.expectOne(proxy('/v2/event-handler/EH_1'));
    expect(update.request.method).toBe('PUT');
    // The discriminator survives the round trip: it is what selects the form AND what tells the
    // gateway which subtype to deserialise into.
    expect(update.request.body.handlerType).toBe('EMAIL_HANDLER');
    update.flush({});
  });
});

describe('gateway event model helpers', () => {

  const doc = fixture as unknown as GatewaySchemaDocument;

  it('takes the handler types from the schema, not from the types endpoint', () => {
    // /v2/event-handler-types maps an EventHandlerDefinition through VoModelMapper, which has a
    // mapping for each handler VO and none for the definition -- so its response shape is not
    // something this code can claim to know. The schema answers the more useful question anyway:
    // which types there is a form for.
    expect(gatewayHandlerTypes(doc).map(t => t.type)).toEqual(['EMAIL_HANDLER']);
    expect(gatewayHandlerTypes(null)).toEqual([]);
  });

  it('will not offer the handler type whose configuration is a command line', () => {
    // Against the real document, which carries all four core handler types. PROCESS_HANDLER's
    // activeProcessCommand reaches Runtime.getRuntime().exec on the gateway host, and stack fix
    // D16 put handler creation within reach of the permission Cortex's service account holds --
    // so this filter is now the only thing between a dropdown and remote code execution.
    const live = liveFixture as unknown as GatewaySchemaDocument;
    expect(Object.keys(live.families.eventHandler)).toContain('PROCESS_HANDLER');
    expect(gatewayHandlerTypes(live).map(t => t.type))
      .toEqual(['EMAIL_HANDLER', 'SET_POINT_HANDLER', 'SMS_HANDLER']);

    expect(gatewayHandlerRunsCommands('PROCESS_HANDLER')).toBeTrue();
    // Named exactly. A prefix or substring test would also swallow a future EMAIL_PROCESS_HANDLER
    // and silently stop offering something harmless.
    ['EMAIL_HANDLER', 'SMS_HANDLER', 'SET_POINT_HANDLER', 'PROCESS', '', null, undefined]
      .forEach(type => expect(gatewayHandlerRunsCommands(type)).toBeFalse());
  });

  it('offers only detector types that are both suitable and renderable', () => {
    const offered = [{type: 'BINARY_STATE_DETECTOR'}, {type: 'HIGH_LIMIT'}];
    // BINARY_STATE is in the schema; HIGH_LIMIT suits the point but this gateway published no
    // schema for it, and offering it would open a dialog with no fields.
    expect(gatewayDetectorTypes(doc, offered).map(t => t.type)).toEqual(['BINARY_STATE_DETECTOR']);
    expect(gatewayDetectorTypes(doc, null)).toEqual([]);
    expect(gatewayDetectorTypes(null, offered)).toEqual([]);
  });

  it('rebuilds a recipient around its new type instead of assigning over the old one', () => {
    // The value field differs per kind: an email lives in `address`, a phone in `number`, a
    // gateway user in `username`. Mutating recipientType in place would leave the old field
    // beside the new one, and the gateway deserialises by discriminator -- so the stale field
    // would either be rejected or quietly kept as the truth.
    const email = buildRecipient('EMAIL_ADDRESS', 'ops@example.com');
    expect(email).toEqual({recipientType: 'EMAIL_ADDRESS', address: 'ops@example.com'});

    const phone = buildRecipient('PHONE_NUMBER', '+441234567890');
    expect(phone).toEqual({recipientType: 'PHONE_NUMBER', number: '+441234567890'});
    expect(phone.address).toBeUndefined();

    expect(buildRecipient('USER_EMAIL_ADDRESS', 'jo').username).toBe('jo');
    expect(buildRecipient('USER_PHONE_NUMBER', 'jo').username).toBe('jo');
    expect(buildRecipient('ALERT_LIST', 'AL_1').xid).toBe('AL_1');
  });

  it('reads a recipient of a kind it does not know without inventing one', () => {
    // A newer gateway could answer with a kind this build has no entry for. Showing it blank is
    // fine; what must not happen is this code rewriting it -- the editor replaces one row at a
    // time, so an untouched unknown recipient reaches the save exactly as it arrived.
    expect(recipientValue({recipientType: 'SOMETHING_NEW', handle: 'x'} as any)).toBe('');
    expect(buildRecipient('SOMETHING_NEW', 'x')).toEqual({recipientType: 'SOMETHING_NEW'});
    expect(recipientValue(null)).toBe('');
    expect(recipientValue({recipientType: 'EMAIL_ADDRESS'})).toBe('');
  });

  it('does not paint a logging instruction as an alarm', () => {
    // DO_NOT_LOG and IGNORE are not severities -- they tell the gateway what not to record, and
    // colouring them would invent an incident out of a setting.
    expect(gatewayAlarmTone('CRITICAL')).toBe('error');
    expect(gatewayAlarmTone('EMERGENCY')).toBe('error');
    expect(gatewayAlarmTone('URGENT')).toBe('warn');
    expect(gatewayAlarmTone('INFORMATION')).toBe('info');
    expect(gatewayAlarmTone('DO_NOT_LOG')).toBe('none');
    expect(gatewayAlarmTone('IGNORE')).toBe('none');
    expect(gatewayAlarmTone('NONE')).toBe('none');
    expect(gatewayAlarmTone(undefined)).toBe('none');
  });
});
