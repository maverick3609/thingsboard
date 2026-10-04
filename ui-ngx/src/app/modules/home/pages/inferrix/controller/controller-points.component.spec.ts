// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { of, throwError } from 'rxjs';
import { ControllerPointsComponent } from './controller-points.component';
import { InferrixControllerService, PagedRecords } from '@core/http/inferrix-controller.service';
import { ControllerPoint } from '@shared/models/inferrix-controller.models';

/**
 * Which points the live Points tab offers a write for.
 *
 * Built with `new` rather than a TestBed: the template gates the button on nothing but
 * `*ngIf="canWrite(point)"`, so `canWrite` *is* the rendered behaviour, and this keeps the spec off
 * the platform's module graph.
 */
describe('ControllerPointsComponent write gating', () => {

  // A real bench board (firmware 0.1.17): 8 local DI, 4 DO, 6 AI, 6 AO and 11 Modbus points. The
  // live reply carries no writable flag of its own, which is the whole reason the config is read.
  const LIVE_POINTS: Partial<ControllerPoint>[] = [
    {id: 8, n: 'DI8', type: 'di'},
    {id: 9, n: 'DO1', type: 'do'},
    {id: 11, n: 'DO3', type: 'do'},
    {id: 19, n: 'AO1_raw', type: 'ao'},
    {id: 22, n: 'AO3', type: 'ao'},
    {id: 26, n: 'RTU DI1', type: 'rtu'},
    {id: 30, n: 'RTU DO1', type: 'rtu'},
    {id: 34, n: 'Frequency EM', type: 'rtu'}
  ];

  // The same board's `points` config section. Only DO1, AO1_raw and RTU DO1 carry the writable bit.
  const CONFIG_POINTS = [
    {point_id: 8, flags: 0}, {point_id: 9, flags: 1}, {point_id: 11, flags: 0},
    {point_id: 19, flags: 1}, {point_id: 22, flags: 0}, {point_id: 26, flags: 0},
    {point_id: 30, flags: 1}, {point_id: 34, flags: 0}
  ];

  const paged = (records: any[]): PagedRecords =>
    ({records, total: records.length, truncated: false});

  function build(configReply: any): ControllerPointsComponent {
    const service = {
      readPoints: () => of(paged(LIVE_POINTS)),
      readConfigSection: () => configReply
    } as unknown as InferrixControllerService;
    const component = new ControllerPointsComponent(service, null);
    component.deviceId = 'd';
    component.reload();
    return component;
  }

  const offered = (component: ControllerPointsComponent) =>
    component.points.filter(point => component.canWrite(point)).map(point => point.n);

  it('offers a write only where the config says the point takes one', () => {
    expect(offered(build(of(CONFIG_POINTS)))).toEqual(['DO1', 'AO1_raw', 'RTU DO1']);
  });

  // The reported defect. `RTU DI1` is a discrete input and `Frequency EM` a power meter's reading;
  // both arrive as type `rtu`, which is what used to be enough to get a Write button.
  it('refuses the RTU inputs that share the rtu type with a writable coil', () => {
    const component = build(of(CONFIG_POINTS));
    const byName = (n: string) => component.points.find(point => point.n === n);
    expect(component.canWrite(byName('RTU DI1'))).toBeFalse();
    expect(component.canWrite(byName('Frequency EM'))).toBeFalse();
    expect(component.canWrite(byName('RTU DO1'))).toBeTrue();
  });

  // Same defect, different class: an on-board output whose config leaves the bit clear.
  it('refuses on-board outputs the config does not mark writable', () => {
    const component = build(of(CONFIG_POINTS));
    const byName = (n: string) => component.points.find(point => point.n === n);
    expect(component.canWrite(byName('DO3'))).toBeFalse();
    expect(component.canWrite(byName('AO3'))).toBeFalse();
    expect(component.canWrite(byName('DO1'))).toBeTrue();
  });

  it('never offers a write on an input class, config or no config', () => {
    [of(CONFIG_POINTS), throwError(() => new Error('nope'))].forEach(reply => {
      const component = build(reply);
      expect(component.canWrite(component.points.find(point => point.n === 'DI8'))).toBeFalse();
    });
  });

  /**
   * A config read that fails must not cost the operator the values or the tab. Falling back to the
   * class leaves it behaving as it did before the config was read at all — worse than the fix, but
   * strictly better than an empty table or a tab with no writes on a device that has them.
   */
  it('still loads the points, and falls back to the class, when the config cannot be read', () => {
    const component = build(throwError(() => new Error('device busy')));
    expect(component.points.length).toBe(LIVE_POINTS.length);
    expect(component.error).toBeFalsy();
    expect(offered(component)).toEqual(['DO1', 'DO3', 'AO1_raw', 'AO3', 'RTU DI1', 'RTU DO1',
      'Frequency EM']);
  });

  it('offers nothing at all to a reader who cannot write', () => {
    const component = build(of(CONFIG_POINTS));
    component.readonly = true;
    expect(offered(component)).toEqual([]);
  });

  // A point present live but absent from the config is not permission to write it.
  it('refuses a point the config does not mention', () => {
    const component = build(of([{point_id: 9, flags: 1}]));
    expect(offered(component)).toEqual(['DO1']);
  });
});
