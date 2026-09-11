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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { InferrixControllerService, PagedRecords } from '@core/http/inferrix-controller.service';

/**
 * The paged device read.
 *
 * Every page is capped at 2 KB, so a controller with more than a handful of points answers in
 * pieces and the client has to walk them. The cases that matter are the ones that decide when to
 * stop: the device saying it is done, and a device too old to page at all.
 */
describe('InferrixControllerService paging', () => {

  const DEVICE = 'device-1';
  const url = (offset: number) => `/api/inferrix/controllers/${DEVICE}/proxy/api/v1/points?offset=${offset}`;

  let service: InferrixControllerService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [InferrixControllerService]
    });
    service = TestBed.inject(InferrixControllerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('walks every page and advances the offset by what it actually received', () => {
    let result: PagedRecords;
    service.readPoints(DEVICE).subscribe(r => result = r);

    httpMock.expectOne(url(0)).flush({
      points: [{id: 1}, {id: 2}], offset: 0, truncated: true, total: 5
    });
    // Advanced by 2 — the count received, not a fixed page size, which is what the device asks for.
    httpMock.expectOne(url(2)).flush({
      points: [{id: 3}, {id: 4}], offset: 2, truncated: true, total: 5
    });
    httpMock.expectOne(url(4)).flush({
      points: [{id: 5}], offset: 4, truncated: false, total: 5
    });

    expect(result.records.map(p => p.id)).toEqual([1, 2, 3, 4, 5]);
    expect(result.total).toBe(5);
    // The walk reached the end, so nothing is being hidden from the operator.
    expect(result.truncated).toBeFalse();
  });

  it('refuses to page a device that does not echo the offset, and still warns', () => {
    // Firmware before 0.1.14 had no `offset` on /points and echoes none back. Asking for a second
    // page would re-serve the first forever, so the walk stops — but the device said there is more,
    // and the operator has to keep being told that.
    let result: PagedRecords;
    service.readPoints(DEVICE).subscribe(r => result = r);

    httpMock.expectOne(url(0)).flush({points: [{id: 1}, {id: 2}], truncated: true, total: 40});

    expect(result.records.map(p => p.id)).toEqual([1, 2]);
    expect(result.total).toBe(40);
    expect(result.truncated).toBeTrue();
  });

  it('stops on a single complete page', () => {
    let result: PagedRecords;
    service.readPoints(DEVICE).subscribe(r => result = r);

    httpMock.expectOne(url(0)).flush({points: [{id: 1}], offset: 0, truncated: false, total: 1});

    expect(result.records.length).toBe(1);
    expect(result.truncated).toBeFalse();
  });

  it('stops when a page comes back empty even though the device still says truncated', () => {
    // A device that answered `truncated` forever with nothing in it would otherwise spin.
    let result: PagedRecords;
    service.readPoints(DEVICE).subscribe(r => result = r);

    httpMock.expectOne(url(0)).flush({points: [], offset: 0, truncated: true, total: 7});

    expect(result.records).toEqual([]);
    expect(result.total).toBe(7);
  });

  it('gives each subscription its own walk', () => {
    // The points tab re-subscribes on every poll tick; page state kept per call rather than per
    // subscription would leak the previous tick's count into the next one.
    const observable = service.readPoints(DEVICE);

    let first: PagedRecords;
    observable.subscribe(r => first = r);
    httpMock.expectOne(url(0)).flush({points: [{id: 1}], offset: 0, truncated: false, total: 1});

    let second: PagedRecords;
    observable.subscribe(r => second = r);
    httpMock.expectOne(url(0)).flush({points: [{id: 2}], offset: 0, truncated: false, total: 1});

    expect(first.records.map(p => p.id)).toEqual([1]);
    expect(second.records.map(p => p.id)).toEqual([2]);
  });

});
