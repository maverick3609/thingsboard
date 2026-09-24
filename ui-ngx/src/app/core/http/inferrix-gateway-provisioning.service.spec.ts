// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayDataSource } from '@shared/models/inferrix-gateway-data.models';

/**
 * The platform-provisioning half of the gateway client.
 *
 * Three things here are easy to get wrong by reading and cheap to pin down: provisioning is keyed
 * by the data source's <b>xid</b> while unprovisioning is keyed by its numeric <b>id</b> — the only
 * write in this service that is — the profile id on the wire is the gateway's own row id and not
 * the platform's profile UUID, and a data source name that contains a slash must not be able to
 * climb out of its path segment.
 */
describe('InferrixGatewayService platform provisioning', () => {

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

  it('provisions by xid and the gateway-local profile id', () => {
    service.provisionDataSource(DEVICE, 'DS_7233a02a', 8).subscribe();

    const request = httpMock.expectOne(
      r => r.url === proxy('/v2/platform-integration/provision/DS_7233a02a/8'));
    expect(request.request.method).toBe('PUT');
    request.flush({});
  });

  it('escapes an xid rather than letting it add a path segment', () => {
    service.provisionDataSource(DEVICE, 'DS_a/../../v2/auth', 3).subscribe();

    const request = httpMock.expectOne(r => r.url.includes('/v2/platform-integration/provision/'));
    expect(request.request.url).toContain('DS_a%2F..%2F..%2Fv2%2Fauth');
    expect(request.request.url).not.toContain('/v2/auth');
    request.flush({});
  });

  it('unprovisions by the numeric data source id, not the xid', () => {
    service.unprovisionDataSource(DEVICE, 2).subscribe();

    const request = httpMock.expectOne(
      r => r.url === proxy('/v2/platform-integration/provisioned/2'));
    expect(request.request.method).toBe('DELETE');
    request.flush({});
  });

  it('reads the two lists from their own endpoints, one page each', () => {
    let unprovisioned: {items: GatewayDataSource[]; total: number};
    service.getUnprovisionedDataSources(DEVICE, {pageSize: 10, page: 0})
      .subscribe(result => unprovisioned = result);

    const first = httpMock.expectOne(
      r => r.url === proxy('/v2/platform-integration/unprovisioned'));
    expect(first.request.method).toBe('GET');
    first.flush({items: [{name: 'Chiller 1 DS'}], total: 11});
    expect(unprovisioned.total).toBe(11);

    service.getProvisionedDataSources(DEVICE, {pageSize: 10, page: 0}).subscribe();
    httpMock.expectOne(r => r.url === proxy('/v2/platform-integration/provisioned')).flush(
      {items: [], total: 0});
  });

  it('keeps the profile sync on its own route so opening the tab never writes', () => {
    service.getGatewayDeviceProfiles(DEVICE, {pageSize: 100, page: 0}).subscribe();
    const read = httpMock.expectOne(r => r.url === proxy('/v2/platform-integration/device-profile'));
    expect(read.request.url).not.toContain('/sync');
    read.flush({items: [], total: 0});

    service.syncGatewayDeviceProfiles(DEVICE, {pageSize: 100, page: 0}).subscribe();
    const sync = httpMock.expectOne(
      r => r.url === proxy('/v2/platform-integration/device-profile/sync'));
    expect(sync.request.method).toBe('GET');
    sync.flush({items: [], total: 0});
  });
});
