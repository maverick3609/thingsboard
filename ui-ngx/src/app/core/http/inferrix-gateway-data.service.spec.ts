// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayDataPoint, GatewayDataSource, gatewayPageToPageData,
  gatewayQueryFromPageLink } from '@shared/models/inferrix-gateway-data.models';
import { PageLink } from '@shared/models/page/page-link';
import { Direction } from '@shared/models/page/sort-order';

/**
 * The data-source and data-point half of the gateway client.
 *
 * Two properties are worth a test rather than a reading: that a list is <b>one</b> request for one
 * page — the gateway is a small edge box on the far side of a LAN, and a client that fetched
 * everything to show twenty rows would be the feature's worst behaviour — and that an `xid` makes
 * the round trip untouched, since it is the identifier every point, detector and publisher on the
 * device refers to its data source by.
 */
describe('InferrixGatewayService data sources and points', () => {

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

  it('asks for one page and only one page', () => {
    let page: {items: GatewayDataSource[]; total: number};
    service.getDataSources(DEVICE, {pageSize: 20, page: 2, sortProperty: 'name', sortOrder: 'ASC'})
      .subscribe(result => page = result);

    const request = httpMock.expectOne(r => r.url === proxy('/v2/data-source'));
    expect(request.request.method).toBe('GET');
    // Typed parameters, not a query string. The platform turns these into RQL itself -- the
    // gateway reads a raw query string as an RQL expression on every verb, so forwarding one
    // would hand an operator-typed expression to the device's query parser.
    expect(request.request.params.get('pageSize')).toBe('20');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('sortProperty')).toBe('name');
    expect(request.request.params.get('sortOrder')).toBe('ASC');

    request.flush({items: [{xid: 'DS_1', name: 'Boiler'}], total: 137});
    expect(page.total).toBe(137);
    expect(page.items.length).toBe(1);
    // And nothing went looking for the other 136.
    httpMock.verify();
  });

  it('leaves a parameter out entirely rather than sending it empty', () => {
    service.getDataSources(DEVICE, {pageSize: 20, page: 0, textSearch: ''}).subscribe();
    const request = httpMock.expectOne(r => r.url === proxy('/v2/data-source'));
    // An empty search is not a search for the empty string: it would become match(name,**) and
    // scan. Undefined would be worse -- it serialises as the literal text "undefined".
    expect(request.request.params.has('textSearch')).toBeFalse();
    expect(request.request.params.has('filterField')).toBeFalse();
    request.flush({items: [], total: 0});
  });

  it('scopes a point list to its data source instead of filtering client-side', () => {
    service.getDataPoints(DEVICE,
      {pageSize: 50, page: 0, filterField: 'dataSourceXid', filterValue: 'DS_1'}).subscribe();

    const request = httpMock.expectOne(r => r.url === proxy('/v2/data-point'));
    expect(request.request.params.get('filterField')).toBe('dataSourceXid');
    expect(request.request.params.get('filterValue')).toBe('DS_1');
    request.flush({items: [], total: 0});
  });

  it('updates in place when a row already has an xid, and keeps that xid', () => {
    const dataSource: GatewayDataSource = {
      xid: 'DS_1', name: 'Boiler', modelType: 'MODBUS_IP.DS', enabled: true, host: '10.0.0.9'
    };
    let saved: GatewayDataSource;
    service.saveDataSource(DEVICE, dataSource).subscribe(result => saved = result);

    const request = httpMock.expectOne(proxy('/v2/data-source/DS_1'));
    expect(request.request.method).toBe('PUT');
    // The xid stays in the body as well as the URL. The gateway's own identifier is what every
    // point, event detector and publisher refers to this data source by, so a save that dropped
    // or reassigned it would orphan all of them.
    expect(request.request.body.xid).toBe('DS_1');
    expect(request.request.body.modelType).toBe('MODBUS_IP.DS');

    request.flush({...dataSource});
    expect(saved.xid).toBe('DS_1');
  });

  it('creates against the collection when there is no xid yet', () => {
    service.saveDataSource(DEVICE, {name: 'New', modelType: 'MODBUS_IP.DS'}).subscribe();
    const request = httpMock.expectOne(proxy('/v2/data-source'));
    expect(request.request.method).toBe('POST');
    request.flush({xid: 'DS_2', name: 'New'});
  });

  it('creates against the collection when told to, even carrying an xid', () => {
    // The add form offers the xid field so an operator can name the row, and the gateway accepts a
    // caller-chosen xid on a create. Reading the verb off the xid alone made that a PUT against
    // something that does not exist yet: 404, dialog closed, nothing saved.
    service.saveDataSource(DEVICE, {xid: 'MY_OWN_XID', name: 'New', modelType: 'MODBUS_IP.DS'},
      undefined, true).subscribe();
    const request = httpMock.expectOne(proxy('/v2/data-source'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body.xid).toBe('MY_OWN_XID');
    request.flush({xid: 'MY_OWN_XID', name: 'New'});
  });

  it('escapes an xid into the path rather than pasting it in', () => {
    // An xid is [A-Za-z0-9_.-] on the platform's allowlist, so a slash or a space cannot reach the
    // device -- but it must fail as a refused route, not as a different route.
    service.getDataPoint(DEVICE, 'a b/c').subscribe({error: () => {}});
    const request = httpMock.expectOne(proxy('/v2/data-point/a%20b%2Fc'));
    request.flush({}, {status: 403, statusText: 'Forbidden'});
  });

  it('sends enable and restart as typed booleans', () => {
    service.setDataSourceEnabled(DEVICE, 'DS_1', true, true).subscribe();
    const enable = httpMock.expectOne(r => r.url === proxy('/v2/data-source/enable-disable/DS_1'));
    expect(enable.request.method).toBe('PATCH');
    expect(enable.request.params.get('enabled')).toBe('true');
    expect(enable.request.params.get('restart')).toBe('true');
    enable.flush(null);

    // The gateway says restart is only meaningful with enabled true, so asking to restart a
    // disabled data source is not forwarded as a contradiction.
    service.setDataSourceEnabled(DEVICE, 'DS_1', false, true).subscribe();
    const disable = httpMock.expectOne(r => r.url === proxy('/v2/data-source/enable-disable/DS_1'));
    expect(disable.request.params.get('enabled')).toBe('false');
    expect(disable.request.params.get('restart')).toBe('false');
    disable.flush(null);
  });

  it('reads the latest point value and never writes one', () => {
    service.getLatestPointValue(DEVICE, 'DP_1').subscribe();
    const request = httpMock.expectOne(proxy('/v2/point-value/latest/DP_1'));
    expect(request.request.method).toBe('GET');
    request.flush([{value: 21.5, timestamp: 1}]);
    // There is deliberately no set-value method on this service: a point write is a write to live
    // building plant, and the platform's route allowlist permits only GET on /v2/point-value.
    expect((service as any).setPointValue).toBeUndefined();
  });

  it('saves a point with its locator nested, not flattened', () => {
    const point: GatewayDataPoint = {
      xid: 'DP_1', name: 'Supply temp', dataSourceXid: 'DS_1',
      pointLocator: {modelType: 'MODBUS.PL', dataType: 'NUMERIC', offset: 40001}
    };
    service.saveDataPoint(DEVICE, point).subscribe();
    const request = httpMock.expectOne(proxy('/v2/data-point/DP_1'));
    expect(request.request.body.pointLocator.offset).toBe(40001);
    expect(request.request.body.pointLocator.modelType).toBe('MODBUS.PL');
    request.flush({...point});
  });
});

