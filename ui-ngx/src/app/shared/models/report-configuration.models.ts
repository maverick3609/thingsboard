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

// Typed mirror of common/data/.../report/configuration/** (R2a Java model). Root discriminator
// is `format`, component discriminator is `type` - both kept as string literals (not the enum
// types) so unions narrow correctly. jsonb wire shape only; nothing here changes the backend.

import { EntityFilter, KeyFilter } from '@shared/models/query/query.models';
import { AlarmSearchStatus, AlarmSeverity } from '@shared/models/alarm.models';
import { UserId } from '@shared/models/id/user-id';
import { ReportTemplateId } from '@shared/models/id/report-template-id';
import { AggregationType } from '@shared/models/time/time.models';
import { IntervalType } from '@shared/models/telemetry/telemetry.models';
import { DashboardReportConfig } from '@shared/models/report.models';

export { TbReportFormat } from '@shared/models/report.models';

// style/TextAlignment.java, style/VerticalAlignment.java, style/FontWeight.java, style/FontStyle.java
export enum TextAlignment {
  LEFT = 'left',
  CENTER = 'center',
  RIGHT = 'right',
  JUSTIFY = 'justify'
}

export enum VerticalAlignment {
  TOP = 'top',
  MIDDLE = 'middle',
  BOTTOM = 'bottom'
}

export enum FontWeight {
  NORMAL = 'normal',
  BOLD = 'bold',
  WEIGHT_500 = '500'
}

export enum FontStyle {
  NORMAL = 'normal',
  ITALIC = 'italic'
}

// style/BorderLength.java, style/BorderType.java - BorderLength is name-valued (no @JsonValue).
export enum BorderLength {
  LONG = 'LONG',
  SHORT = 'SHORT'
}

export enum BorderType {
  SOLID = 'solid',
  DASHED = 'dashed',
  DOTTED = 'dotted'
}

// style/PageSize.java - name-valued; dimensions (pt @ 72dpi) mirror the engine's page-size table
// (flying-saucer PDF renderer), not part of the Java enum's JSON wire form.
export enum PageSize {
  A4 = 'A4',
  LETTER = 'LETTER',
  LEGAL = 'LEGAL',
  A5 = 'A5',
  A3 = 'A3',
  TABLOID = 'TABLOID'
}

export const PageSizeDimensions: Record<PageSize, { width: number; height: number }> = {
  [PageSize.A4]: { width: 595, height: 842 },
  [PageSize.LETTER]: { width: 612, height: 792 },
  [PageSize.LEGAL]: { width: 612, height: 1008 },
  [PageSize.A5]: { width: 420, height: 595 },
  [PageSize.A3]: { width: 842, height: 1191 },
  [PageSize.TABLOID]: { width: 792, height: 1224 }
};

// style/PageOrientation.java
export enum PageOrientation {
  PORTRAIT = 'PORTRAIT',
  LANDSCAPE = 'LANDSCAPE'
}

