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

import { Component, Inject, OnInit } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormBuilder, FormGroup } from '@angular/forms';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { forkJoin } from 'rxjs';
import { PageLink } from '@shared/models/page/page-link';
import { Direction } from '@shared/models/page/sort-order';
import { User } from '@shared/models/user.model';
import { Role } from '@shared/models/role.models';
import { RoleService } from '@core/http/role.service';

export interface ManageUserRolesDialogData {
  user: User;
}

// Replace-set editor for the user_role N:M assignment (RBAC Option B). An empty selection
// removes all roles and restores the user's legacy authority-based access.
// standalone: opened from the users table (a different lazy module), so it must not depend on
// RolesModule having been loaded
@Component({
  selector: 'tb-manage-user-roles-dialog',
  templateUrl: './manage-user-roles-dialog.component.html',
  standalone: true,
  imports: [CommonModule, SharedModule]
})
export class ManageUserRolesDialogComponent extends DialogComponent<ManageUserRolesDialogComponent, boolean> implements OnInit {

  rolesFormGroup: FormGroup;
  allRoles: Role[] = [];
  loaded = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ManageUserRolesDialogData,
              public dialogRef: MatDialogRef<ManageUserRolesDialogComponent, boolean>,
              private fb: FormBuilder,
              private roleService: RoleService) {
    super(store, router, dialogRef);
    this.rolesFormGroup = this.fb.group({
      roleIds: [[]]
    });
  }

  ngOnInit(): void {
    const allRolesPageLink = new PageLink(1024, 0, null, {property: 'name', direction: Direction.ASC});
    forkJoin({
      allRoles: this.roleService.getRoles(allRolesPageLink),
      userRoles: this.roleService.getUserRoles(this.data.user.id.id)
    }).subscribe(({allRoles, userRoles}) => {
      this.allRoles = allRoles.data;
      this.rolesFormGroup.get('roleIds').setValue(userRoles.map(role => role.id.id));
      this.loaded = true;
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  save(): void {
    const roleIds: string[] = this.rolesFormGroup.get('roleIds').value;
    this.roleService.updateUserRoles(this.data.user.id.id, roleIds)
      .subscribe(() => this.dialogRef.close(true));
  }

}
