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

import { Component, Inject, SkipSelf } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormArray, FormBuilder, FormGroup, FormGroupDirective, NgForm, Validators } from '@angular/forms';
import { ErrorStateMatcher } from '@angular/material/core';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { Role, RolePermissions, RoleType } from '@shared/models/role.models';
import { RoleService } from '@core/http/role.service';
import { humanizePermissionName } from '@home/pages/role/role.utils';

export interface RoleDialogData {
  role: Role | null;
  isAdd: boolean;
  // server vocabularies (GET /api/permissions/allowedPermissions), fetched by the caller
  allowedResources: string[];
  allowedOperations: string[];
}

@Component({
  selector: 'tb-role-dialog',
  templateUrl: './role-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: RoleDialogComponent}],
  standalone: false
})
export class RoleDialogComponent extends DialogComponent<RoleDialogComponent, Role> implements ErrorStateMatcher {

  roleFormGroup: FormGroup;
  isAdd: boolean;
  allowedResources: string[];
  allowedOperations: string[];
  submitted = false;

  humanize = humanizePermissionName;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: RoleDialogData,
              public dialogRef: MatDialogRef<RoleDialogComponent, Role>,
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              private fb: FormBuilder,
              private roleService: RoleService) {
    super(store, router, dialogRef);
    this.isAdd = data.isAdd;
    this.allowedResources = data.allowedResources;
    this.allowedOperations = data.allowedOperations;

    const role = data.role;
    this.roleFormGroup = this.fb.group({
      name: [role ? role.name : '', [Validators.required, Validators.maxLength(255)]],
      description: [role?.additionalInfo?.description ?? ''],
      // a role with no permissions denies everything it is assigned to, so require at least one row
      permissions: this.fb.array(this.toPermissionRows(role?.permissions), [Validators.required, Validators.minLength(1)])
    });
  }

  get permissionsFormArray(): FormArray {
    return this.roleFormGroup.get('permissions') as FormArray;
  }

  private toPermissionRows(permissions: RolePermissions | undefined): FormGroup[] {
    if (!permissions) {
      return [];
    }
    return Object.keys(permissions).map(resource => this.buildRow(resource, permissions[resource]));
  }

  private buildRow(resource: string, operations: string[]): FormGroup {
    return this.fb.group({
      resource: [resource, [Validators.required]],
      operations: [operations, [Validators.required]]
    });
  }

  addPermissionRow(): void {
    this.permissionsFormArray.push(this.buildRow(null, []));
  }

  removePermissionRow(index: number): void {
    this.permissionsFormArray.removeAt(index);
  }

  isErrorState(control: any, form: FormGroupDirective | NgForm | null): boolean {
    const originalErrorState = this.errorStateMatcher.isErrorState(control, form);
    const customErrorState = !!(control && control.invalid && this.submitted);
    return originalErrorState || customErrorState;
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  save(): void {
    this.submitted = true;
    if (this.roleFormGroup.invalid) {
      return;
    }
    const formValue = this.roleFormGroup.value;
    // rows with the same resource union their operations
    const permissions: RolePermissions = {};
    for (const row of formValue.permissions) {
      const existing = permissions[row.resource] ?? [];
      permissions[row.resource] = Array.from(new Set([...existing, ...row.operations]));
    }
    const role: Role = {
      ...(this.data.role ?? {} as Role),
      name: formValue.name,
      type: RoleType.GENERIC,
      permissions,
      additionalInfo: {
        ...(this.data.role?.additionalInfo ?? {}),
        description: formValue.description
      }
    };
    this.roleService.saveRole(role).subscribe(savedRole => this.dialogRef.close(savedRole));
  }

}
