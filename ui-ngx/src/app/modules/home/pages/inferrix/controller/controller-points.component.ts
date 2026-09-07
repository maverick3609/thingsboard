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

import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { ControllerPoint, pointQualityColor } from '@shared/models/inferrix-controller.models';

/**
 * Live point values from the controller's active program.
 *
 * Read-only: the firmware has no REST point write, only an MQTT `set` command, so a value cannot be
 * forced from here. The point set itself comes from the compiled ICC and is changed in the config
 * plane, not on this screen.
 */
@Component({
  selector: 'tb-controller-points',
  templateUrl: './controller-points.component.html',
  styleUrls: ['./controller-points.component.scss'],
  standalone: false
})
export class ControllerPointsComponent implements OnInit, OnDestroy {

  @Input() deviceId: string;

  points: ControllerPoint[] = [];
  total = 0;
  truncated = false;
  loading = false;
  error: string;
  autoRefresh = false;

  readonly displayedColumns = ['id', 'n', 'type', 'address', 'v', 'q', 'age'];
  readonly qualityColor = pointQualityColor;

  private refreshSubscription: Subscription;

  constructor(private controllerService: InferrixControllerService) {}

  ngOnInit(): void {
    this.reload();
  }

  ngOnDestroy(): void {
    this.refreshSubscription?.unsubscribe();
  }

  /**
   * The device answers one request at a time and the poll is only meaningful while someone is
   * watching, so it is off by default and stops with the component.
   */
  toggleAutoRefresh(enabled: boolean): void {
    this.autoRefresh = enabled;
    this.refreshSubscription?.unsubscribe();
    this.refreshSubscription = null;
    if (enabled) {
      this.refreshSubscription = timer(5000, 5000).pipe(
        switchMap(() => this.controllerService.proxy<any>(this.deviceId, 'GET', '/api/v1/points',
          null, {ignoreErrors: true}))
      ).subscribe({
        next: response => this.apply(response),
        error: error => {
          this.error = this.messageOf(error);
          this.toggleAutoRefresh(false);
        }
      });
    }
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    this.controllerService.proxy<any>(this.deviceId, 'GET', '/api/v1/points', null,
      {ignoreErrors: true}).subscribe({
      next: response => {
        this.apply(response);
        this.loading = false;
      },
      error: error => {
        this.error = this.messageOf(error);
        this.loading = false;
      }
    });
  }

  address(point: ControllerPoint): string {
    return point.bus === undefined || point.bus === null ? '—' : `${point.bus}/${point.unit ?? '—'}`;
  }

  value(point: ControllerPoint): string {
    if (typeof point.v === 'boolean') {
      return point.v ? 'true' : 'false';
    }
    return point.v === undefined || point.v === null ? '—' : String(point.v);
  }

  private apply(response: any): void {
    this.points = response?.points ?? [];
    this.total = response?.total ?? this.points.length;
    this.truncated = !!response?.truncated;
  }

  private messageOf(error: any): string {
    return error?.error?.message || error?.message || 'Request failed';
  }
}
