# Reporting (PE Replication) — Design Specification

**Date:** 2026-07-17
**Status:** Draft (design)
**Source:** ThingsBoard PE **4.2.0** (decompiled from `docs/jars/` — `report-4.2.0PE.jar`, `data-4.2.0PE.jar`, `dao-4.2.0PE.jar`, `rule-engine-*-4.2.0PE.jar`, `ui-ngx-4.2.0PE.jar`; javap + CFR)
**Target:** Inferrix Cortex on ThingsBoard CE 4.3.1.x / 4.4.0-SNAPSHOT, branch `inferrix-release-4.3`
**Approach:** In-core PE-mirror — classes at exact PE paths, PE-identical REST API / data models / JSON contracts, core-file modifications tracked in `INFERRIX-PATCHES.md`. Same discipline as the White Labeling and Scheduler ports.
**Renderer decision:** In-process **Playwright-Java** behind a PE-identical internal interface (replaces PE's external `tb-web-report` microservice). One deployable; per-render browser lifecycle; hard timeout + concurrency semaphore.
**Integrates with:** the ported Scheduler feature (`docs/specs/2026-07-13-scheduler-design.md`) via its deliberate `SchedulerReportExecutor` seam (`@ConditionalOnMissingBean(NoOpSchedulerReportExecutor)`), and the CE **Job/Task** framework (`common/data/job/*`, `service/job/*`) and **Notification Center**.

> **The old `inferrix-reporting/` module (master branch — Playwright + Liquibase + `@Scheduled` polling, `inferrix_report_*` tables) is DISCARDED** (user decision 2026-07-16). It is never forward-ported; its `## Feature: Reporting & Scheduling` ledger section is obsolete. This is a fresh PE-fidelity build on `inferrix-release-4.3` only.

---

## 1. Overview

PE 4.2 reporting is **two coexisting subsystems** that share one browser-rendering primitive:

- **Subsystem A — Dashboard reports (legacy, browser-rendered).** A dashboard is opened in a headless browser and captured as **PDF / PNG / JPEG**. Driven by: `generateDashboardReport` scheduler events (→ rule engine → legacy `generate dashboard report` rule node), and the on-demand `POST /api/report/{dashboardId}/download` + `/api/report/test` endpoints. Config type: `DashboardReportConfig`.
- **Subsystem B — Template reports (server-rendered).** A `ReportTemplate` entity (a multi-component document — dashboard snapshots, entity/alarm/timeseries tables, rich text, headers) is rendered to **PDF / CSV**, persisted as a `Report` entity, and delivered through the **Notification Center**. Driven by: `generateReport` scheduler events (→ Job/Task framework), the `generate report` (V2) rule node, and `/api/v2/report/*`. A template's *dashboard component* renders through the same browser primitive as subsystem A.

The Scheduler port already isolated subsystem B's entry point behind `SchedulerReportExecutor` (PE dispatches `generateReport` to `JobManager.submitJob(Job.newReportJob(...))` — see decompiled `DefaultSchedulerService:356-361`).

### Two implementation cycles

This feature is decomposed into two spec → plan → implement → verify cycles. **This spec covers R1 only.** R2 gets its own spec.

**R1 (this spec) — full pipeline, dashboard-sourced reports.** Everything needed to schedule, generate, deliver, store, and download a **dashboard-based** report end to end:
- Complete domain model (`Report`, `ReportInfo`, `ReportTemplate`, `ReportConfig`, `DashboardReportConfig`, ids, formats, notification info) — the `ReportTemplateConfig` document schema is stored as passthrough `JsonNode` in R1 (§2.4) so R2 can type it without a schema migration.
- `report` + `report_template` tables via the Inferrix schema overlay.
- Full DAO layer (entities, repositories, services, validators) — PE-identical queries.
- `JobType.REPORT` wiring + `ReportJobConfiguration` / `ReportTask` / `ReportTaskResult` / `ReportJobResult` registered into the CE Job/Task framework (auto-discovered — §5).
- In-process **Playwright renderer** implementing PE's exact browser protocol (§6), plus the ui-ngx `reportView` hooks the browser protocol depends on.
- Server-minted user access token (`createUserAccessToken`) — §7.
- Real `SchedulerReportExecutor` bean (§8) replacing the no-op.
- Delivery via Notification Center with report **email attachments** (§9): `TbEmail.reports`, `DefaultMailService` attach loop, `EmailNotificationChannel` pass-through, `NotificationRequestConfig.reports`.
- REST: `/api/v2/report*` (history: list / get / download / delete / create), `/api/report/{dashboardId}/download`, `/api/report/test`, `/api/v2/report/test`, `/api/v2/report/request`, `/api/reportTemplate*` CRUD (§10).
- UI: report-templates page (dashboard-component templates), report-history page, on-demand dashboard "Download report" toolbar action, native scheduler `generateReport` config form (replaces the raw-JSON placeholder the Scheduler port shipped), menu + i18n (§12).
- Template rendering in R1 handles the **dashboard component only** (via Playwright → `page.pdf()` / screenshot). Non-dashboard components and CSV are R2.

**R2 (future spec — §18) — template engine.** Thymeleaf + flying-saucer/iText PDF stack, all non-dashboard component renderers (entity/alarm/timeseries tables, rich text, heading, divider, page break, image, sub-report), CSV format, the full report-template **designer** UI, and both rule nodes (`generate dashboard report`, `generate report`). R2 swaps renderer internals behind the R1 interfaces — **no schema or API churn**.

### Excluded (both cycles, unless noted)

- Microservice/distributed report mode: `RemoteTbReportCtxProvider`, `RemoteReportDataService`, `TbReportConsumerService`, `TbReportQueueFactory`, `@TbReportComponent`, report notification queue proto. (In-process monolith only.)
- `BlobEntity` storage path (PE's legacy dashboard-report attachment store) — R1 attaches report bytes directly (§9); the rule-node `attachments` metadata channel is R2 with the rule nodes.
- Edge sync and Version-Control export/import (`ReportTemplateExportService` / `ReportTemplateImportService`).
- `/scheduledReports` endpoint + `ScheduledReportInfo` (the Scheduler port already dropped the 3 backing DAO methods).
- API-usage report limits / rate limits (`ApiUsageRecordKey.GENERATED_REPORTS_COUNT`, `isReportCreationEnabled`, `LimitedApi.REPORTS`, `ReportDataValidator.validateReportSize`) — **stubbed permissive** in R1 to avoid ~5 extra tb-core enum patches (§15 #S-stub).
- Entity-group ownership (`OwnersCacheService`, `ctx.getPeContext()`) — replaced by a customer-else-tenant fallback for `userOwnerId` (§15).

---

## 2. Data Model

New files under `common/data/src/main/java/org/thingsboard/server/common/data/`, package-identical to PE. All are PE-only (no CE equivalent).

### 2.1 Report entity & value types (`report/`, `dashboardreport/`)

| Class | Role |
|---|---|
| `report/Report` | `extends BaseData<ReportId>` (impl `HasTenantId, HasCustomerId, HasName`). Fields: `tenantId, customerId (nullable), templateId (UUID, nullable), format (TbReportFormat), name (String), userId (UserId)`. Immutable after create (PE: "Report can't be updated"). The **bytes are not a bean field** — stored/served via the DAO's separate `data` column (§4). |
| `report/ReportInfo` | `extends Report` + `customerTitle` (LEFT JOIN on customer) — backs the history list. |
| `report/ReportData` | Render output DTO: `{ byte[] data, String name, String contentType }`. Not persisted; the bridge between renderer and DAO. |
| `report/ReportConfig` | Scheduler `generateReport` config (§2.3): `reportTemplateId (ReportTemplateId), userId (UserId), timezone (String), targets (List<UUID>), notificationTemplateId (NotificationTemplateId)`. |
| `report/ReportRequest` | `/api/v2/report/{test,request}` body: `reportTemplateId (ReportTemplateId, nullable), reportTemplateConfig (JsonNode, nullable — wins over id), timezone (String), userId (UserId, nullable → caller), originator (EntityId, nullable), targets, notificationTemplateId`. |
| `report/TbReportFormat` | enum `PDF("application/pdf", ".pdf"), CSV("text/csv", ".csv")`. (R1 produces PDF/PNG/JPEG bytes for dashboard reports with the dashboard's own content type; `TbReportFormat` classifies persisted template reports — R1 persists PDF; CSV is R2.) |
| `report/ReportInfoQuery` | Paged history query: `pageLink (PageLink), reportTemplateId (nullable), userId (nullable), includeCustomers (boolean)`. |
| `dashboardreport/DashboardReportConfig` | On-demand + legacy dashboard-report config (§2.3): `baseUrl, dashboardId (String), state (String, nullable), timezone, useDashboardTimewindow (boolean), timewindow (JsonNode, nullable), namePattern, type ("pdf"|"png"|"jpeg"), useCurrentUserCredentials (boolean), userId (String)`. |
| `dashboardreport/DashboardReportData` | Renderer request/response holder for subsystem A (mirrors `ReportData` for the download path). |

### 2.2 Report template (`report/`, `report/configuration/`)

| Class | Role |
|---|---|
| `report/BaseReportTemplate` | `extends BaseDataWithAdditionalInfo<ReportTemplateId>` (impl `HasName, HasTenantId, HasCustomerId, ExportableEntity<ReportTemplateId>`). Fields: `tenantId, customerId, name, format (TbReportFormat), type (ReportTemplateType), description, externalId, version`. |
| `report/ReportTemplate` | `extends BaseReportTemplate` + `configuration`. **R1: `configuration` is `JsonNode` (passthrough)** — see §2.4. (PE types it as `ReportTemplateConfig`; R2 introduces the typed hierarchy.) |
| `report/ReportTemplateInfo` | `extends BaseReportTemplate` + `customerTitle` — backs the template list. |
| `report/ReportTemplateType` | enum `REPORT, SUB_REPORT`. |
| `report/ReportTemplateQuery` | Paged: `pageLink, typeList (List<ReportTemplateType>), formatList (List<TbReportFormat>), includeCustomers`. |
| `id/ReportId` | `UUIDBased implements EntityId` → `EntityType.REPORT`. |
| `id/ReportTemplateId` | `UUIDBased implements EntityId` → `EntityType.REPORT_TEMPLATE`. |

### 2.3 JSON contracts (PE-verbatim — the UI and engine depend on these field names)

**Scheduler event `generateReport` `configuration`** (= `ReportConfig` inline; **no** `originatorId`/`msgType`/`msgBody`/`metadata` for this type — it is the one scheduler type that does not build a `TbMsg`):

```json
{
  "reportTemplateId":       { "entityType": "REPORT_TEMPLATE", "id": "<uuid>" },
  "userId":                 { "entityType": "USER", "id": "<uuid>" },
  "timezone":               "Europe/Kiev",
  "targets":                ["<notificationTargetUuid>", "..."],
  "notificationTemplateId": { "entityType": "NOTIFICATION_TEMPLATE", "id": "<uuid>" }
}
```

`targets` + `notificationTemplateId` optional; when both present, the generated report is delivered to those notification targets using that template (email channel attaches the bytes). There is **no** legacy to/cc/bcc/subject/body in PE 4.2 — delivery is entirely notification-target based.

**`DashboardReportConfig`** (on-demand download body, and legacy `generateDashboardReport` `msgBody.reportConfig`):

```json
{
  "baseUrl":                "https://cortex.inferrix.com",
  "dashboardId":            "<uuid>",
  "state":                  "<dashboard state id | null>",
  "timezone":               "Europe/Kiev",
  "useDashboardTimewindow": false,
  "timewindow":             { /* dashboard timewindow JSON, see below */ },
  "namePattern":            "report-%d{yyyy-MM-dd_HH:mm:ss}",
  "type":                   "pdf",
  "useCurrentUserCredentials": true,
  "userId":                 "<uuid>"
}
```

`namePattern`: `%d{<SimpleDateFormat>}` tokens are replaced with the formatted current time in `timezone` (PE `ReportUtils.prepareReportName`); default `report-%d{yyyy-MM-dd_HH:mm:ss}`. `timewindow` is omitted from the render request when `useDashboardTimewindow=true`. Timewindow JSON shape (PE swagger-verbatim): `{displayValue, hideInterval, hideLastInterval, hideQuickInterval, hideAggregation, hideAggInterval, hideTimezone, selectedTab, realtime:{realtimeType, interval, timewindowMs, quickInterval}, history:{historyType, interval, timewindowMs, fixedTimewindow:{startTimeMs, endTimeMs}, quickInterval}, aggregation:{type, limit}}`.

### 2.4 Report-template configuration — R1 passthrough, R2 typed

PE's `ReportTemplateConfig` is a rich hierarchy: `AbstractReportTemplateConfig` → `PdfReportTemplateConfig` / `CsvReportTemplateConfig`, carrying `components: List<ReportComponent>` (`@JsonTypeInfo` polymorphic: `DashboardComponent`, `EntityTableComponent`, `AlarmTableComponent`, `TimeseriesTableComponent`, `TableWithLayoutComponent`, `SubReportComponent`, `RichTextComponent`, `HeadingComponent`, `DividerComponent`, `PageBreakComponent`, `ImageComponent`, `ErrorComponent`) plus `dataSources`, `entityAliases`, `style`, `headerFooter`, `timewindow`.

**R1 stores `ReportTemplate.configuration` as `JsonNode`** (jsonb column). The DB column is jsonb in both cycles, so **R2 types the field without a migration**. R1 needs only to locate the dashboard component to render it (§6.3), which it does by JSON navigation (`configuration.components[type=="DASHBOARD"]` → `dashboardId, state, timewindow, pageWidth`). The **complete PE schema** is transcribed in Appendix A so R2 (and the R1 template form) produce byte-compatible JSON. Rationale: typing ~40 component POJOs before a renderer consumes them is speculative (global rule #2); jsonb + a documented shape delivers the same forward-compatibility.

### 2.5 Enums / IDs (core-file touches)

- `EntityType.REPORT_TEMPLATE(108)` and `EntityType.REPORT(109)` — **PE-exact proto numbers** (verified free in CE; highest existing is `SCHEDULER_EVENT(103)`). Appended after `SCHEDULER_EVENT` with comma-swap.
- `EntityIdFactory` — two switch cases → `new ReportTemplateId(uuid)` / `new ReportId(uuid)`.
- `EntityId` — two `@DiscriminatorMapping` entries (Swagger polymorphic schema).
- `TbMsgType.generateReport` — **already present** (Scheduler ledger row S3).

---

## 3. Database Schema

Two tables, authored from the decompiled JPA entities (no PE `.sql` shipped in the jar). **All DDL goes into the Inferrix schema overlay** `dao/src/main/resources/sql/schema-inferrix.sql` (idempotent; applied on fresh install + every upgrade + DAO tests) — **no upstream schema file is touched, no new ledger row for the DDL** (the overlay mechanism, ledger §"Inferrix schema overlay", is the single DDL channel).

```sql
CREATE TABLE IF NOT EXISTS report_template (
    id uuid NOT NULL CONSTRAINT report_template_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid,
    name varchar(255) NOT NULL,
    format varchar(32) NOT NULL,
    type varchar(32) NOT NULL,
    description varchar,
    configuration jsonb,
    additional_info varchar,
    external_id uuid,
    version bigint DEFAULT 1,
    CONSTRAINT report_template_external_id_unq_key UNIQUE (tenant_id, external_id)
);

CREATE TABLE IF NOT EXISTS report (
    id uuid NOT NULL CONSTRAINT report_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid,
    template_id uuid,
    format varchar(32) NOT NULL,
    name varchar(255) NOT NULL,
    user_id uuid NOT NULL,
    data bytea
);

CREATE INDEX IF NOT EXISTS idx_report_tenant_id ON report(tenant_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_report_template_id ON report(tenant_id, template_id);
```

Notes:
- `report.data` (bytea) is written/read by **native queries** (PE `ReportRepository`): `UPDATE report SET data = :data WHERE id = :id` and `SELECT data FROM report WHERE id = :id` — the `data` column is intentionally **not** mapped on `ReportEntity` (bytes never load during list queries).
- `report_template.configuration` is jsonb (typed in R2, passthrough in R1). `report.format` / `report_template.format` store `TbReportFormat.name()`.
- No FKs (PE parity). `template_id` nullable (ad-hoc reports have no template).

---

## 4. DAO Layer

New files under `dao/src/main/java/org/thingsboard/server/dao/`, PE-identical paths. Both services register with the CE `EntityDaoService` machinery (so `BaseController.checkEntityId` and referenced-entity checks resolve the new types).

**Report** (`dao/report/`, `dao/sql/report/`, `dao/model/sql/`):
- `report/ReportService` (interface, `extends EntityDaoService`): `createReport(Report, byte[])`, `findReportById(TenantId, ReportId)`, `getReportData(TenantId, ReportId)`, `findReportInfoById`, `findReports(ReportInfoQuery)` (paged), `deleteReport(TenantId, ReportId)`, `deleteReportsByTenantId`, `deleteReportsByTenantIdAndCustomerId`. (Immutable — no update.)
- `report/DefaultReportService` — `createReport` = `reportDao.save(...)` then `reportDao.saveData(tenantId, reportId, bytes)` (separate native write). Publishes `DeleteEntityEvent` on delete.
- `report/ReportDao` (interface) + `sql/report/JpaReportDao` + `sql/report/ReportRepository` (`saveData`/`getDataById` native `@Modifying`/`@Query`), `sql/report/ReportInfoRepository` (customer-title join).
- `model/sql/AbstractReportEntity<T>` (`extends BaseSqlEntity<T>`; fields `tenantId, customerId, templateId, format (TbReportFormat), name, userId`; `data` **not** a field), `model/sql/ReportEntity` (`@Table("report")`), `model/sql/ReportInfoEntity` (+`customerTitle`).

**Report template** (`dao/report/`, `dao/sql/report/`, `dao/model/sql/`):
- `report/ReportTemplateService` (interface, `extends EntityDaoService`): `saveReportTemplate`, `findReportTemplateById`, `findReportTemplateInfoById`, `findReportTemplateInfos(ReportTemplateQuery)`, `findReportTemplatesByIds`, `deleteReportTemplate`, `deleteByTenantId`, `deleteByTenantIdAndCustomerId`.
- `report/BaseReportTemplateService` — `extends AbstractEntityService`; save publishes `SaveEntityEvent` + count-cache evict; constraint hook `report_template_external_id_unq_key`.
- `report/ReportTemplateDao` / `ReportTemplateInfoDao` (interfaces) + `sql/report/Jpa*` + `sql/report/ReportTemplateRepository` / `ReportTemplateInfoRepository`.
- `model/sql/AbstractReportTemplateEntity<T>` (`extends BaseVersionedEntity<T>`; `configuration` jsonb via `@Convert`/`@JdbcType` per the WL jsonb rule), `model/sql/ReportTemplateEntity` (`@Table("report_template")`), `model/sql/ReportTemplateInfoEntity` (+`customerTitle`).

**Validators** (`dao/service/validator/`): `ReportDataValidator` (name/format/tenant/user mandatory; **size check stubbed** in R1), `ReportTemplateDataValidator` (name/format/type/tenant mandatory; customer belongs to tenant else NULL_UUID; per-tenant entity-count limit).

**ModelConstants** (core-file touch): `report`/`report_template` column-name constants block (mirrors the Scheduler `ModelConstants` row S7).

---

## 5. Job/Task Framework Integration

The CE Job/Task framework (`common/data/job/*`, `application/.../service/job/*`) is **structurally identical to PE 4.2's** — verified: CE `Task<R>` carries `tenantId/jobId/key/retries/attempt` and PE's `ReportTask extends Task<ReportTaskResult>` slots in unchanged. **The framework auto-discovers everything from the enum** (`DefaultJobManager:89-90`): `jobProcessors` is `List<JobProcessor>` collected by `getType()`, and `taskProducers` is built by iterating `JobType.values()`. So adding `JobType.REPORT` + two `@Component` beans self-wires the queue producer and both dispatch maps — **no explicit registration touch beyond the enum + Jackson subtypes.**

Core-file touches (all in `common/data/job/`, mechanical — mirror the existing `DUMMY` entry):

| File | Change |
|---|---|
| `job/JobType.java` | Add `REPORT("Report generation")` constant. |
| `job/JobConfiguration.java` | `@JsonSubTypes` += `@Type(name="REPORT", value=ReportJobConfiguration.class)`. |
| `job/JobResult.java` | `@JsonSubTypes` += `@Type(name="REPORT", value=ReportJobResult.class)`. |
| `job/task/Task.java` | `@JsonSubTypes` += `@Type(name="REPORT", value=ReportTask.class)`. |
| `job/task/TaskResult.java` | `@JsonSubTypes` += `@Type(name="REPORT", value=ReportTaskResult.class)`. |

New domain (`common/data/job/`, `common/data/job/task/`, PE-only): `ReportJobConfiguration` (`reportTemplateId, userId, timezone, targets, notificationTemplateId, notificationRequests (List<NotificationRequest>), originator, ruleNode (RuleNode), outputTbMsgProto (String), queueName, schedulerEventInfo (EntityInfo)`), `ReportJobResult` (`report`), `job/task/ReportTask` (fields per decompiled source: `customerId, reportTemplateId, reportTemplateConfig (JsonNode in R1), timezone, userId, userOwnerId (EntityId), accessToken (String), accessTokenExpirationTs (long), originator (EntityId)`), `job/task/ReportTaskResult` (`report, error`; `success/failed/discarded` factories).

> **Risk (verify at implementation):** adding `JobType.REPORT` makes `DefaultJobManager` call `queueFactory.createTaskProducer(REPORT)` on **every** node at startup → provisions topic `tasks.report`. Confirm the in-memory + Kafka queue factories auto-provision without extra config (they do for `DUMMY`; REPORT follows the same path).

---

## 6. Report Engine & Renderer

**No new Maven module.** The engine + renderer live in `application/.../service/report/` (+ `.../report/render/`), the same convention the White Labeling and Scheduler ports used (they added code to `application`/`dao`/`common-data`, not new modules). Playwright-Java is a single dependency line in `application/pom.xml`.

> **Why not a `report` module (PE-mirror):** the discarded old `inferrix-reporting/` module hit a Maven **cyclic dependency** (commit `98959daa39`) because the report code needed `application`'s services while `application` needed the module for the boot jar. Keeping the engine inside `application` sidesteps that trap entirely and matches the established port convention. PE's `org.thingsboard.server.report.*` package layout is mirrored *within* `application`'s `service.report` package.

### 6.1 Interfaces (R2 swaps impls behind them)

- `service.report.ReportService` (iface): `ReportData generateReport(ReportTask)`, `TbReportFormat getFormat()`. R1 impl: `PdfReportService` (dashboard-component → Playwright PDF). CSV + non-dashboard component renderers → R2.
- `service.report.TbReportService`: `EnumMap<TbReportFormat, ReportService>` dispatch; `generateReport(ReportTask)` renders `ReportData`, builds the `Report` bean, persists via the dao `ReportService.createReport`. Also `generateTestReport(ReportTask)` (synchronous, `reports.generation_timeout_ms` guarded) for the test endpoints.
- **Server-side data-access context deferred to R2.** PE's `TbReportCtx` / `LocalTbReportCtxProvider` / `LocalReportDataService` exist to serve **server-rendered** components (entity/alarm/timeseries tables) queries under the task's `SecurityUser`. In R1 the dashboard renders **entirely in-browser** — the headless browser fetches all data itself over REST/WS using the token — so R1 needs **no** server-side query context. R1's `TbReportService` only loads the template (dao) and persists the `Report` (dao). This is added in R2 with the table renderers.
- `DefaultDashboardReportService` (in `application`, local interface): `generateReport(DashboardReportConfig, ...)` → Playwright renderer, for the on-demand download path. (PE lifts this interface into `rule-engine-api` for its rule node — that move is deferred to R2 with the rule node; R1 keeps it local, no upstream-module touch.)

### 6.2 Playwright renderer — `WebReportClient` replacement

Class `service.report.render.PlaywrightWebReportRenderer` (Inferrix; PE-identical method surface to `WebReportClient` so callers are unchanged). Behavior mirrors PE's HTTP contract, minus the HTTP hop:

- **Lifecycle:** one `BrowserContext` per render (crash isolation), closed in `finally` (kills the Chromium child tree). A `Semaphore` (config `reports.renderer.max_concurrent`, default 2) bounds parallel renders. A hard deadline (`reports.generation_timeout_ms`, default 120000) wraps the whole render; on timeout, force-close the context → `ThingsboardException`.
- **Chromium:** installed on the server out-of-jar (`playwright install chromium`), path via `reports.renderer.browser_path` (optional). Documented in the deploy runbook.
- **Recovery** (per the renderer decision): render failure/hang/crash → caught, logged ERROR with ids, `ReportData`-less result → the scheduler engine (already try/catches `executeReport`) re-arms the next occurrence; the job records FAILED. No zombie processes (per-render context close).

### 6.3 Browser protocol (reproduce PE's `tb-web-report` ↔ UI handshake)

The renderer drives the CE UI exactly as PE's Node service did (extracted from `ui-ngx-4.2.0PE.jar` `ReportService`):

1. **Expose callback before navigation:** Playwright `page.exposeFunction("postWebReportResult", ...)` — the UI polls every 20 ms for `window.postWebReportResult` and calls it once logged in and ready.
2. **Navigate:** `{baseUrl}/dashboards/{dashboardId}?reportView=true&accessToken=<JWT>` (`&state=<state>` when set). CE `auth.service.ts` already honors `?accessToken=` login (verified). `reportView=true` triggers the report-mode UI path (§12) that installs the readiness trackers.
3. **Command** via `page.evaluate` → `window.postMessage(JSON.stringify({type, data}), "*")`:
   - `openReport {accessToken|publicId, dashboardId, state?, reportTimewindow?, timeout?}` → UI navigates, applies the timewindow override, then `waitForLayoutReady`; replies `{success, pageHeight, error?}` where **`pageHeight = round(#gridster-child.scrollHeight) + round(.tb-dashboard-title.offsetHeight)`**.
   - `waitReportWidgets {timeout}` → `waitForWidgetsLoaded → waitForLayoutReady → waitForWidgetsLoaded`.
4. **Readiness conditions (exact, all polled at 10 ms; default 3000 ms):**
   - `waitForReportPage`: dashboard fired `onDashboardLoaded(widgetsCount)` **and** DOM check passes **and** ≥300 ms since that event. DOM check = `section.tb-dashboard-container gridster#gridster-child` present (not nested in `tb-widget-container`) and the count of `tb-widget > div.tb-widget-loading` ≥ `widgetsCount` and **every one carries class `!hidden`** (i.e., no widget still loading). Error string: `"Wait for report page timed out!"`.
   - `waitForWebsocketData`: every tracked WS `cmdId`/`subscriptionId` received data **and** ≥100 ms since the last subscribe. Error: `"Wait for websocket data timed out!"`.
   - `waitForWidgetsLoaded` (maps): the wait-for-map set is empty **and** ≥100 ms since last registration. Error: `"Wait for map tiles timed out!"`.
5. **Capture:** on `openReport` success, size the viewport to `pageHeight` (and `pageWidth` when the template supplies it) → `page.pdf()` for PDF, `page.screenshot()` for PNG/JPEG. Artifact name = `ReportUtils.prepareReportName(namePattern, now, timezone)`.
6. **Fallback ready channel** (if `postWebReportResult` never injected): the UI also `window.postMessage(JSON.stringify({type:"reportResult", data}), "*")` — the renderer listens on both.

> **CE gap (heaviest UI work):** none of these hooks exist in CE ui-ngx. §12 ports the `reportView` bootstrap, the `onDashboardLoaded` signal, the widget-loading `!hidden` convention, the websocket-data tracker, the map-tile tracker, and the `postWebReportResult`/`postMessage` plumbing.

---

## 7. Access-Token Mechanics

PE mints a **password-less user JWT** server-side; the same token both logs the headless browser in **and** authorizes the renderer's server-side data access (`LocalTbReportCtxProvider` parses it back to a `SecurityUser`).

Core-file touch — `SystemSecurityService` (interface + `DefaultSystemSecurityService` impl, `application/.../service/security/`):
- `String createUserAccessToken(TenantId tenantId, UserId userId)` — load user, build `SecurityUser`, `tokenFactory.createAccessJwtToken(securityUser).getToken()`. (`JwtTokenFactory.createAccessJwtToken` already exists in CE.)
- `String createUserAccessTokenFromPublicId(TenantId tenantId, String publicId)` — public-dashboard variant (used by the on-demand download of a public dashboard).

R1 only **mints** the token (for browser login). PE also **parses it back** to a `SecurityUser` in `LocalTbReportCtxProvider` for server-side queries — that consumer is R2 (§6.1), so R1 does not touch `parseAccessJwtToken`. Ledger row (2 methods on a TB-core interface + impl). No new security model — reuses CE's JWT factory and `SecurityUser`.

---

## 8. Scheduler Integration

The Scheduler port left `SchedulerReportExecutor` (`application/.../service/scheduler/report/`) with a `NoOpSchedulerReportExecutor` marked `@ConditionalOnMissingBean`. R1 contributes the real bean — **zero scheduler-engine changes**.

New: `application/.../service/scheduler/report/InferrixSchedulerReportExecutor implements SchedulerReportExecutor` (next to the seam interface). Its `executeReport(TenantId, SchedulerEvent)` reproduces decompiled `DefaultSchedulerService:356-361` behind the seam:

```java
ReportConfig cfg = JacksonUtil.treeToValue(event.getConfiguration(), ReportConfig.class);
ReportJobConfiguration jobCfg = ReportJobConfiguration.builder()
    .reportTemplateId(cfg.getReportTemplateId())
    .userId(cfg.getUserId())
    .timezone(cfg.getTimezone())
    .targets(cfg.getTargets())
    .notificationTemplateId(cfg.getNotificationTemplateId())
    .schedulerEventInfo(new EntityInfo(event.getId(), event.getName()))
    .build();
// key = the scheduler event id (stable per event); the Job ctor assigns tasksKey itself
jobManager.submitJob(new Job(tenantId, JobType.REPORT, event.getId().toString(), event.getId(), jobCfg));
```

Deps: `JobManager` (CE bean, `rule-engine-api`). Logging: `log.info` on entry (tenant, event, templateId), `log.error` on parse/submit failure (caught by the engine's per-event try/catch — a failing report never stalls the scheduler). This is the sole integration point; the Scheduler's `generateReport` type becomes live the moment this bean is on the classpath.

### 8.1 Job → Task → render → persist → deliver

`ReportJobProcessor implements JobProcessor` (`getType()==REPORT`), `application/.../service/report/`:
- `process(Job, Consumer<Task>)`: load `ReportTemplate` + `User`; mint JWT via `systemSecurityService.createUserAccessToken(tenantId, userId)`; resolve `userOwnerId` = **customerId if present else tenantId** (PE's `OwnersCacheService` replaced); emit **one** `ReportTask` (all §5 fields). API-usage gate **stubbed** (always enabled).
- `onJobFinished(Job)`: on COMPLETED with `targets` + `notificationTemplateId` → build a `NotificationRequest` with `info = ReportGeneratedNotificationInfo{tenantId, customerId, reportFormat, reportName, userId}`, `NotificationRequestConfig.reports = List.of(reportId)`, dispatch via `NotificationCenter.processNotificationRequest`. (V2 rule-node `outputTbMsgProto` push → R2.)

`ReportTaskProcessor extends TaskProcessor<ReportTask, ReportTaskResult>` (`application/.../service/report/task/`; timeout `reports.generation_timeout_ms`): `process(task)` → `TbReportService.generateReport(task)` → `ReportTaskResult.success(task, report)`.

---

## 9. Delivery (Notification Center + email attachments)

R1 delivers via the CE Notification Center's email channel, extended to attach report bytes (PE parity). Core-file touches (mail + notification, 5 files):

| File | Change |
|---|---|
| `rule-engine-api/.../TbEmail.java` | Add `List<ReportId> reports` (and `List<BlobEntityId> attachments` — reserved, unused in R1) to the immutable bean + builder. |
| `application/.../service/mail/DefaultMailService.java` | In `send(...)`: `multipart` also true when `reports` non-empty; for each `ReportId` → `reportService.findReportById` + `getReportData` → `helper.addAttachment(report.getName(), new ByteArrayDataSource(bytes, report.getFormat().getContentType()))`. |
| `application/.../service/notification/channels/EmailNotificationChannel.java` | Pass `ctx.getRequest().getConfig().getReports()` into `TbEmail.builder()....reports(...)`. |
| `common/data/.../notification/NotificationRequestConfig.java` | Add `List<ReportId> reports`. |
| `common/data/.../notification/info/*` | Register `ReportGeneratedNotificationInfo` as a `NotificationInfo` Jackson subtype (new PE-only info class + one subtype entry). |

`LimitedApi.EMAILS` rate limiting already applies in `DefaultMailService` — unchanged.

---

## 10. REST API

New controllers (additive, own `@RestController`s at PE-verbatim paths), `application/.../controller/`:

**`ReportController`** — `@RequestMapping("/api/v2")`, history + template-report ops:

| Method + path | Auth | Body / Returns |
|---|---|---|
| `POST /report` (multipart `file` + `info`) | TA, CU | create/import a stored `Report` |
| `GET /report/{reportId}` | TA | `Report` |
| `GET /report/{reportId}/download` | TA | bytes + `Content-Disposition: attachment;filename="…"` + `x-filename` |
| `DELETE /report/{reportId}` | TA | 200 |
| `GET /reports` | TA | `PageData<Report>` (`pageSize,page,textSearch?,sortProperty?,sortOrder?`) |
| `GET /reportInfos` | TA | `PageData<ReportInfo>` (`reportTemplateId?, userId?, includeCustomers?`) |
| `GET /reportInfos/all` | TA | `PageData<ReportInfo>` (all customers) |
| `POST /report/test` | TA | body `ReportRequest` → bytes (synchronous, timeout-guarded) |
| `POST /report/request` | TA | body `ReportRequest` (templateId required) → `Job` |

**`DashboardReportController`** — `@RequestMapping("/api")`, on-demand dashboard capture:

| Method + path | Auth | Body / Returns |
|---|---|---|
| `POST /report/{dashboardId}/download` | TA, CU | body `{type, timezone, timewindow?, state?}`; async `DeferredResult` → bytes + `Content-Disposition` + `x-filename`; name = `{dashboardTitle}-yyyy-MM-dd_HH:mm:ss` |
| `POST /report/test?reportsServerEndpointUrl=` | TA, CU | body `DashboardReportConfig` → bytes (endpoint param ignored — in-process renderer; kept for UI-payload parity) |

**`ReportTemplateController`** — `@RequestMapping("/api")`, template CRUD:

| Method + path | Auth |
|---|---|
| `POST /reportTemplate` | TA |
| `GET /reportTemplate/{id}`, `GET /reportTemplate/info/{id}` | TA, CU |
| `DELETE /reportTemplate/{id}` | TA |
| `GET /reportTemplateInfos/all?typeList&formatList&includeCustomers` | TA |
| `GET /reportTemplates?reportTemplateIds=a,b` | TA |

All endpoints `@PreAuthorize` gated + per-entity `checkEntityId(id, Operation)`; audit-logged via a `TbReportTemplateService` audit wrapper (mirrors the Scheduler `TbSchedulerService`). `BaseController` core touch: `reportService` + `reportTemplateService` fields, `checkReportId`/`checkReportTemplateId` helpers, two `checkEntityId` switch arms.

---

## 11. Security

CE permission model (WL/Scheduler-precedent adaptation of PE's RBAC), `application/.../service/security/permission/`:

- `Resource.REPORT(EntityType.REPORT)`, `Resource.REPORT_TEMPLATE(EntityType.REPORT_TEMPLATE)`.
- `TenantAdminPermissions`: standard tenant-entity checker for both (full CRUD on tenant's reports/templates).
- `CustomerUserPermissions`: read + on-demand download of own-customer reports/dashboards; a dedicated checker granting the operations the report controllers exercise (READ + CREATE for on-demand download; scoped to `customerId`), following the Scheduler S14 precedent (do **not** broaden the shared `customerEntityPermissionChecker`).
- SYS_ADMIN: no entry (tenant-level feature).
- The minted JWT is that of the configured `userId`; the headless browser logs in as that user, so a report can never render data the configured user couldn't see (R1's data access is entirely in-browser under this identity; R2's server-side query context reuses the same principal).

---

## 12. UI

New Angular under `ui-ngx/src/app/modules/home/pages/report/` (report-templates + report-history pages) + `ui-ngx/src/app/core/http/report.service.ts` + `ui-ngx/src/app/shared/models/report.models.ts` (additive), plus the **reportView render-mode hooks** (core-file touches — the heaviest UI merge risk):

**Render-mode hooks (subsystem A/B browser protocol, §6.3):**
- `dashboard-page.component.ts` — emit `onDashboardLoaded(widgetsCount)` at the existing lifecycle points (mirror the `mobileService.onDashboardLoaded` call sites, `:419`/`:1124`); apply the `reportTimewindow` override when in report mode.
- Widget component — carry the `!hidden` class on `div.tb-widget-loading` once a widget's data settles (the readiness DOM convention).
- Websocket service — in `reportView` mode, track `cmdId`/`subscriptionId` → data-received (the `waitForWebsocketData` signal).
- Map widgets — register/complete against the wait-for-map set; root gets class `tb-web-report`.
- A `ReportService` (new, Inferrix) — installs the `window` `message` listener, implements `openReport`/`waitReportWidgets`/`clearReport`, computes `pageHeight`, calls `window.postWebReportResult`, and the `postMessage` fallback. Bootstrapped when `getQueryParam("reportView")==="true"`.

**Product UI:**
- **Report templates page** (`/reports/templates` or under features) — list + create/edit dialog. R1 dialog: name, format (PDF), type (REPORT), description, and a **dashboard-component** editor (dashboard picker, state, timewindow, page width) producing the Appendix-A JSON. (Full designer → R2.)
- **Report history page** (`/reports/history`) — CE `entities-table` over `/api/v2/reportInfos`; columns: created time, name, format, template, customer; row actions: download, delete.
- **On-demand download** — a "Download report" action on the dashboard toolbar → dialog (type PDF/PNG/JPEG, timezone, timewindow, state) → `POST /api/report/{dashboardId}/download` → browser download. (Toolbar core-file touch.)
- **Scheduler `generateReport` form** — replace the raw-JSON placeholder (Scheduler `scheduler-event-config.component.ts:92` routes `generateReport`→`raw`) with a native form: report-template picker, user picker, timezone, notification targets multiselect, notification template picker → writes the §2.3 `ReportConfig` JSON. `generateReport` stays hidden from CUSTOMER_USER (Scheduler `scheduler-event-dialog.component.ts:69`).

**Core-file touches (FE):** `menu.models.ts` (Reports menu entry — on `inferrix-release-4.3` this makes it **triple-touched: WL row 13, scheduler S19, this**; the old master-branch reporting row does not exist on this branch; top merge risk), `entity-type.models.ts` (REPORT/REPORT_TEMPLATE translations + resources), `home-pages.module.ts` (module export), `public-api.ts` (report service export), `locale.constant-en_US.json` (report i18n section — keys extracted from the PE bundle), `dashboard` toolbar component (download action), `scheduler-event-config.component.*` (native form — Inferrix-owned file, not a ledger row), and `features-routing.module.ts` / a new `report-routing.module.ts` for the pages.

---

## 13. Configuration (`thingsboard.yml`, core-file touch)

```yaml
reports:
  # In-process Playwright renderer (replaces PE's external tb-web-report server)
  renderer:
    max_concurrent: "${REPORTS_RENDERER_MAX_CONCURRENT:2}"
    browser_path: "${REPORTS_RENDERER_BROWSER_PATH:}"   # empty = Playwright-managed Chromium
    base_url: "${REPORTS_RENDERER_BASE_URL:http://localhost:8080}"   # TB UI URL reachable by the headless browser
  generation_timeout_ms: "${REPORTS_GENERATION_TIMEOUT_MS:120000}"
  test_report_pool_size: "${REPORTS_TEST_REPORT_POOL_SIZE:12}"
```

`reports.scheduler.min_interval` is **already** relocated to `scheduler.min_interval` (Scheduler port). PE's `reports.web_report.base_url` / `max_response_size` / `rate_limits.*` are dropped (external-server / rate-limit concepts not in R1). Audit mask entries for `report` / `report_template` added to `audit-log.logging-level.mask` (default `W`).

---

## 14. Error Handling

- **Renderer:** per-render `BrowserContext` + hard timeout + semaphore (§6.2). Any failure → ERROR log with ids → job FAILED → next scheduled occurrence retries. No zombie Chromium.
- **Ready-signal timeouts:** the three PE error strings surfaced verbatim (`"Wait for report page timed out!"`, etc.) so operators can distinguish a slow dashboard from a crash.
- **Scheduler seam:** `executeReport` failures caught by the engine's existing per-event try/catch — a broken report config never stalls the scheduler.
- **Job/Task:** framework records FAILED with the `TaskResult.error`; `onJobFinished` on FAILED updates any pre-created notification request to SENT with the error (PE parity).
- **Delivery:** missing/renderless report → WARN, skip attachment; mail send failure → the notification channel's standard error path.
- **Validators** reject malformed templates/reports at save (400).
- **Immutability:** `Report` update attempts → `DataValidationException` ("Report can't be updated").

---

## 15. Deviations from PE (R1)

| # | PE | Ours (R1) | Why |
|---|---|---|---|
| 1 | External `tb-web-report` microservice + `WebReportClient` HTTP | In-process Playwright-Java behind the same interface | One deployable; renderer decision |
| 2 | `OwnersCacheService.getOwner(userId)` for `userOwnerId` | customerId else tenantId | CE has no entity-group ownership |
| 3 | `ApiUsageState.isReportCreationEnabled`, `GENERATED_REPORTS_COUNT`, `LimitedApi.REPORTS`, `validateReportSize` | stubbed permissive | avoids ~5 tb-core enum patches; no product requirement in R1 |
| 4 | `BlobEntity` dashboard-report store + `attachments` rule metadata | direct byte attach; `attachments` field reserved-unused | legacy store not needed; rule nodes are R2 |
| 5 | `ReportTemplateConfig` typed hierarchy (~40 classes) | `JsonNode` passthrough (jsonb) | typing before a consuming renderer is speculative; R2 types it with no migration |
| 6 | All component renderers + Thymeleaf/iText PDF + CSV | dashboard component → Playwright PDF only | R2 (largest PE code body) |
| 7 | `generate dashboard report` / `generate report` rule nodes | not ported | R2 |
| 8 | `RemoteTbReportCtxProvider`, `RemoteReportDataService`, `TbReportConsumerService`, report queue proto, `@TbReportComponent` | dropped | in-process monolith only |
| 9 | `/scheduledReports`, `ScheduledReportInfo`, 3 DAO methods | dropped | Scheduler port already dropped the coupling |
| 10 | `ReportTemplateExportService`/`ImportService`, edge sync | dropped | scope |
| 11 | PE RBAC (group permissions) | CE `Resource` + tenant/customer checkers | CE permission architecture |
| 12 | Sparse logging | comprehensive flow logging every ported class (per commit `3ba81c0789` convention); renderer logs per render + verbatim ready-timeout strings | debuggability requirement |
| 13 | `reports.web_report.*`, `reports.rate_limits.*`, `reports.scheduler.*` yml | `reports.renderer.*` + `generation_timeout_ms` + `test_report_pool_size`; scheduler min-interval already relocated | in-process renderer knobs |

Everything else — entity/DTO field names + JSON shapes, DDL columns, DAO queries, REST paths/payloads, the browser handshake protocol, notification-delivery flow, proto entity numbers (108/109) — is PE-identical.

---

## 16. TB-Core Files Modified (ledger preview — new `## Feature: Reporting` section)

**Backend (~18):** `common/data/.../EntityType.java`, `id/EntityIdFactory.java`, `id/EntityId.java`, `job/JobType.java`, `job/JobConfiguration.java`, `job/JobResult.java`, `job/task/Task.java`, `job/task/TaskResult.java`, `notification/NotificationRequestConfig.java`, `notification/info/` (subtype registry), `dao/.../ModelConstants.java`, `rule-engine-api/.../TbEmail.java`, `application/.../service/mail/DefaultMailService.java`, `application/.../service/notification/channels/EmailNotificationChannel.java`, `application/.../service/security/permission/Resource.java` + `TenantAdminPermissions.java` + `CustomerUserPermissions.java`, `application/.../service/security/DefaultSystemSecurityService.java` (+iface), `application/.../controller/BaseController.java`, `application/src/main/resources/thingsboard.yml`, `application/pom.xml` (+Playwright-Java dependency). (No new Maven module, no root-pom touch — §6.)

**Frontend (~7):** `menu.models.ts`, `entity-type.models.ts`, `home-pages.module.ts`, `core/http/public-api.ts`, `locale.constant-en_US.json`, dashboard toolbar component, `features-routing.module.ts`. Plus render-mode hooks in `dashboard-page.component.ts`, the widget component, and the websocket service (each a ledger row — the highest merge-risk group).

**DDL:** `report` + `report_template` → `schema-inferrix.sql` overlay (no upstream schema touch, no ledger row).

Each row gets insertion anchors + merge-recovery notes in the same commit that touches the file. The Bucket-B merge-guard grep gains `report_template|ReportTemplate|JobType.REPORT|EntityType.REPORT`. New additive files carry the Apache-2.0 header, owner **The Inferrix Authors**.

---

## 17. Testing Strategy (TDD)

Backend-first, red-green per work package, PE-observed semantics encoded as tests before porting each class.

1. **Domain** (`common/data`): JSON round-trips for `ReportConfig`, `DashboardReportConfig`, `ReportJobConfiguration`/`ReportTask`/`ReportTaskResult` (Jackson subtype resolution by `jobType`/`type`), `TbReportFormat` content-type/extension.
2. **DAO** (CE JPA harness): `report_template` CRUD + jsonb config round-trip + unique-external-id; `report` create + `saveData`/`getReportData` bytea round-trip (incl. multi-MB) + immutability + paged/text-search/customer-join queries + delete cascades.
3. **Validators**: every reject path.
4. **Job/Task** (mocked): `ReportJobProcessor.process` emits exactly one `ReportTask` with correct fields + minted token; `onJobFinished` builds the notification request with `reports=[reportId]`; `ReportTaskProcessor` success/timeout.
5. **Seam executor** (mocked `JobManager`): `generateReport` config → correct `Job`/`ReportJobConfiguration`; parse failure caught + logged.
6. **Renderer** (integration, headless Chromium against the dev UI): a known dashboard → non-empty PDF; ready-signal timeout path → verbatim error; semaphore bounds concurrency; per-render context closed (no leaked process).
7. **Mail/notification** (mocked `MimeMessageHelper`): `reports` non-empty → `addAttachment` called with report name + content type; `EmailNotificationChannel` passes `reports` through.
8. **REST** (CE `AbstractControllerTest`): all endpoints, permission matrix (TA full / CU scoped download / SYS_ADMIN 403), download headers (`Content-Disposition`, `x-filename`), audit entries.
9. **UI:** `yarn build` type-check (Node 22) + runtime walkthrough — create a dashboard template, schedule a `generateReport` event, confirm the emailed PDF; on-demand download; history list/download/delete.

**Definition of done:** new tests green; `mvn install` green on touched modules (`common/data`, `dao`, `application`) incl. `license:check`; `yarn build` green; **E2E:** a scheduled `generateReport` fires → dashboard renders → PDF persisted → email with PDF attachment received; on-demand dashboard download works; history + delete work; enable/disable of the scheduler event takes effect without restart.

---

## 18. Future Phases

**R2 — template engine (own spec):** typed `ReportTemplateConfig` hierarchy (Appendix A) replacing the R1 `JsonNode`; Thymeleaf + flying-saucer/iText PDF renderer; all component renderers (entity/alarm/timeseries/table-with-layout, rich text, heading, divider, page break, image, sub-report); CSV format + `CsvReportService`; full report-template **designer** UI; `generate dashboard report` + `generate report` (V2) rule nodes (incl. `outputTbMsgProto` push + `attachments` metadata channel). Swaps renderer internals behind R1 interfaces — no schema/API change.

**Later (optional, non-PE-parity or deferred):** microservice render mode (`Remote*` + report queue); `BlobEntity` attachment channel; report-template VC export/import; edge sync; API-usage report limits + rate limits; `/scheduledReports` view.

---

## Appendix A — Full PE `ReportTemplateConfig` schema (for R1 form fidelity + R2 typing)

`AbstractReportTemplateConfig` → `PdfReportTemplateConfig` | `CsvReportTemplateConfig`. Common: `components: List<ReportComponent>`, `dataSources: List<DataSource>`, `entityAliases`, `style`, `headerFooter: HeaderFooter`, `timewindow`. `ReportComponent` is `@JsonTypeInfo(property="type")` polymorphic:

| `type` | Class | Key fields |
|---|---|---|
| `DASHBOARD` | `DashboardComponent` | `dashboardId, state, timewindow, pageWidth` — **the only component R1 renders** |
| `ENTITY_TABLE` | `EntityTableComponent` | `dataSource, columns (List<ColumnSettings>), sortOrder, pageLink` |
| `ALARM_TABLE` | `AlarmTableComponent` | `alarmFilter (AlarmFilterConfig), columns, sortOrder` |
| `TIMESERIES_TABLE` | `TimeseriesTableComponent` | `dataKeys (List<DataKey>), timewindow, columns` |
| `TABLE_WITH_LAYOUT` | `TableWithLayoutComponent` | table + `LayoutReportComponent` cells |
| `SUB_REPORT` | `SubReportComponent` | `reportTemplateId` (a SUB_REPORT-typed template) |
| `RICH_TEXT` | `RichTextComponent` | `content (html)` |
| `HEADING` | `HeadingComponent` | `text, level` |
| `DIVIDER` | `DividerComponent` | style |
| `PAGE_BREAK` | `PageBreakComponent` | — |
| `IMAGE` | `ImageComponent` | `imageUrl / resourceId` |
| `ERROR` | `ErrorComponent` | `message` (default/unknown component) |

Supporting: `DataSource{type (DataSourceType), entityAliasId, dataKeys}`, `DataKey{name, type, label, settings (DataKeySettings)}`, `ColumnSettings`, `CellSettings`, `TableSortOrder{key, direction}`, `AlarmFilterConfig`, `HeaderFooter`, `EntityAlias`, `Filter`. Exact field lists per component come from `data-4.2.0PE.jar!org/thingsboard/server/common/data/report/configuration/**` (retained decompiled at the analysis scratchpad) — transcribe when R2 types them.

> **R1 requirement:** the report-template form's dashboard-component editor MUST emit JSON that R2's typed `DashboardComponent` reads back losslessly: `{"type":"DASHBOARD","dashboardId":"…","state":"…","timewindow":{…},"pageWidth":…}` inside `configuration.components[]`, with `configuration` carrying `type` (`PDF`) at minimum. Keep unknown keys (don't strip) so PE-authored templates survive a round-trip.
