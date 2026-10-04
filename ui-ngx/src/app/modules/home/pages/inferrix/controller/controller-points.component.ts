// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { forkJoin, Observable, of, Subscription, timer } from 'rxjs';
import { catchError, map, switchMap, takeUntil } from 'rxjs/operators';
import { ControllerPanelComponent } from '@home/pages/inferrix/controller/controller-panel.component';
import { InferrixControllerService, PagedRecords } from '@core/http/inferrix-controller.service';
import {
  ControllerPoint,
  POINTS_CONFIG_SECTION,
  pointQualityColor,
  WRITABLE_POINT_TYPES,
  writablePointIds
} from '@shared/models/inferrix-controller.models';
import {
  ControllerPointWriteDialogComponent,
  ControllerPointWriteDialogData
} from '@home/pages/inferrix/controller/controller-point-write-dialog.component';

/**
 * Live point values from the controller's active program, and a write for the points that take one.
 *
 * The point set itself comes from the compiled ICC and is changed in the config plane, not on this
 * screen.
 *
 * **The write is offered per point, not per class.** It used to be offered for every point in a
 * class that can ever be written — `do`, `ao`, `rtu` — on the reasoning that the device's own
 * refusal is what the operator should see. That reads an input as settable: `rtu` covers every
 * Modbus point whatever its function code, so the four discrete inputs on a bench board got a
 * Write button alongside a power meter's frequency and two sensor readings, and on-board `do`/`ao`
 * channels whose config leaves the writable bit clear got one too — 13 of 36 points on that board.
 * A refusal after the fact is not the same as not offering the write, least of all on a screen
 * whose whole job is to say what the controller is doing.
 *
 * So the writable bit is read from the applied config, which is the only place it exists: the live
 * `GET /api/v1/points` reply carries no flag of its own (firmware 0.1.17). Read once per explicit
 * load rather than on each poll tick, because flags change only when a config is applied.
 */
@Component({
  selector: 'tb-controller-points',
  templateUrl: './controller-points.component.html',
  styleUrls: ['./controller-table.scss'],
  standalone: false
})
export class ControllerPointsComponent extends ControllerPanelComponent {

  points: ControllerPoint[] = [];
  pagedPoints: ControllerPoint[] = [];
  readonly pageSizeOptions = [10, 20, 50, 100];
  pageSize = 10;
  pageIndex = 0;
  total = 0;
  truncated = false;
  loading = false;
  error: string;
  autoRefresh = false;

  readonly displayedColumns = ['id', 'n', 'type', 'address', 'v', 'q', 'age', 'actions'];
  readonly qualityColor = pointQualityColor;

  private refreshSubscription: Subscription;
  /** Point ids the device accepts a write for; `null` while unread or if the config read failed. */
  private writableIds: Set<number> = null;

  constructor(private controllerService: InferrixControllerService,
              private dialog: MatDialog) {
    super();
  }

  override ngOnDestroy(): void {
    this.refreshSubscription?.unsubscribe();
    super.ngOnDestroy();
  }

  protected load(): void {
    this.reload();
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
        switchMap(() => this.controllerService.readPoints(this.deviceId)),
        takeUntil(this.destroy$)
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
    forkJoin({points: this.controllerService.readPoints(this.deviceId), writable: this.readWritable()})
      .subscribe({
        next: ({points, writable}) => {
          this.writableIds = writable;
          this.apply(points);
          this.loading = false;
        },
        error: error => {
          this.error = this.messageOf(error);
          this.loading = false;
        }
      });
  }

  /**
   * Which points the device will accept a write for, or `null` when that could not be established.
   *
   * A failed config read must not fail the tab — the values are what the operator came for, and
   * they are already on their way in the other half of the `forkJoin`. It is also not reported:
   * there is nothing for the operator to do about it, and the fallback in {@link canWrite} leaves
   * the tab behaving exactly as it did before this was read at all.
   */
  private readWritable(): Observable<Set<number>> {
    return this.controllerService.readConfigSection(this.deviceId, POINTS_CONFIG_SECTION, false)
      .pipe(map(records => writablePointIds(records)), catchError(() => of(null)));
  }

  /**
   * Both conditions, deliberately, and in this order.
   *
   * The flag is the device's own answer and could stand alone, which would also offer a write on
   * the classes this list leaves out — a Wirepas, system-register or peer point whose config sets
   * the bit. Those have never been offered one and there is no board here to try it on, so the
   * class stays a necessary condition: every change this makes to what an operator sees is a
   * button *removed*, which cannot break a workflow that works today. Loosen it only against a
   * device that demonstrably accepts such a write.
   *
   * With no config read there is no better answer than the class, which is where this started:
   * offer it and let the device refuse.
   */
  canWrite(point: ControllerPoint): boolean {
    if (this.readonly || !WRITABLE_POINT_TYPES.includes(point.type)) {
      return false;
    }
    return this.writableIds ? this.writableIds.has(point.id) : true;
  }

  write(point: ControllerPoint): void {
    this.dialog.open<ControllerPointWriteDialogComponent, ControllerPointWriteDialogData, boolean>(
      ControllerPointWriteDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {deviceId: this.deviceId, point}
      }).afterClosed().subscribe(written => {
      if (written) {
        this.reload();
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

  pageChanged(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.slicePage();
  }

  /** Auto-refresh re-applies the whole list every 5s, so the page has to survive it. */
  private slicePage(): void {
    const lastPage = Math.max(0, Math.ceil(this.points.length / this.pageSize) - 1);
    this.pageIndex = Math.min(this.pageIndex, lastPage);
    const start = this.pageIndex * this.pageSize;
    this.pagedPoints = this.points.slice(start, start + this.pageSize);
  }

  private apply(result: PagedRecords): void {
    this.points = result.records as ControllerPoint[];
    this.slicePage();
    this.total = result.total;
    // True only when points exist that are not on screen: the walk hit the page cap, or the
    // device predates the paging support and can only serve its first page.
    this.truncated = result.truncated;
  }

}
