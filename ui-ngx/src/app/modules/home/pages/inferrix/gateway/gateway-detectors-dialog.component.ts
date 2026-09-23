// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { MatDialog, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
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

/** More detectors than any one point has; a page control here would be furniture. */
const DETECTORS_PER_POINT = 100;

/**
 * The event detectors watching one data point.
 *
 * Reached from the point rather than from a list of its own, because a detector's `sourceId` is a
 * point xid — a detector with no point is not a thing the gateway can store, and a flat list would
 * make the operator type one.
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
  types: GatewayTypeOption[] = [];
  loading = false;
  error: string;
  changed = false;

  readonly displayedColumns = ['name', 'detectorType', 'alarmLevel', 'handlers', 'actions'];

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

  handlerSummary(detector: GatewayEventDetector): string {
    const xids = Array.isArray(detector?.handlerXids) ? detector.handlerXids : [];
    return xids.length ? xids.join(', ') : '—';
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    this.gatewayService.getDetectorsForPoint(this.data.deviceId, this.data.point.xid,
      {pageSize: DETECTORS_PER_POINT, page: 0, sortProperty: 'name', sortOrder: 'ASC'},
      {ignoreLoading: true}).subscribe({
      next: page => {
        this.detectors = page?.items ?? [];
        this.loading = false;
      },
      error: error => {
        this.error = gatewayErrorMessage(error);
        this.detectors = [];
        this.loading = false;
      }
    });
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
