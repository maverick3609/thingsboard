// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayModelDialogComponent,
  GatewayModelDialogData } from '@home/pages/inferrix/gateway/gateway-model-dialog.component';
import { GatewayDetectorsDialogComponent, GatewayDetectorsDialogData }
  from '@home/pages/inferrix/gateway/gateway-detectors-dialog.component';
import { GATEWAY_IDENTITY_FIELDS, GatewayDataPoint, GatewayDataSource, GatewayListQuery,
  GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { componentToFormProperties, GatewaySchemaDocument,
  schemaToFormProperties } from '@shared/models/inferrix-gateway-schema.models';
import { FormProperty } from '@shared/models/dynamic-form.models';

/** How many data sources the picker offers before an operator has to search instead. */
const DATA_SOURCE_PICKER_LIMIT = 200;

/**
 * The points of one data source.
 *
 * Scoped to a data source deliberately, and that is the panel's main design decision. A gateway in
 * a building carries thousands of points; an unscoped list would page through all of them, and an
 * operator looking for the points of one Modbus device would never find them. The scoping is done
 * on the device — the platform turns the chosen data source into an RQL `eq` term — so it costs one
 * page, not a filter over everything.
 *
 * **Live values are read one at a time, on request.** The gateway has no bulk point-value endpoint,
 * so a value column that filled itself would be one call per visible row against a small edge box
 * behind a per-device connection limiter. A button per row keeps the cost visible and bounded.
 */
@Component({
  selector: 'tb-gateway-data-points',
  templateUrl: './gateway-data-points.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayDataPointsComponent extends GatewayListPanelComponent<GatewayDataPoint> {

  dataSources: GatewayDataSource[] = [];
  selectedDataSource: GatewayDataSource;

  /** Latest value per xid, filled only for the rows an operator asked about. */
  values: {[xid: string]: {text: string; timestamp?: number; loading?: boolean}} = {};

  readonly displayedColumns = ['name', 'dataType', 'xid', 'value', 'enabled', 'actions'];

  private schemas: GatewaySchemaDocument;

  constructor(private gatewayService: InferrixGatewayService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  protected load(): void {
    this.gatewayService.getSchemas(this.deviceId, {ignoreLoading: true}).subscribe({
      next: schemas => {
        this.schemas = schemas;
        this.cd.markForCheck();
      },
      error: () => {}
    });
    this.loading = true;
    this.gatewayService.getDataSources(this.deviceId,
      {pageSize: DATA_SOURCE_PICKER_LIMIT, page: 0, sortProperty: 'name', sortOrder: 'ASC'},
      {ignoreLoading: true}).subscribe({
      next: page => {
        this.dataSources = page?.items ?? [];
        this.selectedDataSource = this.dataSources[0];
        this.loading = false;
        if (this.selectedDataSource) {
          this.reload();
        } else {
          this.cd.markForCheck();
        }
      },
      error: error => {
        this.error = this.messageOf(error);
        this.loading = false;
        this.cd.markForCheck();
      }
    });
  }

  dataSourceChanged(dataSource: GatewayDataSource): void {
    this.selectedDataSource = dataSource;
    this.pageIndex = 0;
    this.values = {};
    this.reload();
  }

  /**
   * Null until a data source is chosen, which shows an empty list without calling the device —
   * an unscoped point query would page through every point on the gateway.
   */
  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayDataPoint>> {
    return this.selectedDataSource
      ? this.gatewayService.getDataPoints(this.deviceId, {...query,
          filterField: 'dataSourceXid', filterValue: this.selectedDataSource.xid},
          {ignoreLoading: true})
      : null;
  }

  /**
   * One point's most recent value.
   *
   * Read-only. Writing one is a write to live building plant through a proxy whose caller may hold
   * nothing but tenant administration, and the platform's route allowlist permits only GET here
   * until that is decided on its own terms.
   */
  readValue(row: GatewayDataPoint): void {
    this.values[row.xid] = {text: '', loading: true};
    this.gatewayService.getLatestPointValue(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: history => {
        const latest = Array.isArray(history) ? history[0] : null;
        this.values[row.xid] = latest
          ? {text: this.render(latest.value), timestamp: latest.timestamp}
          : {text: this.translate.instant('inferrix.gateway.no-value')};
        this.cd.markForCheck();
      },
      error: error => {
        this.values[row.xid] = {text: this.messageOf(error)};
        this.cd.markForCheck();
      }
    });
  }

  /**
   * The detectors watching this point.
   *
   * Opened from the point and not from a tab of their own: a detector's `sourceId` is a point xid,
   * so a flat list would make the operator type one.
   */
  detectors(row: GatewayDataPoint): void {
    this.dialog.open<GatewayDetectorsDialogComponent, GatewayDetectorsDialogData, boolean>(
      GatewayDetectorsDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {deviceId: this.deviceId, point: row, readonly: this.readonly}
      });
  }

  /**
   * The point-locator type a new point on this data source must carry, taken from a point that
   * already has one.
   *
   * There is no way to derive it and no endpoint that publishes it. The stack names its Jackson
   * subtypes `<PROTOCOL>.DS` and `<PROTOCOL>.PL`, which makes a rename look like the pairing, and
   * for some protocols it is -- but `MODBUS_IP.DS` takes `MODBUS.PL`, there is no `MODBUS_IP.PL`
   * at all, and `MODBUS_SLAVE_DEVICE.PL` and `MODBUS_CONTROLLER.PL` sit beside it. So the rename
   * is a coincidence that holds for the four protocols a test fixture happens to contain.
   *
   * Sending the wrong one is not a validation error the operator can read: the gateway casts the
   * deserialized locator to the data source's own locator class and answers HTTP 500
   * `ClassCastException`, and sending none at all answers HTTP 500 `NullPointerException`. Both
   * verified live. A guess that is wrong for Modbus is therefore worse than no guess.
   *
   * A sibling point is the one authority that is free -- this panel is already scoped to one data
   * source, so its loaded rows are points of exactly this data source. When there are none, the
   * type is unknowable from here and adding is refused rather than attempted.
   */
  get locatorType(): string | null {
    return this.rows.find(row => row.pointLocator?.modelType)?.pointLocator?.modelType ?? null;
  }

  add(): void {
    if (!this.selectedDataSource || !this.locatorType) {
      return;
    }
    this.open({
      dataSourceXid: this.selectedDataSource.xid,
      enabled: false,
      pointLocator: {modelType: this.locatorType}
    }, this.translate.instant('inferrix.gateway.add-data-point'));
  }

  edit(row: GatewayDataPoint): void {
    this.gatewayService.getDataPoint(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.open(full, full.name),
      error: error => this.fail(error)
    });
  }

  toggleEnabled(row: GatewayDataPoint, enabled: boolean): void {
    this.gatewayService.setDataPointEnabled(this.deviceId, row.xid, enabled, {ignoreLoading: true})
      .subscribe({
        next: () => this.reload(),
        error: error => {
          this.fail(error);
          this.reload();
        }
      });
  }

  delete(row: GatewayDataPoint): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-data-point-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-data-point-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteDataPoint(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  private open(model: GatewayDataPoint, title: string): void {
    const locatorType = model.pointLocator?.modelType ?? this.locatorType;
    const locatorProperties = this.propertiesFor('pointLocator', locatorType);
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayDataPoint>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          title,
          model,
          properties: this.pointProperties(),
          locatorProperties,
          locatorTitle: this.translate.instant('inferrix.gateway.point-locator'),
          locatorMissing: !locatorProperties.length,
          readonly: this.readonly
        }
      }).afterClosed().subscribe(saved => {
      if (saved) {
        this.gatewayService.saveDataPoint(this.deviceId, saved, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  /**
   * A point's own fields.
   *
   * `DataPointModel` is a shared component rather than a family entry, because there is exactly one
   * point model: every protocol difference lives in the nested locator, which is rendered as its
   * own form below.
   */
  private pointProperties(): FormProperty[] {
    return this.withoutStructuralFields(
      componentToFormProperties(this.schemas, 'DataPointModel'));
  }

  private propertiesFor(family: string, modelType: string): FormProperty[] {
    return modelType
      ? this.withoutStructuralFields(schemaToFormProperties(this.schemas, family, modelType))
      : [];
  }

  /**
   * Drops the fields the form must not own: identity and the type discriminator, the locator
   * (its own form), and the link to the parent data source (this panel's scope, chosen above).
   */
  private withoutStructuralFields(properties: FormProperty[]): FormProperty[] {
    const structural = [...GATEWAY_IDENTITY_FIELDS, 'pointLocator', 'dataSourceId',
      'dataSourceXid', 'dataSourceName', 'extendedName'];
    return properties.filter(property => !structural.includes(property.id));
  }

  private render(value: any): string {
    return value === undefined || value === null ? '—' : String(value);
  }

}
