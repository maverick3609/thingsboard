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
import { GATEWAY_IDENTITY_FIELDS, GatewayDataSource, GatewayListQuery,
  GatewayDataSourceType, GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { GatewaySchemaDocument,
  schemaToFormProperties } from '@shared/models/inferrix-gateway-schema.models';
import { FormProperty } from '@shared/models/dynamic-form.models';

/**
 * The gateway's data sources — the protocol connections it polls.
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
    this.open({modelType: type.type, enabled: false},
      this.translate.instant('inferrix.gateway.add-data-source'));
  }

  edit(row: GatewayDataSource): void {
    // The row from a list is the whole model on this stack, but re-reading it is what makes an
    // edit safe against a list that was paged minutes ago -- and it is one call, not a page.
    this.gatewayService.getDataSource(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.open(full, full.name),
      error: error => this.fail(error)
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

  private open(model: GatewayDataSource, title: string): void {
    const properties = this.propertiesFor(model.modelType);
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayDataSource>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, model, properties, readonly: this.readonly}
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
   * The form fields for one data source type.
   *
   * Identity and the type discriminator are dropped: `id` is a surrogate key, `xid` and `name` are
   * edited as the row's identity, and an input that changed `modelType` would ask the form to
   * become a different form while it was being filled in.
   */
  private propertiesFor(modelType: string): FormProperty[] {
    return schemaToFormProperties(this.schemas, 'dataSource', modelType)
      .filter(property => !GATEWAY_IDENTITY_FIELDS.includes(property.id));
  }
}