// style/Insets.java - all four are Java primitive `int`, always set as a unit.
export interface Insets {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

// style/Font.java
export interface Font {
  size?: number;
  weight?: FontWeight;
  style?: FontStyle;
  family?: string;
}

// style/Heading.java - table/section heading style, distinct from the page-level HeadingComponent.
export interface Heading {
  text?: string;
  font?: Font;
  color?: string;
  textAlignment?: TextAlignment;
  verticalAlignment?: VerticalAlignment;
  height?: number;
}

// image/ImageSourceType.java, image/ImageWidthType.java, image/ImageAlignment.java
export enum ImageSourceType {
  IMAGE = 'image',
  ENTITY_KEY = 'entityKey'
}

export enum ImageWidthType {
  FIT_WIDTH = 'fitWidth',
  ORIGINAL = 'original',
  CUSTOM = 'custom'
}

export enum ImageAlignment {
  LEFT = 'left',
  CENTER = 'center',
  RIGHT = 'right'
}

// DataSourceType.java
export enum DataSourceType {
  DEVICE = 'device',
  ENTITY = 'entity',
  ENTITY_COUNT = 'entityCount',
  ALARM_COUNT = 'alarmCount'
}

// timewindow/QuickTimeInterval.java - name-valued.
export enum QuickTimeInterval {
  YESTERDAY = 'YESTERDAY',
  DAY_BEFORE_YESTERDAY = 'DAY_BEFORE_YESTERDAY',
  THIS_DAY_LAST_WEEK = 'THIS_DAY_LAST_WEEK',
  PREVIOUS_WEEK = 'PREVIOUS_WEEK',
  PREVIOUS_WEEK_ISO = 'PREVIOUS_WEEK_ISO',
  PREVIOUS_MONTH = 'PREVIOUS_MONTH',
  PREVIOUS_QUARTER = 'PREVIOUS_QUARTER',
  PREVIOUS_HALF_YEAR = 'PREVIOUS_HALF_YEAR',
  PREVIOUS_YEAR = 'PREVIOUS_YEAR',
  CURRENT_HOUR = 'CURRENT_HOUR',
  CURRENT_DAY = 'CURRENT_DAY',
  CURRENT_DAY_SO_FAR = 'CURRENT_DAY_SO_FAR',
  CURRENT_WEEK = 'CURRENT_WEEK',
  CURRENT_WEEK_ISO = 'CURRENT_WEEK_ISO',
  CURRENT_WEEK_SO_FAR = 'CURRENT_WEEK_SO_FAR',
  CURRENT_WEEK_ISO_SO_FAR = 'CURRENT_WEEK_ISO_SO_FAR',
  CURRENT_MONTH = 'CURRENT_MONTH',
  CURRENT_MONTH_SO_FAR = 'CURRENT_MONTH_SO_FAR',
  CURRENT_QUARTER = 'CURRENT_QUARTER',
  CURRENT_QUARTER_SO_FAR = 'CURRENT_QUARTER_SO_FAR',
  CURRENT_HALF_YEAR = 'CURRENT_HALF_YEAR',
  CURRENT_HALF_YEAR_SO_FAR = 'CURRENT_HALF_YEAR_SO_FAR',
  CURRENT_YEAR = 'CURRENT_YEAR',
  CURRENT_YEAR_SO_FAR = 'CURRENT_YEAR_SO_FAR'
}

// timewindow/FixedTimeWindow.java
export interface FixedTimeWindow {
  startTimeMs?: number;
  endTimeMs?: number;
}

// timewindow/Interval.java has a custom Jackson (de)serializer: it writes a plain JSON number
// (milliseconds) when intervalType is MILLISECONDS/unset, or the intervalType name as a bare
// JSON string otherwise - on the wire this is a primitive, never `{interval, intervalType}`.
export type ReportInterval = number | IntervalType;

// timewindow/History.java - renamed from Java's `History` to avoid shadowing the DOM lib type of
// the same name. `historyType` is a raw Java `int` selector with no CE-side enum declared for it.
export interface TimeWindowHistory {
  historyType?: number;
  interval?: ReportInterval;
  timewindowMs?: number;
  fixedTimewindow?: FixedTimeWindow;
  quickInterval?: QuickTimeInterval;
}

// timewindow/AggregationConfiguration.java - `Aggregation` (kv package) is CE's AggregationType.
export interface AggregationConfiguration {
  type?: AggregationType;
  limit?: number;
}

// timewindow/TimeWindowConfiguration.java
export interface TimeWindowConfiguration {
  history?: TimeWindowHistory;
  aggregation?: AggregationConfiguration;
  timezone?: string;
}

// AlarmFilterConfig.java
export interface AlarmFilterConfig {
  typeList?: string[];
  statusList?: AlarmSearchStatus[];
  severityList?: AlarmSeverity[];
  assigneeId?: UserId;
  searchPropagatedAlarms?: boolean;
}

// DataSource.java
export interface DataSource {
  type?: DataSourceType;
  deviceId?: string;
  entityAliasId?: string;
  filterId?: string;
  dataKeys?: DataKey[];
  latestDataKeys?: DataKey[];
  alarmFilterConfig?: AlarmFilterConfig;
}

// DataKey.java
export interface DataKey {
  name?: string;
  type?: string;
  label?: string;
  decimals?: number;
  units?: string;
  aggregationType?: AggregationType;
  timewindow?: TimeWindowConfiguration;
  usePostProcessing?: boolean;
  postFuncBody?: string;
  settings?: DataKeySettings;
}

// CellSettings.java
export interface CellSettings {
  font?: Font;
  color?: string;
  backgroundColor?: string;
  textAlignment?: TextAlignment;
  verticalAlignment?: VerticalAlignment;
}

// style/DataKeySettingsType.java + DefaultDataKeySettings.java/ColumnSettings.java - discriminated
// by the EXISTING_PROPERTY `type`.
export enum DataKeySettingsType {
  COLUMN = 'COLUMN',
  DEFAULT = 'DEFAULT'
}

export interface DefaultDataKeySettings {
  type: 'DEFAULT';
}

export interface ColumnSettings {
  type: 'COLUMN';
  columnWidth?: string;
  header?: CellSettings;
  cell?: CellSettings;
}

export type DataKeySettings = ColumnSettings | DefaultDataKeySettings;

// TableSortOrder.java - `Direction` is a nested Java enum, kept as its own top-level TS enum since
// it isn't the same concept as the app's generic list-sort `Direction`.
export enum TableSortDirection {
  ASC = 'ASC',
  DESC = 'DESC'
}

export interface TableSortOrder {
  column?: string;
  direction?: TableSortDirection;
}

// EntityAlias.java, Filter.java
export interface EntityAlias {
  id?: string;
  alias?: string;
  filter?: EntityFilter;
}

export interface Filter {
  id?: string;
  filter?: string;
  keyFilters?: KeyFilter[];
}

// components/ReportComponentType.java - name-valued; discriminates the ReportComponent union.
export enum ReportComponentType {
  HEADING = 'HEADING',
  RICH_TEXT = 'RICH_TEXT',
  DIVIDER = 'DIVIDER',
  PAGE_BREAK = 'PAGE_BREAK',
  IMAGE = 'IMAGE',
  ENTITY_TABLE = 'ENTITY_TABLE',
  ALARM_TABLE = 'ALARM_TABLE',
  TIME_SERIES_TABLE = 'TIME_SERIES_TABLE',
  SUB_REPORT = 'SUB_REPORT',
  DASHBOARD = 'DASHBOARD',
  ERROR = 'ERROR'
}

// components/AbstractLayoutReportComponent.java - mixed into every layout-bearing component.
export interface ReportComponentLayout {
  margins?: Insets;
  paddings?: Insets;
  background?: string;
  borderWidth?: number;
  borderRadius?: number;
  borderColor?: string;
}

// components/AbstractDataReportComponent.java
export interface ReportComponentDataSources {
  dataSources?: DataSource[];
}

// components/AbstractTableReportComponent.java / AbstractTableWithLayoutReportComponent.java
export interface ReportComponentTableSettings {
  showTableHeading?: boolean;
  tableHeading?: Heading;
  tableSortOrder?: TableSortOrder;
}

// components/AbstractImageComponent.java - shared by ImageComponent and DashboardComponent.
export interface ReportImageSizing {
  widthType?: ImageWidthType;
  customWidth?: number;
  alignment?: ImageAlignment;
}

// components/HeadingComponent.java
export interface HeadingComponent extends ReportComponentLayout, ReportComponentDataSources {
  type: 'HEADING';
  value: string;
  font?: Font;
  color?: string;
  textAlignment?: TextAlignment;
  verticalAlignment?: VerticalAlignment;
  height?: number;
}

// components/RichTextComponent.java
export interface RichTextComponent extends ReportComponentLayout, ReportComponentDataSources {
  type: 'RICH_TEXT';
  value: string;
}

// components/DividerComponent.java
export interface DividerComponent extends ReportComponentLayout {
  type: 'DIVIDER';
  length?: BorderLength;
  borderType?: BorderType;
  widthPx?: number;
  color?: string;
}

// components/PageBreakComponent.java
export interface PageBreakComponent {
  type: 'PAGE_BREAK';
}

// components/ImageComponent.java
export interface ImageComponent extends ReportComponentLayout, ReportComponentDataSources, ReportImageSizing {
  type: 'IMAGE';
  sourceType: ImageSourceType;
  imageUrl?: string;
}

// components/EntityTableComponent.java - adds no fields of its own.
export interface EntityTableComponent extends ReportComponentLayout, ReportComponentDataSources, ReportComponentTableSettings {
  type: 'ENTITY_TABLE';
}

// components/AlarmTableComponent.java - `getDataSources()` is overridden + @JsonIgnore'd (derived
// from alarmSource), so this component never carries a `dataSources` JSON property.
export interface AlarmTableComponent extends ReportComponentLayout, ReportComponentTableSettings {
  type: 'ALARM_TABLE';
  alarmSource?: DataSource;
  timewindow?: TimeWindowConfiguration;
}

// components/TimeseriesTableComponent.java
export interface TimeseriesTableComponent extends ReportComponentLayout, ReportComponentDataSources, ReportComponentTableSettings {
  type: 'TIME_SERIES_TABLE';
  timewindow?: TimeWindowConfiguration;
  showTimestamp?: boolean;
  timestampLabel?: string;
  timestampPattern?: string;
  timestampColumnSettings?: ColumnSettings;
}

// components/SubReportComponent.java - extends AbstractDataReportComponent only (no layout fields).
export interface SubReportComponent extends ReportComponentDataSources {
  type: 'SUB_REPORT';
  templateId?: ReportTemplateId;
  avoidPageBreakInside?: boolean;
}

// components/DashboardComponent.java - config reuses report.models.ts's DashboardReportConfig
// (org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig) verbatim.
export interface DashboardComponent extends ReportComponentLayout, ReportComponentDataSources, ReportImageSizing {
  type: 'DASHBOARD';
  config: DashboardReportConfig;
}

// components/ErrorComponent.java - `exception` is @JsonIgnore'd transient render state, never
// serialized, so it has no wire representation here.
export interface ErrorComponent {
  type: 'ERROR';
  errorMessage?: string;
}

export type ReportComponent =
  | HeadingComponent
  | RichTextComponent
  | DividerComponent
  | PageBreakComponent
  | ImageComponent
  | EntityTableComponent
  | AlarmTableComponent
  | TimeseriesTableComponent
  | SubReportComponent
  | DashboardComponent
  | ErrorComponent;

// configuration/AbstractReportTemplateConfig.java
export interface ReportTemplateConfigBase {
  namePattern?: string;
  timeDataPattern?: string;
  entityAliases?: EntityAlias[];
  filters?: Filter[];
  components: ReportComponent[];
}

// configuration/HeaderFooter.java
export interface HeaderFooter {
  enabled?: boolean;
  components: ReportComponent[];
  firstPage?: HeaderFooter;
}

// configuration/PdfReportTemplateConfig.java
export interface PdfReportTemplateConfig extends ReportTemplateConfigBase {
  format: 'PDF';
  pageSize: PageSize;
  pageOrientation: PageOrientation;
  pageMargins: Insets;
  pageBackground?: string;
  header?: HeaderFooter;
  footer?: HeaderFooter;
}

// configuration/CsvReportTemplateConfig.java - shared Abstract fields only.
export interface CsvReportTemplateConfig extends ReportTemplateConfigBase {
  format: 'CSV';
}

// configuration/ReportTemplateConfig.java - `format` is the @JsonTypeInfo discriminator.
export type ReportTemplateConfigModel = PdfReportTemplateConfig | CsvReportTemplateConfig;

export function newPdfReportTemplateConfig(): PdfReportTemplateConfig {
  return {
    format: 'PDF',
    components: [],
    pageSize: PageSize.A4,
    pageOrientation: PageOrientation.PORTRAIT,
    pageMargins: { left: 40, right: 40, top: 40, bottom: 40 }
  };
}

export function newReportComponent(type: ReportComponentType): ReportComponent {
  switch (type) {
    case ReportComponentType.HEADING:
      return { type: 'HEADING', value: '' };
    case ReportComponentType.RICH_TEXT:
      return { type: 'RICH_TEXT', value: '' };
    case ReportComponentType.DIVIDER:
      return { type: 'DIVIDER' };
    case ReportComponentType.PAGE_BREAK:
      return { type: 'PAGE_BREAK' };
    case ReportComponentType.IMAGE:
      return { type: 'IMAGE', sourceType: ImageSourceType.IMAGE };
    case ReportComponentType.ENTITY_TABLE:
      return { type: 'ENTITY_TABLE' };
    case ReportComponentType.ALARM_TABLE:
      return { type: 'ALARM_TABLE' };
    case ReportComponentType.TIME_SERIES_TABLE:
      return { type: 'TIME_SERIES_TABLE' };
    case ReportComponentType.SUB_REPORT:
      return { type: 'SUB_REPORT' };
    case ReportComponentType.DASHBOARD:
      return { type: 'DASHBOARD', config: {} };
    case ReportComponentType.ERROR:
      return { type: 'ERROR' };
    default:
      throw new Error(`Unknown ReportComponentType: ${type}`);
  }
}
