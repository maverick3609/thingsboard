// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { EntityId } from '@shared/models/id/entity-id';
import { EntityType } from '@shared/models/entity-type.models';

export class ReportId implements EntityId {
  entityType = EntityType.REPORT;
  id: string;
  constructor(id: string) {
    this.id = id;
  }
}
