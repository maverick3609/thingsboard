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

import { buildWidgetExportTable, widgetExportFilename } from './widget-export-data-source';
import { WidgetContext } from '@home/models/widget-component.models';
import { Datasource, widgetType } from '@shared/models/widget.models';
import { DataKeyType } from '@shared/models/telemetry/telemetry.models';

function ctxWith(defaultSubscription: any, widgetTitle?: string): WidgetContext {
  return {defaultSubscription, widgetTitle} as unknown as WidgetContext;
}

describe('widget-export-data-source', () => {

  describe('widgetExportFilename', () => {
    it('uses the widget title when present', () => {
      expect(widgetExportFilename(ctxWith(null, 'My Report '))).toBe('My Report');
    });

    it('falls back to a default name when there is no title', () => {
      expect(widgetExportFilename(ctxWith(null, ''))).toBe('widget-data');
      expect(widgetExportFilename(ctxWith(null, undefined))).toBe('widget-data');
    });
  });

  describe('buildWidgetExportTable — no subscription', () => {
    it('returns an empty table', () => {
      const table = buildWidgetExportTable(ctxWith(null), widgetType.timeseries);
      expect(table).toEqual({columns: [], rows: []});
    });
  });

  describe('buildWidgetExportTable — timeseries', () => {
    it('flattens datasource data into one row per (entity, timestamp)', () => {
      const datasource: Datasource = {
        entityName: 'Device A', entityLabel: 'Device A',
        dataKeys: [{name: 'temperature', label: 'Temperature', type: DataKeyType.timeseries}]
      };
      const subscription = {
        datasources: [datasource],
        data: [
          {datasource, dataKey: datasource.dataKeys[0], data: [[2000, 21], [1000, 20]]}
        ]
      };
      const table = buildWidgetExportTable(ctxWith(subscription), widgetType.timeseries);
      expect(table.columns).toEqual(['Entity', 'Timestamp', 'Temperature']);
      expect(table.rows.length).toBe(2);
      // Sorted ascending by time even though the source data was descending.
      expect(table.rows[0][0]).toBe('Device A');
      expect(table.rows[0][2]).toBe(20);
      expect(table.rows[1][2]).toBe(21);
    });
  });

  describe('buildWidgetExportTable — latest', () => {
    it('produces one row per entity with the latest value per key', () => {
      const datasource: Datasource = {
        entityName: 'Device A', entityLabel: 'Device A',
        dataKeys: [{name: 'temperature', label: 'Temperature', type: DataKeyType.timeseries}]
      };
      const subscription = {
        datasources: [datasource],
        data: [
          {datasource, dataKey: datasource.dataKeys[0], data: [[1000, 20]]}
        ]
      };
      const table = buildWidgetExportTable(ctxWith(subscription), widgetType.latest);
      expect(table.columns).toEqual(['Entity', 'Temperature']);
      expect(table.rows).toEqual([['Device A', 20]]);
    });
  });

  describe('buildWidgetExportTable — alarm', () => {
    it('extracts alarm field + configured data key values via the shared entity-key-type mapping', () => {
      const alarmSource: Datasource = {
        dataKeys: [
          {name: 'createdTime', label: 'Created time', type: DataKeyType.alarm},
          {name: 'severity', label: 'Severity', type: DataKeyType.alarm}
        ]
      };
      const subscription = {
        alarmSource,
        alarms: {
          data: [
            {
              latest: {
                ALARM_FIELD: {
                  createdTime: {ts: 1000, value: 1000},
                  severity: {ts: 1000, value: 'CRITICAL'}
                }
              }
            }
          ]
        }
      };
      const table = buildWidgetExportTable(ctxWith(subscription), widgetType.alarm);
      expect(table.columns).toEqual(['Created time', 'Severity']);
      expect(table.rows).toEqual([[1000, 'CRITICAL']]);
    });

    it('returns an empty string for a data key with no matching latest value', () => {
      const alarmSource: Datasource = {
        dataKeys: [{name: 'assignee', label: 'Assignee', type: DataKeyType.alarm}]
      };
      const subscription = {alarmSource, alarms: {data: [{latest: {}}]}};
      const table = buildWidgetExportTable(ctxWith(subscription), widgetType.alarm);
      expect(table.rows).toEqual([['']]);
    });
  });
});
