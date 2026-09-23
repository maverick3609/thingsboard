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
import { GATEWAY_IDENTITY_FIELDS, GatewayListQuery,
  GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { GatewayEventHandler, gatewayHandlerRunsCommands, gatewayHandlerTypes, GatewayTypeOption,
  HANDLER_STRUCTURAL_FIELDS } from '@shared/models/inferrix-gateway-event.models';
import { GatewaySchemaDocument,
  schemaToFormProperties } from '@shared/models/inferrix-gateway-schema.models';
import { FormProperty } from '@shared/models/dynamic-form.models';

/**
 * Event handlers — what happens when a detector trips.
 *
 * Four types, closed and core-owned: EMAIL, SMS, SET_POINT and PROCESS. Unlike data sources, no
 * protocol module can add one, so the set cannot grow behind this screen's back.
 *
 * Three of the four are offered. A PROCESS handler's configuration *is* a command line the gateway
 * executes, so creating one from here is remote code execution on the gateway host — see
 * {@link gatewayHandlerRunsCommands}. Existing ones are listed and can be deleted, and open
 * read-only.
 *
 * Recipient lists are edited by {@link GatewayRecipientsComponent}, not by the schema form. The
 * gateway declares `RecipientEntryModel` as a bare discriminator and ships none of its subtypes,
 * so a schema-built form would render the type and drop the address — and the array renderer
 * writes its value back over the whole list, which would erase every recipient on save.
 */
@Component({
  selector: 'tb-gateway-event-handlers',
  templateUrl: './gateway-event-handlers.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayEventHandlersComponent extends GatewayListPanelComponent<GatewayEventHandler> {

  readonly displayedColumns = ['name', 'handlerType', 'xid', 'disabled', 'actions'];

  /** The handler fields that hold recipients. Named by the stack's own models. */
  private static readonly RECIPIENT_FIELDS =
    ['activeRecipients', 'escalationRecipients', 'inactiveRecipients'];

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
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayEventHandler>> {
    return this.gatewayService.getEventHandlers(this.deviceId, query, {ignoreLoading: true});
  }

  get addableTypes(): GatewayTypeOption[] {
    return gatewayHandlerTypes(this.schemas);
  }

  add(type: GatewayTypeOption): void {
    this.open({handlerType: type.type},
      this.translate.instant('inferrix.gateway.add-event-handler'));
  }

  edit(row: GatewayEventHandler): void {
    this.gatewayService.getEventHandler(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.open(full, full.name),
      error: error => this.fail(error)
    });
  }

  delete(row: GatewayEventHandler): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-event-handler-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-event-handler-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteEventHandler(this.deviceId, row.xid, {ignoreLoading: true})
          .subscribe({
            next: () => this.reload(),
            error: error => this.fail(error)
          });
      }
    });
  }

  private open(model: GatewayEventHandler, title: string): void {
    // Read-only for a reason the operator is told, rather than a Save button that appears to work.
    const runsCommands = gatewayHandlerRunsCommands(model.handlerType);
    const all = schemaToFormProperties(this.schemas, 'eventHandler', model.handlerType);
    const recipientFields = GatewayEventHandlersComponent.RECIPIENT_FIELDS
      .filter(field => all.some(property => property.id === field));
    const structural = [...GATEWAY_IDENTITY_FIELDS, ...HANDLER_STRUCTURAL_FIELDS, ...recipientFields];
    const properties: FormProperty[] = all.filter(property => !structural.includes(property.id));

    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayEventHandler>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, model, properties, recipientFields,
          readonly: this.readonly || runsCommands,
          readonlyNote: runsCommands
            ? this.translate.instant('inferrix.gateway.handler-runs-commands') : undefined}
      }).afterClosed().subscribe(saved => {
      if (saved) {
        this.gatewayService.saveEventHandler(this.deviceId, saved, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }
}
