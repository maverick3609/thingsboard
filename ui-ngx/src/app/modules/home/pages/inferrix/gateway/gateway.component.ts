// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, Inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { AppState } from '@core/core.state';
import { EntityComponent } from '@home/components/entity/entity.component';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { EntityType } from '@shared/models/entity-type.models';

/**
 * The Details tab of an adopted gateway.
 *
 * Only the plain device record is editable here — name, label, description. Everything about the
 * gateway itself is a live call and lives on the Health tab, deliberately: a gateway sits on a LAN
 * or a VPN, so "what is it" and "can we reach it" are the same question, and answering it belongs
 * where the operator went looking for it rather than behind a form they came to rename something
 * in.
 *
 * Note what is *not* here: the management address, the certificate fingerprint and the API token.
 * Those are server-scope attributes the platform owns, and editing them from a form would let
 * someone repoint an adopted gateway without the certificate check adoption performs.
 */
@Component({
  selector: 'tb-inferrix-gateway',
  templateUrl: './gateway.component.html',
  styleUrls: [],
  standalone: false
})
export class GatewayComponent extends EntityComponent<any> {

  entityType = EntityType;

  constructor(protected store: Store<AppState>,
              @Inject('entity') protected entityValue: any,
              @Inject('entitiesTableConfig') protected entitiesTableConfigValue: EntityTableConfig<any>,
              public fb: UntypedFormBuilder,
              protected cd: ChangeDetectorRef) {
    super(store, fb, entityValue, entitiesTableConfigValue, cd);
  }

  buildForm(entity: any): UntypedFormGroup {
    return this.fb.group({
      name: [entity ? entity.name : '', [Validators.required, Validators.maxLength(255)]],
      label: [entity ? entity.label : '', [Validators.maxLength(255)]],
      additionalInfo: this.fb.group({
        description: [entity && entity.additionalInfo ? entity.additionalInfo.description : '']
      })
    });
  }

  updateForm(entity: any) {
    this.entityForm.patchValue({
      name: entity.name,
      label: entity.label,
      additionalInfo: {description: entity.additionalInfo ? entity.additionalInfo.description : ''}
    });
  }
}
