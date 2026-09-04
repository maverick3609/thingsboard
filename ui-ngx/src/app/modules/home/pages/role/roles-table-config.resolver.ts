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

import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { DatePipe } from '@angular/common';
import { TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';
import { map, mergeMap } from 'rxjs/operators';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { Direction } from '@shared/models/page/sort-order';
import { Role } from '@shared/models/role.models';
import { RoleService } from '@core/http/role.service';
import { RoleDialogComponent, RoleDialogData } from '@home/pages/role/role-dialog.component';
import { humanizePermissionName } from '@home/pages/role/role.utils';

// Dialog-CRUD table (ai-model-table-config.resolve.ts shape): roles are small enough that a
// dialog beats the details drawer, and it spares the EntityComponent/tabs scaffolding.
@Injectable()
export class RolesTableConfigResolver {

  private readonly config: EntityTableConfig<Role> = new EntityTableConfig<Role>();

  constructor(private roleService: RoleService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog) {

    this.config.entityType = EntityType.ROLE;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.ROLE);
    this.config.entityResources = entityTypeResources.get(EntityType.ROLE);
    this.config.tableTitle = this.translate.instant('role.roles');
    this.config.selectionEnabled = true;
    this.config.detailsPanelEnabled = false;
    this.config.rowPointer = true;
    this.config.entitiesDeleteEnabled = true;
    this.config.entityTitle = (role) => role ? role.name : '';
    this.config.addDialogStyle = {width: '720px', maxHeight: '100vh'};
    this.config.defaultSortOrder = {property: 'createdTime', direction: Direction.DESC};

    this.config.columns.push(
      new DateEntityTableColumn<Role>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<Role>('name', 'role.name', '30%'),
      new EntityTableColumn<Role>('description', 'role.description', '40%',
        entity => entity.additionalInfo?.description ?? '', () => ({}), false),
      new EntityTableColumn<Role>('permissions', 'role.permissions', '30%',
        entity => this.permissionsSummary(entity), () => ({}), false)
    );

    this.config.deleteEntityTitle = role => this.translate.instant('role.delete-role-title', {roleName: role.name});
    this.config.deleteEntityContent = () => this.translate.instant('role.delete-role-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('role.delete-roles-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('role.delete-roles-text');

    this.config.entitiesFetchFunction = pageLink => this.roleService.getRoles(pageLink);
    this.config.deleteEntity = id => this.roleService.deleteRole(id.id);
    this.config.addEntity = () => this.openRoleDialog(null, true);

    this.config.handleRowClick = ($event, role) => {
      $event?.stopPropagation();
      this.openRoleDialog(role, false).subscribe(res => res ? this.config.updateData() : null);
      return true;
    };
  }

  resolve(_route: ActivatedRouteSnapshot): EntityTableConfig<Role> {
    return this.config;
  }

  private permissionsSummary(role: Role): string {
    const resources = Object.keys(role.permissions ?? {});
    if (!resources.length) {
      return this.translate.instant('role.no-permissions');
    }
    const summary = resources.slice(0, 3).map(humanizePermissionName).join(', ');
    return resources.length > 3
      ? this.translate.instant('role.permissions-summary-more', {summary, count: resources.length - 3})
      : summary;
  }

  private openRoleDialog(role: Role | null, isAdd: boolean): Observable<Role> {
    // vocabularies come from the server so the editor never drifts from backend enums
    return this.roleService.getAllowedPermissions().pipe(
      mergeMap(allowedPermissions => this.dialog.open<RoleDialogComponent, RoleDialogData, Role>(RoleDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          role,
          isAdd,
          allowedResources: allowedPermissions.allowedResources,
          allowedOperations: allowedPermissions.allowedOperations
        }
      }).afterClosed())
    );
  }

}
