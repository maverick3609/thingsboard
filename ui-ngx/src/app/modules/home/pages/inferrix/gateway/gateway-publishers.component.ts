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
import { GATEWAY_IDENTITY_FIELDS, GATEWAY_PUBLISHER_HIDDEN_FIELDS, GATEWAY_REPORTED_FIELDS,
  GatewayListQuery, GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { PUBLISHED_POINT_PROPERTIES, PUBLISHED_POINT_STRUCTURAL_FIELDS, GatewayPublishedPoint,
  GatewayPublisher, GatewayPublisherType } from '@shared/models/inferrix-gateway-publisher.models';
import { componentToFormProperties, GatewaySchemaDocument,
  schemaToFormProperties } from '@shared/models/inferrix-gateway-schema.models';
import { FormProperty } from '@shared/models/dynamic-form.models';

/**
 * The gateway's publishers, each opened with its published points inline.
 *
 * There is deliberately no published-points tab. A published point exists only as one publisher's
 * view of one data point — it carries the publisher's xid and the data point's xid and nothing
 * else of its own — so a flat list of them is a list the operator has to filter back down to one
 * publisher before it says anything. The gateway's own webapp makes the same call, and its
 * `/v2/publisher` read is what makes it cheap here: the points come back inside the publisher, so
 * opening one costs a single request.
 */
@Component({
  selector: 'tb-gateway-publishers',
  templateUrl: './gateway-publishers.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayPublishersComponent extends GatewayListPanelComponent<GatewayPublisher> {

  readonly displayedColumns = ['name', 'modelType', 'xid', 'points', 'enabled', 'actions'];

  private schemas: GatewaySchemaDocument;
  private types: GatewayPublisherType[] = [];

  constructor(private gatewayService: InferrixGatewayService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  protected load(): void {
    // Neither read blocks the list: the schema is a second call to the same edge box, and a
    // gateway that refuses either should still show the publishers it has.
    this.gatewayService.getSchemas(this.deviceId, {ignoreLoading: true}).subscribe({
      next: schemas => {
        this.schemas = schemas;
        this.cd.markForCheck();
      },
      error: () => {}
    });
    this.gatewayService.getPublisherTypes(this.deviceId, {ignoreLoading: true}).subscribe({
      next: page => {
        this.types = page?.items ?? [];
        this.cd.markForCheck();
      },
      error: () => {}
    });
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayPublisher>> {
    return this.gatewayService.getPublishers(this.deviceId, query, {ignoreLoading: true});
  }

  get addableTypes(): GatewayPublisherType[] {
    return this.types;
  }

  /** `type.type` is already the full discriminator, as it is for data source types. */
  add(type: GatewayPublisherType): void {
    this.open({modelType: type.type, enabled: false, points: []},
      this.translate.instant('inferrix.gateway.add-publisher'));
  }

  edit(row: GatewayPublisher): void {
    // Re-read rather than open the list's copy: the points are what the operator came here to
    // edit, and a row paged minutes ago carries a point list that may already be stale.
    this.gatewayService.getPublisher(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.open(full, full.name),
      error: error => this.fail(error)
    });
  }

  toggleEnabled(row: GatewayPublisher, enabled: boolean): void {
    this.gatewayService.setPublisherEnabled(this.deviceId, row.xid, enabled, {ignoreLoading: true})
      .subscribe({
        next: () => this.reload(),
        error: error => {
          this.fail(error);
          this.reload();
        }
      });
  }

  delete(row: GatewayPublisher): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-publisher-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-publisher-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deletePublisher(this.deviceId, row.xid, {ignoreLoading: true})
          .subscribe({
            next: () => this.reload(),
            error: error => this.fail(error)
          });
      }
    });
  }

  private open(model: GatewayPublisher, title: string): void {
    // The dialog renders this array and the point handlers below mutate it in place, so an edit
    // shows up in the table without closing and reopening the publisher.
    const points: GatewayPublishedPoint[] = [...(model.points ?? [])];
    const children: GatewayModelChildren = {
      title: this.translate.instant('inferrix.gateway.published-points'),
      rows: points,
      detail: (row: GatewayPublishedPoint) => row.dataPointXid ?? '—',
      detailHeader: this.translate.instant('inferrix.gateway.data-point'),
      readonly: this.readonly,
      // A published point carries its publisher's xid, so there is nothing to attach one to until
      // the publisher has been saved once and the gateway has given it one.
      needsSaveFirst: !model.xid,
      add: () => this.editPoint(model, {
        modelType: this.pointType(model.modelType), publisherXid: model.xid, enabled: false
      }, points),
      edit: row => this.editPoint(model, row, points),
      delete: row => this.deletePoint(row, points),
      toggle: (row, enabled) => this.togglePoint(row, enabled, points)
    };
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayPublisher>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, model, properties: this.propertiesFor(model.modelType),
          readonly: this.readonly, children}
      }).afterClosed().subscribe(saved => {
      if (saved) {
        // `points` is dropped rather than sent back. The dialog spreads the model it was opened
        // with, so the array in hand is the one read *before* the operator edited any of it --
        // and every one of those edits has already been written through /v2/published-points. A
        // publisher save that carried the stale copy could only undo them.
        const {points: alreadyWritten, ...publisher} = saved;
        this.gatewayService.savePublisher(this.deviceId, publisher, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      } else {
        // Points were written while the form was open, so even a cancelled publisher edit can
        // leave the list's point count wrong.
        this.reload();
      }
    });
  }

  private editPoint(publisher: GatewayPublisher, point: GatewayPublishedPoint,
                    rows: GatewayPublishedPoint[]): void {
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayPublishedPoint>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          title: point.xid
            ? point.name
            : this.translate.instant('inferrix.gateway.add-published-point'),
          model: point,
          properties: this.pointProperties(point.modelType),
          readonly: this.readonly
        }
      }).afterClosed().subscribe(saved => {
      if (!saved) {
        return;
      }
      // The publisher link is set here rather than left to the form: it is what places the point,
      // and an add reaches the form before the operator has chosen anything.
      saved.publisherXid = publisher.xid;
      this.gatewayService.savePublishedPoint(this.deviceId, saved, {ignoreLoading: true}).subscribe({
        next: stored => {
          this.replace(rows, point, stored ?? saved);
          this.cd.markForCheck();
        },
        error: error => this.fail(error)
      });
    });
  }

  private deletePoint(point: GatewayPublishedPoint, rows: GatewayPublishedPoint[]): void {
    // See `delete`: the gateway-supplied name belongs in the interpolated title, never the
    // innerHTML-rendered message.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-published-point-title', {name: point.name}),
      this.translate.instant('inferrix.gateway.delete-published-point-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deletePublishedPoint(this.deviceId, point.xid, {ignoreLoading: true})
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

  private togglePoint(point: GatewayPublishedPoint, enabled: boolean,
                      rows: GatewayPublishedPoint[]): void {
    this.gatewayService.setPublishedPointEnabled(this.deviceId, point.xid, enabled,
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

  /** Replaces an edited row in place, or appends a newly created one. */
  private replace(rows: GatewayPublishedPoint[], original: GatewayPublishedPoint,
                  stored: GatewayPublishedPoint): void {
    const at = rows.indexOf(original);
    if (at >= 0) {
      rows[at] = stored;
    } else {
      rows.push(stored);
    }
  }

  /**
   * The point type a publisher's points take.
   *
   * The two discriminators differ only in their suffix — an `INTEGRATION_MQTT_SENDER.PUB` holds
   * `INTEGRATION_MQTT_SENDER.POINT` — which is how the gateway names every pair, and is the only
   * pairing it publishes anywhere.
   */
  private pointType(publisherType: string): string {
    return publisherType ? publisherType.replace(/\.PUB$/, '.POINT') : '';
  }

  /**
   * A published point's fields.
   *
   * Curated rather than mapped: see {@link PUBLISHED_POINT_PROPERTIES} for why the schema cannot
   * drive this one form. A type that is not in that table falls back to the schema, which gives
   * the generic fields and nothing type-specific — fewer inputs than the point really has, but
   * never an input that does not belong to it.
   */
  private pointProperties(modelType: string): FormProperty[] {
    const curated = PUBLISHED_POINT_PROPERTIES[modelType];
    if (curated) {
      return curated;
    }
    return componentToFormProperties(this.schemas, 'AbstractPublishedPointModelPublishedPointVO')
      .filter(property => !PUBLISHED_POINT_STRUCTURAL_FIELDS.includes(property.id));
  }

  /**
   * The form fields for one publisher type.
   *
   * Identity and the discriminator go, as everywhere; `points` goes because it is the table below
   * the form rather than a field in it; and a handful of per-type fields go because the gateway's
   * own webapp does not offer them for that type — see {@link GATEWAY_PUBLISHER_HIDDEN_FIELDS}.
   */
  private propertiesFor(modelType: string): FormProperty[] {
    const hidden = [...GATEWAY_IDENTITY_FIELDS, ...GATEWAY_REPORTED_FIELDS, 'points',
      ...(GATEWAY_PUBLISHER_HIDDEN_FIELDS[modelType] ?? [])];
    return schemaToFormProperties(this.schemas, 'publisher', modelType)
      .filter(property => !hidden.includes(property.id));
  }
}
