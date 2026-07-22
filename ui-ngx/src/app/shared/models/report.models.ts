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

import { BaseData } from '@shared/models/base-data';
import { TenantId } from '@shared/models/id/tenant-id';
import { CustomerId } from '@shared/models/id/customer-id';
import { UserId } from '@shared/models/id/user-id';
import { NotificationTemplateId } from '@shared/models/id/notification-template-id';
import { ReportId } from '@shared/models/id/report-id';
import { ReportTemplateId } from '@shared/models/id/report-template-id';

// TbReportFormat.java
export enum TbReportFormat {
  PDF = 'PDF',
  CSV = 'CSV'
}

// ReportTemplateType.java
export enum ReportTemplateType {
  REPORT = 'REPORT',
  SUB_REPORT = 'SUB_REPORT'
}

// Report.java - persisted report metadata (rendered bytes live server-side; fetched separately via
// ReportService#downloadReport).
export interface Report extends BaseData<ReportId> {
  tenantId?: TenantId;
  customerId?: CustomerId;
  templateId?: string;   // NOTE: backend field is a plain UUID, not a typed ReportTemplateId (Report.java:47)
  format: TbReportFormat;
  name: string;
  userId: UserId;
}

// ReportInfo.java - Report enriched with the owning customer's title, for the report history list.
export interface ReportInfo extends Report {
  customerTitle?: string;
}

// BaseReportTemplate.java - fields shared by ReportTemplate (full entity) and ReportTemplateInfo
// (list projection).
export interface BaseReportTemplate extends BaseData<ReportTemplateId> {
  tenantId?: TenantId;
  customerId?: CustomerId;
  name: string;
  format?: TbReportFormat;
  type?: ReportTemplateType;
  description?: string;
  externalId?: ReportTemplateId;
  version?: number;
  additionalInfo?: any;
}

// ReportTemplate.java - configuration is the typed ReportTemplateConfig (PE shape, design spec §2.4 /
// R2a §4): root keyed on `format` ('PDF'|'CSV'), a `components` array where each component is
// discriminated by `type`, and the DASHBOARD component nests its dashboard settings under `config`
// ({dashboardId, state, timewindow, ...}). Kept `any` here since the R1 dialog only edits the
// single dashboard component; the full designer is R2c.
export interface ReportTemplate extends BaseReportTemplate {
  configuration?: any;
}

// ReportTemplateInfo.java - BaseReportTemplate enriched with the owning customer's title, for the
// report template list (GET /api/reportTemplateInfos/all).
export interface ReportTemplateInfo extends BaseReportTemplate {
  customerTitle?: string;
}

// ReportConfig.java - scheduler generateReport event config, PE-verbatim field names.
export interface ReportConfig {
  reportTemplateId: ReportTemplateId;
  userId: UserId;
  timezone?: string;
  targets?: Array<string>;
  notificationTemplateId?: NotificationTemplateId;
}

// DashboardReportConfig.java - on-demand dashboard-report download body / legacy
// generateDashboardReport scheduler event's msgBody.reportConfig, PE-verbatim field names.
export interface DashboardReportConfig {
  baseUrl?: string;
  dashboardId?: string;
  state?: string;
  timezone?: string;
  useDashboardTimewindow?: boolean;
  timewindow?: any;
  namePattern?: string;
  type?: string;
  useCurrentUserCredentials?: boolean;
  userId?: string;
}
