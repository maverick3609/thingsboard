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

// Generic "Export widget data" data source (PE port, BUG 2). Turns a widget's already-subscribed data into a
// {columns, rows} table for widget-data-export.ts, WITHOUT touching the Timeseries/Entities/Alarms table
// widget components (they hold richer, private row/column state — sort/paging/search UI state, per-key cell
// content functions — that isn't reachable from the generic widget host by design; see the BUG 2 investigation
// report §"the hard part"). This reads only fields already public on WidgetContext / IWidgetSubscription:
//   - ctx.defaultSubscription.datasources / .data           (timeseries + latest — flattened current page;
//     WidgetSubscription#configureLoadedData flattens datasourcePages/dataPages into these on EVERY
//     subscription, paginated-entity-alias widgets included, so this is not timeseries/latest-only plumbing)
//   - ctx.defaultSubscription.alarmSource / .alarms          (alarm — same generic IWidgetSubscription fields
//     alarms-table-widget.component.ts itself reads; alarmSource.dataKeys already includes the built-in alarm
//     fields, not just user-added ones)
//
// Per-widget-type branching lives HERE (not in the widget components) because the three widget types expose
// structurally different generic state (timeseries/latest share ctx.data; alarm has its own alarms/alarmSource
// pair) — this is the "minimal per-widget-type adapter" the BUG 2 report anticipated, not a DOM scrape and not
// an edit to the widgets themselves.
//
// Caveats (documented, not fixed — v1 generic port):
// - Reflects the CURRENTLY LOADED page/window only (bounded by the widget's own pagination / time window), not
//   a fresh unbounded re-query of every matching entity/alarm.
// - The timeseries export is a flattened long-format table (Entity + Timestamp + one column per data key)
//   across ALL configured datasources together, rather than the widget's own possibly-tabbed per-source view
//   (TimeseriesTableWidgetComponent's private sources[]/sourceIndex tab state isn't reachable generically).
// - Cell values are the raw subscribed values (not each widget's custom per-key "cell content function" or
//   unit/decimals pipe) — consistent with a generic, data-driven export.

import { WidgetContext } from '@home/models/widget-component.models';
import { DataKey, Datasource, widgetType } from '@shared/models/widget.models';
import { AlarmData, dataKeyTypeToEntityKeyType } from '@shared/models/query/query.models';
import { formattedDataArrayFromDatasourceData, formattedDataFormDatasourceData } from '@core/utils';
import { IWidgetSubscription } from '@core/api/widget-api.models';
import { WidgetExportCellValue, WidgetExportTable } from '@core/utils/widget-data-export';

const ENTITY_COLUMN = 'Entity';
const TIMESTAMP_COLUMN = 'Timestamp';

export function buildWidgetExportTable(ctx: WidgetContext, type: widgetType): WidgetExportTable {
  const subscription = ctx?.defaultSubscription;
  if (!subscription) {
    return {columns: [], rows: []};
  }
  if (type === widgetType.alarm) {
    return buildAlarmExportTable(subscription);
  }
  if (type === widgetType.timeseries) {
    return buildTimeseriesExportTable(subscription);
  }
  // 'latest' and any other widget type a user has explicitly opted into export for: best-effort one-row-per-
  // entity table off the same generic datasource data 'latest' widgets (e.g. Entities table) use.
  return buildLatestExportTable(subscription);
}

export function widgetExportFilename(ctx: WidgetContext): string {
  const title = ctx?.widgetTitle;
  return title && title.trim().length ? title.trim() : 'widget-data';
}

function uniqueDataKeyLabels(datasources: Datasource[]): string[] {
  const labels: string[] = [];
  const seen = new Set<string>();
  (datasources ?? []).forEach(ds => (ds.dataKeys ?? []).forEach(dataKey => {
    const label = dataKey.label || dataKey.name;
    if (!seen.has(label)) {
      seen.add(label);
      labels.push(label);
    }
  }));
  return labels;
}

function formattedCellValue(value: any): WidgetExportCellValue {
  if (value === null || value === undefined) {
    return value;
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return value;
  }
  try {
    return JSON.stringify(value);
  } catch (e) {
    return String(value);
  }
}

function formatTimestamp(ts: number): string {
  if (!isFinite(ts)) {
    return '';
  }
  const date = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function buildLatestExportTable(subscription: IWidgetSubscription): WidgetExportTable {
  const dataKeyLabels = uniqueDataKeyLabels(subscription.datasources);
  const columns = [ENTITY_COLUMN, ...dataKeyLabels];
  const entityRows = formattedDataFormDatasourceData(subscription.data ?? []);
  const rows = entityRows.map(row => [
    row.entityLabel || row.entityName || '',
    ...dataKeyLabels.map(label => formattedCellValue(row[label]))
  ]);
  return {columns, rows};
}

function buildTimeseriesExportTable(subscription: IWidgetSubscription): WidgetExportTable {
  const dataKeyLabels = uniqueDataKeyLabels(subscription.datasources);
  const columns = [ENTITY_COLUMN, TIMESTAMP_COLUMN, ...dataKeyLabels];
  const perEntityRows = formattedDataArrayFromDatasourceData(subscription.data ?? []);
  const rows: WidgetExportCellValue[][] = [];
  perEntityRows.forEach(entityRows => {
    [...entityRows]
      .sort((a, b) => (a.time ?? 0) - (b.time ?? 0))
      .forEach(row => {
        rows.push([
          row.entityLabel || row.entityName || '',
          formatTimestamp(row.time),
          ...dataKeyLabels.map(label => formattedCellValue(row[label]))
        ]);
      });
  });
  return {columns, rows};
}

// Mirrors alarms-table-widget.component.ts#alarmDataToInfo's per-key value extraction (same
// dataKeyTypeToEntityKeyType + latest[type][name].value path) without importing anything from that component.
function extractAlarmCellValue(alarm: AlarmData, dataKey: DataKey): WidgetExportCellValue {
  const entityKeyType = dataKeyTypeToEntityKeyType(dataKey.type);
  const latestForType = entityKeyType ? alarm.latest?.[entityKeyType] : undefined;
  const tsValue = latestForType ? latestForType[dataKey.name] : undefined;
  return tsValue ? formattedCellValue(tsValue.value) : '';
}

function buildAlarmExportTable(subscription: IWidgetSubscription): WidgetExportTable {
  const dataKeys = subscription.alarmSource?.dataKeys ?? [];
  const columns = dataKeys.map(dataKey => dataKey.label || dataKey.name);
  const alarms = subscription.alarms?.data ?? [];
  const rows = alarms.map(alarm => dataKeys.map(dataKey => extractAlarmCellValue(alarm, dataKey)));
  return {columns, rows};
}
