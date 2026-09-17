// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { EntityId } from '@shared/models/id/entity-id';
import { EntityType } from '@shared/models/entity-type.models';

export class SchedulerEventId implements EntityId {
  entityType = EntityType.SCHEDULER_EVENT;
  id: string;
  constructor(id: string) {
    this.id = id;
  }
}