describe('gateway page mapping', () => {

  it('drives hasNext from the total, never from how full the page came back', () => {
    // The gateway has no hasMore, and its total counts every row matching the filter while
    // ignoring limit and offset. So a short page is the end of the filtered set -- not the end of
    // the data, and not a reason to stop paging.
    const page = gatewayPageToPageData({items: [{xid: 'a'}], total: 137},
      new PageLink(20, 0));
    expect(page.totalElements).toBe(137);
    expect(page.totalPages).toBe(7);
    expect(page.hasNext).toBeTrue();

    const last = gatewayPageToPageData({items: [{xid: 'a'}], total: 137}, new PageLink(20, 6));
    expect(last.hasNext).toBeFalse();
  });

  it('survives a gateway that answers with neither items nor a total', () => {
    const page = gatewayPageToPageData({} as any, new PageLink(20, 0));
    expect(page.data).toEqual([]);
    expect(page.totalElements).toBe(0);
    expect(page.hasNext).toBeFalse();
  });

  it('carries a page link across as typed fields', () => {
    const query = gatewayQueryFromPageLink(
      new PageLink(20, 1, 'boiler', {property: 'name', direction: Direction.DESC}));
    expect(query).toEqual({
      pageSize: 20, page: 1, textSearch: 'boiler', sortProperty: 'name', sortOrder: 'DESC'
    });
  });
});

