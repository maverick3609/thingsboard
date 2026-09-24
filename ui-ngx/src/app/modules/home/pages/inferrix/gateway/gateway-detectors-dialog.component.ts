// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { MatDialog, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TranslateService } from '@ngx-translate/core';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayModelDialogComponent,
  GatewayModelDialogData } from '@home/pages/inferrix/gateway/gateway-model-dialog.component';
import { GATEWAY_IDENTITY_FIELDS, GatewayDataPoint,
  gatewayErrorMessage } from '@shared/models/inferrix-gateway-data.models';
import { DETECTOR_STRUCTURAL_FIELDS, GatewayEventDetector, gatewayDetectorTypes,
  GatewayTypeOption } from '@shared/models/inferrix-gateway-event.models';
import { GatewaySchemaDocument,
  schemaToFormProperties } from '@shared/models/inferrix-gateway-schema.models';

export interface GatewayDetectorsDialogData {
  deviceId: string;
  point: GatewayDataPoint;
  readonly: boolean;
}

/**
 * One read, then paged here. More detectors than any one point has, so the gateway is asked once
 * and the page control works on what came back -- which is also what lets the name column sort,
 * since the gateway cannot order by a field that lives inside the detector's JSON blob.
 */
const DETECTORS_PER_POINT = 100;

/**
 * The event detectors watching one data point.
 *
 * Reached from the point rather than from a list of its own, because a detector always names one
 * — a detector with no point is not a thing the gateway can store, and a flat list would make the
 * operator type one. The list is scoped by the point's numeric id, which is what the gateway's
 * detector table stores; the string `sourceId` on the detector body is that same point's xid.
 *
 * Which detector types are offered is two questions with two different answers. The **gateway**
 * decides which suit the point's data type (`/v2/event-detector-type/{dataType}`, backed by each
 * definition's `supportedDataTypes`); the **schema** decides which of those can be rendered. A
 * type in the first set and not the second opens a dialog with no fields.
 */
@Component({
  selector: 'tb-gateway-detectors-dialog',
  templateUrl: './gateway-detectors-dialog.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayDetectorsDialogComponent
  extends DialogComponent<GatewayDetectorsDialogComponent, boolean> {

  detectors: GatewayEventDetector[] = [];
  /** The slice on screen. */
  pagedDetectors: GatewayEventDetector[] = [];
  types: GatewayTypeOption[] = [];
  loading = false;
  error: string;
  changed = false;

  readonly displayedColumns = ['name', 'detectorType', 'alarmLevel', 'handlers', 'actions'];
  readonly pageSizeOptions = [10, 20, 50, 100];
  pageSize = 10;
  pageIndex = 0;

  private schemas: GatewaySchemaDocument;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: GatewayDetectorsDialogData,
              public dialogRef: MatDialogRef<GatewayDetectorsDialogComponent, boolean>,
              private gatewayService: InferrixGatewayService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService) {
    super(store, router, dialogRef);
    this.load();
  }

  get dataType(): string {
    return this.data.point?.pointLocator?.dataType;
  }

  /**
   * The gateway publishes a human name for every detector type it offers, so show that rather than
   * the enum. A detector whose type is not in the offered set -- one added before the point's data
   * type changed, say -- still has to say something, and the enum is what there is.
   */
  typeName(detector: GatewayEventDetector): string {
    return this.types.find(type => type.type === detector.detectorType)?.name
      || detector.detectorType;
  }

  handlerSummary(detector: GatewayEventDetector): string {
    const xids = Array.isArray(detector?.handlerXids) ? detector.handlerXids : [];
    return xids.length ? xids.join(', ') : '—';
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    // Ordered here, not by the gateway. A detector's name lives inside its JSON blob, and the
    // gateway can only sort by a real column of `event_detectors` -- `id`, `xid` or `dataPointId`;
    // asking it for `name` is a 500. One point's detectors always fit in a single page, so the
    // order the name column promises is ours to deliver.
    this.gatewayService.getDetectorsForPoint(this.data.deviceId, this.data.point.id,
      {pageSize: DETECTORS_PER_POINT, page: 0},
      {ignoreLoading: true}).subscribe({
      next: page => {
        this.detectors = (page?.items ?? [])
          .sort((left, right) => (left.name ?? '').localeCompare(right.name ?? ''));
        this.slicePage();
        this.loading = false;
      },
      error: error => {
        this.error = gatewayErrorMessage(error);
        this.detectors = [];
        this.pagedDetectors = [];
        this.loading = false;
      }
    });
  }

  pageChanged(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.slicePage();
  }

  add(type: GatewayTypeOption): void {
    this.open({detectorType: type.type, sourceId: this.data.point.xid, alarmLevel: 'INFORMATION'},
      this.translate.instant('inferrix.gateway.add-detector'));
  }

  edit(detector: GatewayEventDetector): void {
    this.open(detector, detector.name);
  }

  delete(detector: GatewayEventDetector): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-detector-title', {name: detector.name}),
      this.translate.instant('inferrix.gateway.delete-detector-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteDetector(this.data.deviceId, detector.xid, {ignoreLoading: true})
          .subscribe({
            next: () => {
              this.changed = true;
              this.reload();
            },
            error: error => this.error = gatewayErrorMessage(error)
          });
      }
    });
  }

  close(): void {
    this.dialogRef.close(this.changed);
  }

  /**
   * Deleting the last detector on the last page would otherwise leave the operator looking at an
   * empty table with no way back, so the index comes down with the list.
   */
  private slicePage(): void {
    const lastPage = Math.max(0, Math.ceil(this.detectors.length / this.pageSize) - 1);
    this.pageIndex = Math.min(this.pageIndex, lastPage);
    const start = this.pageIndex * this.pageSize;
    this.pagedDetectors = this.detectors.slice(start, start + this.pageSize);
  }

  private load(): void {
    this.loading = true;
    forkJoin({
      schemas: this.gatewayService.getSchemas(this.data.deviceId, {ignoreLoading: true})
        .pipe(catchError(() => of(null as GatewaySchemaDocument))),
      // Only asked for when the point has a data type. A locator with none is a point the gateway
      // cannot attach a detector to either, and the endpoint would 404 on an empty path segment.
      types: this.dataType
        ? this.gatewayService.getDetectorTypes(this.data.deviceId, this.dataType,
            {ignoreLoading: true}).pipe(catchError(() => of({items: [], total: 0})))
        : of({items: [], total: 0})
    }).subscribe(result => {
      this.schemas = result.schemas;
      this.types = gatewayDetectorTypes(result.schemas, result.types?.items ?? []);
      this.reload();
    });
  }

  private open(detector: GatewayEventDetector, title: string): void {
    const structural = [...GATEWAY_IDENTITY_FIELDS, ...DETECTOR_STRUCTURAL_FIELDS];
    const properties = schemaToFormProperties(this.schemas, 'eventDetector', detector.detectorType)
      .filter(property => !structural.includes(property.id));

    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayEventDetector>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, model: detector, properties, readonly: this.data.readonly}
      }).afterClosed().subscribe(saved => {
      if (saved) {
        // sourceId is re-asserted rather than trusted: it is filtered out of the form, so the only
        // way it could change is by something else having gone wrong.
        this.gatewayService.saveDetector(this.data.deviceId,
          {...saved, sourceId: this.data.point.xid}, {ignoreLoading: true}).subscribe({
          next: () => {
            this.changed = true;
            this.reload();
          },
          error: error => this.error = gatewayErrorMessage(error)
        });
      }
    });
  }
}
