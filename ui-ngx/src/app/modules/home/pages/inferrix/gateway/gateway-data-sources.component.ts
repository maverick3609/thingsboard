// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayModelChildren, GatewayModelDialogComponent,
  GatewayModelDialogData } from '@home/pages/inferrix/gateway/gateway-model-dialog.component';
import { GatewayDetectorsDialogComponent, GatewayDetectorsDialogData }
  from '@home/pages/inferrix/gateway/gateway-detectors-dialog.component';
import { GATEWAY_DATA_SOURCE_HIDDEN_FIELDS, GATEWAY_IDENTITY_FIELDS, GATEWAY_REPORTED_FIELDS,
  GatewayDataPoint,
  GatewayDataSource, GatewayListQuery, GatewayDataSourceType,
  GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { componentToFormProperties, GatewaySchemaDocument,
  schemaToFormProperties } from '@shared/models/inferrix-gateway-schema.models';
import { gatewayFormLayout } from '@shared/models/inferrix-gateway-layout.models';
import { FormProperty } from '@shared/models/dynamic-form.models';

/**
 * How many of a data source's points one open reads.
 *
 * Deliberately a single page rather than a paged table inside a dialog: the points of one source
 * are a handful on every real gateway -- the largest here has 22 -- and a source that somehow had
 * more would still show its first hundred by name rather than nothing.
 */
const POINTS_PER_SOURCE = 100;

/**
 * The gateway's data sources — the protocol connections it polls — each opened with its own data
 * points inline.
 *
 * There is deliberately no data points tab. A point belongs to exactly one data source and is
 * configured against that source's protocol: its locator is a Modbus register or a BACnet object,
 * and neither means anything without the connection that reads it. A flat list of points is one
 * the operator has to narrow back down to a single source before it says anything, which is what
 * the old tab made them do on arrival. The gateway's own webapp puts the points inside the source
 * for the same reason.
 *
 * Paged on the device; see {@link GatewayListPanelComponent} for why that matters and how the
 * paging reaches a gateway that has no paging parameters.
 */
@Component({
  selector: 'tb-gateway-data-sources',
  templateUrl: './gateway-data-sources.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayDataSourcesComponent extends GatewayListPanelComponent<GatewayDataSource> {

  readonly displayedColumns = ['name', 'modelType', 'xid', 'enabled', 'actions'];

  /** Loaded once per gateway: it changes only when the gateway's build does. */
  private schemas: GatewaySchemaDocument;
  private types: GatewayDataSourceType[] = [];

  /** Latest value per point xid, filled only for the rows an operator asked about. */
  private values: {[xid: string]: string} = {};

  constructor(private gatewayService: InferrixGatewayService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  protected load(): void {
    // Both are needed before a row can be opened, and neither is worth blocking the list on: the
    // schema read is a second call to the same edge box, and a gateway that refuses it (an
    // under-privileged token) should still show what it has.
    this.gatewayService.getSchemas(this.deviceId, {ignoreLoading: true}).subscribe({
      next: schemas => {
        this.schemas = schemas;
        this.cd.markForCheck();
      },
      error: () => {}
    });
    this.gatewayService.getDataSourceTypes(this.deviceId, {ignoreLoading: true}).subscribe({
      next: page => {
        this.types = page?.items ?? [];
        this.cd.markForCheck();
      },
      error: () => {}
    });
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayDataSource>> {
    return this.gatewayService.getDataSources(this.deviceId, query, {ignoreLoading: true});
  }

  get addableTypes(): GatewayDataSourceType[] {
    return this.types;
  }

  /**
   * `type.type` is already the full discriminator.
   *
   * `/v2/data-source-types` answers `{"type":"MODBUS_IP.DS","name":"Modbus I/P"}` -- the suffix is
   * part of the value, not something the caller appends. Verified live against a running gateway,
   * where appending one produced `MODBUS_IP.DS.DS` and a Jackson subtype that resolves to nothing.
   */
  add(type: GatewayDataSourceType): void {
    // Seeded onto the model for the reason a point's locator is, below: a serial source whose
    // line settings the operator never opened must still post them, because the gateway converts
    // each with `Enum.valueOf` and answers an absent one with a null-pointer exception.
    this.open({...(gatewayFormLayout(type.type)?.defaults ?? {}),
      modelType: type.type, enabled: false},
      this.translate.instant('inferrix.gateway.add-data-source'), []);
  }

  edit(row: GatewayDataSource): void {
    // The row from a list is the whole model on this stack, but re-reading it is what makes an
    // edit safe against a list that was paged minutes ago -- and it is one call, not a page.
    this.gatewayService.getDataSource(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.openWithPoints(full, full.name),
      error: error => this.fail(error)
    });
  }

  /**
   * Reads this source's points, then opens the form around them.
   *
   * Scoped on the device -- the platform turns the chosen data source into an RQL `eq` term -- so
   * it costs one page rather than a filter over every point on the gateway. The page cap is the
   * one thing this gives up against the old tab: a source with more points than a page holds
   * shows the first page of them, which no real source on this gateway comes close to.
   *
   * A points read that fails still opens the form. The connection settings are worth editing on
   * their own, and an empty table with an error above it is more honest than no dialog at all.
   */
  private openWithPoints(model: GatewayDataSource, title: string): void {
    this.gatewayService.getDataPoints(this.deviceId,
      {pageSize: POINTS_PER_SOURCE, page: 0, sortProperty: 'name', sortOrder: 'ASC',
        filterField: 'dataSourceXid', filterValue: model.xid}, {ignoreLoading: true}).subscribe({
      next: page => this.open(model, title, page?.items ?? []),
      error: error => {
        this.fail(error);
        this.open(model, title, []);
      }
    });
  }

  toggleEnabled(row: GatewayDataSource, enabled: boolean): void {
    this.gatewayService.setDataSourceEnabled(this.deviceId, row.xid, enabled, false,
      {ignoreLoading: true}).subscribe({
      next: () => this.reload(),
      error: error => {
        this.fail(error);
        this.reload();
      }
    });
  }

  delete(row: GatewayDataSource): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-data-source-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-data-source-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteDataSource(this.deviceId, row.xid, {ignoreLoading: true})
          .subscribe({
            next: () => this.reload(),
            error: error => this.fail(error)
          });
      }
    });
  }

  private open(model: GatewayDataSource, title: string, points: GatewayDataPoint[]): void {
    // The dialog renders this array and the point handlers below mutate it in place, so an edit
    // shows up in the table without closing and reopening the data source.
    const rows = [...points];
    const children: GatewayModelChildren = {
      title: this.translate.instant('inferrix.gateway.data-points'),
      rows,
      detail: (row: GatewayDataPoint) => row.pointLocator?.dataType ?? row.xid ?? '\u2014',
      detailHeader: this.translate.instant('inferrix.gateway.point-data-type'),
      readonly: this.readonly,
      // A point carries its data source's xid, so there is nothing to attach one to until the
      // source has been saved once and the gateway has given it one.
      needsSaveFirst: !model.xid,
      provisioned: gatewayFormLayout(model.modelType)?.provisionedPoints,
      add: () => {
        // A new locator starts on what the gateway's own VO starts on, where its layout records
        // one. Seeded onto the model rather than defaulted in the form: a value the form shows but
        // never puts in the payload is a value the two ends can disagree about.
        const locator = this.locatorType(model, rows);
        this.editPoint(model, {
          dataSourceXid: model.xid, enabled: false,
          pointLocator: {...(gatewayFormLayout(locator)?.defaults ?? {}), modelType: locator}
        }, rows);
      },
      edit: row => this.editPoint(model, row, rows),
      delete: row => this.deletePoint(row, rows),
      toggle: (row, enabled) => this.togglePoint(row, enabled, rows),
      readValue: row => this.readValue(row),
      value: row => this.values[row.xid] ?? '\u2014',
      rowActions: [{
        icon: 'notification_important',
        tooltip: this.translate.instant('inferrix.gateway.detectors'),
        run: row => this.detectors(row)
      }]
    };
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayDataSource>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, model, properties: this.propertiesFor(model.modelType),
          layout: gatewayFormLayout(model.modelType),
          readonly: this.readonly, children}
      }).afterClosed().subscribe(saved => {
      if (saved) {
        this.gatewayService.saveDataSource(this.deviceId, saved, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  /**
   * One data point of this source, with its protocol-specific locator.
   *
   * The locator's type is fixed by the source, never chosen here: a point on a Modbus source is a
   * Modbus locator, and offering the others would only let an operator build a point the gateway
   * refuses.
   */
  private editPoint(source: GatewayDataSource, point: GatewayDataPoint,
                    rows: GatewayDataPoint[]): void {
    const locatorType = point.pointLocator?.modelType ?? this.locatorType(source, rows);
    const locatorProperties = locatorType
      ? this.withoutStructuralFields(
          schemaToFormProperties(this.schemas, 'pointLocator', locatorType))
      : [];
    const locatorLayout = locatorType ? gatewayFormLayout(locatorType) : undefined;
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayDataPoint>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          title: point.xid ? point.name : this.translate.instant('inferrix.gateway.add-data-point'),
          model: point,
          properties: this.pointProperties(),
          // Both layouts hang off the locator's type. A point's own fields are the same for every
          // protocol, so laying them out before its locator has been worked through would change
          // every point form in the product for the sake of one.
          layout: locatorLayout ? gatewayFormLayout('DataPointModel') : undefined,
          locatorProperties,
          locatorLayout,
          locatorType,
          deviceId: this.deviceId,
          locatorTitle: this.translate.instant('inferrix.gateway.point-locator'),
          locatorMissing: !locatorProperties.length,
          readonly: this.readonly
        }
      }).afterClosed().subscribe(saved => {
      if (!saved) {
        return;
      }
      // Set here rather than left to the form: the link is what places the point, and an add
      // reaches the form before the operator has chosen anything.
      saved.dataSourceXid = source.xid;
      this.gatewayService.saveDataPoint(this.deviceId, saved, {ignoreLoading: true}).subscribe({
        next: stored => {
          const at = rows.indexOf(point);
          if (at >= 0) {
            rows[at] = stored ?? saved;
          } else {
            rows.push(stored ?? saved);
          }
          this.cd.markForCheck();
        },
        error: error => this.fail(error)
      });
    });
  }

  private deletePoint(point: GatewayDataPoint, rows: GatewayDataPoint[]): void {
    // See `delete`: the gateway-supplied name belongs in the interpolated title, never the
    // innerHTML-rendered message.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-data-point-title', {name: point.name}),
      this.translate.instant('inferrix.gateway.delete-data-point-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteDataPoint(this.deviceId, point.xid, {ignoreLoading: true})
          .subscribe({
            next: () => {
              const at = rows.indexOf(point);
              if (at >= 0) {
                rows.splice(at, 1);
              }
              this.cd.markForCheck();
            },
            error: error => this.fail(error)
          });
      }
    });
  }

  private togglePoint(point: GatewayDataPoint, enabled: boolean, rows: GatewayDataPoint[]): void {
    this.gatewayService.setDataPointEnabled(this.deviceId, point.xid, enabled,
      {ignoreLoading: true}).subscribe({
      next: () => {
        point.enabled = enabled;
        this.cd.markForCheck();
      },
      error: error => {
        // The toggle already moved, so put it back: the gateway did not take the change.
        this.fail(error);
        point.enabled = !enabled;
        this.cd.markForCheck();
      }
    });
  }

  /**
   * The detectors watching one point.
   *
   * Opened from the point and not from a list of their own: a detector's `sourceId` is a point
   * xid, so a flat list would make the operator type one.
   */
  private detectors(point: GatewayDataPoint): void {
    this.dialog.open<GatewayDetectorsDialogComponent, GatewayDetectorsDialogData, boolean>(
      GatewayDetectorsDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {deviceId: this.deviceId, point, readonly: this.readonly}
      });
  }

  /**
   * One point's most recent value, on request.
   *
   * Read-only, and one row at a time. Writing a point value is a write to live building plant
   * through a proxy whose caller may hold nothing but tenant administration, and the platform's
   * route allowlist permits only GET here until that is decided on its own terms.
   */
  private readValue(point: GatewayDataPoint): void {
    this.gatewayService.getLatestPointValue(this.deviceId, point.xid, {ignoreLoading: true})
      .subscribe({
        next: values => {
          const value = values?.[0]?.value;
          this.values[point.xid] = value === undefined || value === null ? '\u2014' : String(value);
          this.cd.markForCheck();
        },
        error: () => {
          this.values[point.xid] = this.translate.instant('inferrix.gateway.value-unavailable');
          this.cd.markForCheck();
        }
      });
  }

  /**
   * The locator type a new point on this source takes.
   *
   * `/v2/data-source-types` publishes the pairing, but not for every type -- it is null for some
   * (`BACNET_MSTP.DS` on stack 5.1.0), and a gateway from before the field existed answers nothing
   * at all. In either case a sibling point of this same source still knows, for free, because the
   * points are already loaded. Only when there is neither is the locator form left empty.
   */
  private locatorType(source: GatewayDataSource, rows: GatewayDataPoint[]): string | null {
    const published = this.types.find(type => type.type === source.modelType)?.pointLocatorType;
    return published
      ?? rows.find(row => row.pointLocator?.modelType)?.pointLocator?.modelType
      ?? null;
  }

  /**
   * A point's own fields.
   *
   * `DataPointModel` is a shared component rather than a family entry, because there is exactly one
   * point model: every protocol difference lives in the nested locator, rendered as its own form.
   */
  private pointProperties(): FormProperty[] {
    return this.withoutStructuralFields(
      componentToFormProperties(this.schemas, 'DataPointModel'));
  }

  /**
   * Drops the fields the point form must not own: identity and the type discriminator, the locator
   * (its own form), and the link to the parent data source (the source this dialog opened from).
   */
  private withoutStructuralFields(properties: FormProperty[]): FormProperty[] {
    const structural = [...GATEWAY_IDENTITY_FIELDS, 'pointLocator', 'dataSourceId',
      'dataSourceXid', 'dataSourceName', 'extendedName'];
    return properties.filter(property => !structural.includes(property.id));
  }

  /**
   * The form fields for one data source type.
   *
   * Identity and the type discriminator are dropped: `id` is a surrogate key, `xid` and `name` are
   * edited as the row's identity, and an input that changed `modelType` would ask the form to
   * become a different form while it was being filled in. Four more go because the gateway's own
   * webapp does not offer them on a data source either -- see
   * {@link GATEWAY_DATA_SOURCE_HIDDEN_FIELDS}.
   */
  private propertiesFor(modelType: string): FormProperty[] {
    const hidden = [...GATEWAY_IDENTITY_FIELDS, ...GATEWAY_REPORTED_FIELDS,
      ...GATEWAY_DATA_SOURCE_HIDDEN_FIELDS];
    return schemaToFormProperties(this.schemas, 'dataSource', modelType)
      .filter(property => !hidden.includes(property.id));
  }
}