describe('InferrixGatewayService schema document', () => {

  const DEVICE = 'device-1';
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

  it('fetches one gateway\'s schemas once however many panels ask', () => {
    // A real document is 531 KB and four panels want it. ThingsBoard ships with HTTP compression
    // off by default, so without this a details page pulls two megabytes to render four forms.
    const seen: any[] = [];
    service.getSchemas(DEVICE).subscribe(document => seen.push(document));
    service.getSchemas(DEVICE).subscribe(document => seen.push(document));

    httpMock.expectOne(`/api/inferrix/gateways/${DEVICE}/schemas`).flush({families: {}, components: {schemas: {}}});

    service.getSchemas(DEVICE).subscribe(document => seen.push(document));
    httpMock.expectNone(`/api/inferrix/gateways/${DEVICE}/schemas`);
    expect(seen.length).toBe(3);
  });

  it('keeps one gateway\'s schemas out of another\'s', () => {
    service.getSchemas('gateway-a').subscribe();
    service.getSchemas('gateway-b').subscribe();
    httpMock.expectOne('/api/inferrix/gateways/gateway-a/schemas').flush({families: {}, components: {schemas: {}}});
    httpMock.expectOne('/api/inferrix/gateways/gateway-b/schemas').flush({families: {}, components: {schemas: {}}});
  });

  it('does not cache a failure', () => {
    // One unreachable moment must not blank every form on this gateway for the whole window.
    service.getSchemas(DEVICE).subscribe({next: () => {}, error: () => {}});
    httpMock.expectOne(`/api/inferrix/gateways/${DEVICE}/schemas`)
      .flush('nope', {status: 503, statusText: 'Service Unavailable'});

    service.getSchemas(DEVICE).subscribe({next: () => {}, error: () => {}});
    httpMock.expectOne(`/api/inferrix/gateways/${DEVICE}/schemas`)
      .flush({families: {}, components: {schemas: {}}});
  });
});

describe('point locator pairing', () => {

  const DEVICE = 'device-1';
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

  it('reads the pairing the gateway publishes rather than deriving one', () => {
    // The only place it appears. The naming looks like a `.DS` -> `.PL` rename and is not one:
    // MODBUS_IP.DS and MODBUS_SERIAL.DS both take MODBUS.PL, and no MODBUS_IP.PL exists. This is
    // the live response shape, verbatim.
    let types: any;
    service.getDataSourceTypes(DEVICE).subscribe(page => types = page.items);
    httpMock.expectOne(r => r.url.endsWith('/proxy/v2/data-source-types')).flush({
      items: [
        {type: 'MODBUS_IP.DS', name: 'Modbus I/P', pointLocatorType: 'MODBUS.PL'},
        {type: 'MODBUS_SERIAL.DS', name: 'Modbus Serial', pointLocatorType: 'MODBUS.PL'},
        {type: 'VIRTUAL.DS', name: 'Virtual', pointLocatorType: 'VIRTUAL.PL'},
        // One real type answers null, so a caller needs an answer for "the gateway did not say".
        {type: 'BACNET_MSTP.DS', name: 'BACnet MS/TP', pointLocatorType: null}
      ],
      total: 4
    });

    const pairing = new Map(types.map((t: any) => [t.type, t.pointLocatorType]));
    expect(pairing.get('MODBUS_IP.DS')).toBe('MODBUS.PL');
    expect(pairing.get('MODBUS_SERIAL.DS')).toBe('MODBUS.PL');
    expect(pairing.get('BACNET_MSTP.DS')).toBeNull();
    // The rename that used to live in this codebase would have produced MODBUS_IP.PL here.
    expect([...pairing.values()]).not.toContain('MODBUS_IP.PL');
  });
});
