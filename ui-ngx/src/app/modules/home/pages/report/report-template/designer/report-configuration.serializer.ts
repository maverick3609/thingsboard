// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
// The ONLY funnel between the designer's in-memory tree (ReportTemplateConfigModel) and the
// backend jsonb wire shape (common/data/.../report/configuration/**); T3/T7 load/save/preview
// exclusively through these two functions. The TS interfaces already mirror the Java model 1:1
// (see report-configuration.models.ts), so both directions reduce to an identity-preserving deep
// clone: JSON.parse(JSON.stringify(...)) drops `undefined`-valued fields (the only editor-only
// bookkeeping this model can carry today) while preserving key order, explicit `null`s, and
// component subtrees the designer doesn't edit yet (ENTITY_TABLE/ALARM_TABLE/TIME_SERIES_TABLE/
// SUB_REPORT/DASHBOARD) verbatim.

import { ReportComponent, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';

export function serialize(model: ReportTemplateConfigModel): any {
  assertPresent(model.format, 'format');
  model.components.forEach((component: ReportComponent) => assertPresent(component.type, 'type'));
  return JSON.parse(JSON.stringify(model));
}

export function deserialize(json: any): ReportTemplateConfigModel {
  const model = JSON.parse(JSON.stringify(json));
  if (!Array.isArray(model.components)) {
    model.components = [];
  }
  return model as ReportTemplateConfigModel;
}

function assertPresent(value: unknown, field: string): void {
  if (value === undefined || value === null) {
    throw new Error(`report-configuration.serializer: missing "${field}" discriminator`);
  }
}
