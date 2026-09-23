// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityTabsComponent } from '@home/components/entity/entity-tabs.component';
import { GatewayInfo } from '@shared/models/inferrix-gateway.models';

/**
 * Everything about a gateway that is not the device record.
 *
 * One tab today. The data sources, events and scripts tabs land in later phases and will follow the
 * same rule the controller tabs do: take `active` and touch the device only once opened, because
 * every panel here is a real call across a LAN to a small edge box.
 *
 * A single throwing tab template blanks the whole strip — the details page renders them together —
 * so a panel that cannot render must render nothing rather than throw.
 */
@Component({
  selector: 'tb-inferrix-gateway-tabs',
  templateUrl: './gateway-tabs.component.html',
  styleUrls: [],
  standalone: false
})
export class GatewayTabsComponent extends EntityTabsComponent<GatewayInfo> {

  constructor(protected store: Store<AppState>) {
    super(store);
  }

  /** Writes go through the platform proxy as a tenant admin; a customer user may only read. */
  get readonly(): boolean {
    return !!this.entitiesTableConfig?.componentsData?.readonly;
  }
}
