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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { AppState } from '@core/core.state';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { EntityType } from '@shared/models/entity-type.models';
import { DiscoveredController } from '@shared/models/inferrix-controller.models';
import { AdoptControllerDialogComponent } from './adopt-controller-dialog.component';

/**
 * Controllers that have announced themselves and belong to nobody yet.
 *
 * This is the only way to see a controller that has never been adopted: over MQTT a device must
 * already hold platform credentials to connect at all, so MQTT can only ever show controllers that
 * were adopted previously. The firmware's plain-TCP announce exists precisely to cover the gap.
 *
 * A system administrator sees every announce on the network. A tenant administrator sees only what
 * a system administrator has assigned to them — an unadopted controller has no tenant of its own,
 * and showing the raw list to everyone would hand each tenant the addresses, deployment names and
 * locations of every other tenant's hardware on the same network.
 */
@Component({
  selector: 'tb-discovered-controllers',
  templateUrl: './discovered-controllers.component.html',
  styleUrls: ['./controllers.component.scss'],
  standalone: false
})
export class DiscoveredControllersComponent implements OnInit, OnDestroy {

  discovered: DiscoveredController[] = [];
  loading = true;
  isSysAdmin = false;

  /** Tenant picked in each row, keyed by uid; only a system administrator sees these. */
  assignTo: {[uid: string]: string} = {};
  assigning: {[uid: string]: boolean} = {};

  readonly displayedColumns = ['uid', 'ip', 'identity', 'lastSeen', 'actions'];
  readonly entityType = EntityType;

  private destroy$ = new Subject<void>();

  constructor(private controllerService: InferrixControllerService,
              private dialog: MatDialog,
              private router: Router,
              private store: Store<AppState>) {}

  ngOnInit(): void {
    this.isSysAdmin = getCurrentAuthUser(this.store).authority === Authority.SYS_ADMIN;
    this.reload();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  reload(): void {
    this.loading = true;
    this.controllerService.getDiscoveredControllers({ignoreErrors: true}).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: discovered => {
        this.discovered = discovered;
        this.loading = false;
      },
      error: () => {
        this.discovered = [];
        this.loading = false;
      }
    });
  }

  identityLabel(controller: DiscoveredController): string {
    const identity = controller.identity || {};
    return [identity.name, identity.location, identity.model].filter(v => !!v).join(' · ') || '—';
  }

  /**
   * Allocates an unadopted controller to a tenant. System administrator only, and the reason it is
   * a separate step rather than part of adoption is that ThingsBoard forbids a system administrator
   * from creating a device under a tenant — so the tenant's own administrator has to do the adopting.
   */
  assign(controller: DiscoveredController): void {
    const tenantId = this.assignTo[controller.uid];
    if (!tenantId) {
      return;
    }
    this.assigning[controller.uid] = true;
    this.controllerService.assignController(controller.uid, tenantId).subscribe({
      next: updated => {
        Object.assign(controller, updated);
        this.assigning[controller.uid] = false;
      },
      error: () => this.assigning[controller.uid] = false
    });
  }

  adopt(controller: DiscoveredController): void {
    this.dialog.open(AdoptControllerDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {controller}
    }).afterClosed().subscribe(device => {
      if (device) {
        this.router.navigateByUrl(`/controllers/${device.id.id}`);
      }
    });
  }

  adoptByAddress(): void {
    this.dialog.open(AdoptControllerDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {}
    }).afterClosed().subscribe(device => {
      if (device) {
        this.router.navigateByUrl(`/controllers/${device.id.id}`);
      }
    });
  }
}
