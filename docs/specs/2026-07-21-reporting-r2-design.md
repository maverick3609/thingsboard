# Reporting R2 (Template Engine) — Design Specification

**Date:** 2026-07-21
**Status:** Draft (design) — awaiting review before plan/implement
**Source:** ThingsBoard PE **4.2.0** (decompiled from `docs/jars/` — `report-4.2.0PE.jar`, `data-4.2.0PE.jar`, `rule-engine-components-4.2.0PE.jar`, `rule-engine-api-4.2.0PE.jar`, `ui-ngx-4.2.0PE.jar`; javap + CFR). Four `pe-feature-analyzer` passes, 2026-07-21.
**Target:** Inferrix Cortex on ThingsBoard CE 4.3.1.x / 4.4.0-SNAPSHOT, branch `inferrix-release-4.3`
**Builds on:** R1 (complete — `docs/specs/2026-07-17-reporting-design.md`, ledger rows R1–R39). R2 **swaps renderer internals and types the config behind the R1 interfaces — no DB schema change, no REST/API change.**
**Approach:** In-core PE-mirror; classes at PE-analogous paths inside `application`/`common-data`/`rule-engine-components`; PE-identical JSON contracts; every core-file touch tracked in `INFERRIX-PATCHES.md`. Renderer stays **opt-in** (`@ConditionalOnProperty(reports.renderer.enabled)`, default off) — same boot discipline as R1.

---

## 1. Overview

R1 shipped the full pipeline for **dashboard-sourced** reports: schedule → Job/Task → in-browser Playwright render → persist `Report` → deliver by email. The `ReportTemplate.configuration` document was stored as passthrough `JsonNode`, and only the **DASHBOARD** component was rendered (whole dashboard → `page.pdf()`).

R2 makes `ReportTemplate` a real **multi-component document engine**, matching PE:

- **Typed `ReportTemplateConfig`** replacing the R1 `JsonNode` passthrough (~55 new POJOs; jsonb column unchanged → no migration).
- **Server-side HTML→PDF rendering** (Thymeleaf + flying-saucer + openpdf) for all non-dashboard components: entity / alarm / timeseries tables, rich text, heading, divider, page break, image, sub-report. The DASHBOARD component is rendered by **screenshotting it in the headless browser and embedding the PNG** into the server-assembled HTML (base64).
- **CSV** format (`CsvReportService`) — table components only.
- **Server-side data-access context** (`TbReportCtx` / `ReportDataService`) that R1 explicitly deferred — table renderers query entity/alarm/timeseries data server-side under the task's `SecurityUser`.
- **`generate report` (V2) rule node** + the `outputTbMsgProto` push-back that closes the async loop into the rule engine.
- **Full report-template designer UI** (~40 net-new Angular components) producing the typed config JSON.

### 1.1 Architectural inversion vs R1 (the single most important fact)

R1 renders the **whole dashboard in the browser** and takes the PDF from the browser. **PE (and R2) render the document body server-side** (Thymeleaf → flying-saucer → openpdf → PDF bytes) and use the browser **only to screenshot the DASHBOARD component**, which is then embedded as an image. These are two different rendering paths that **coexist**:

| Path | Renderer | Used by | Status |
|---|---|---|---|
| On-demand dashboard download (`/api/report/{dashboardId}/download`), `DashboardReportConfig` | R1 Playwright whole-dashboard → PDF/PNG/JPEG | dashboard reports (subsystem A) | **R1 — unchanged in R2** |
| Template report body (`ReportTemplate` → PDF/CSV) | **R2 server-side HTML→PDF engine** | template reports (subsystem B) | **new in R2** |
| DASHBOARD *component* inside a template | R2 calls the R1 Playwright renderer for a **raw PNG**, base64-embeds it | template reports containing a DASHBOARD component | **new adapter over R1 renderer** |

Consequence: R2 does **not** discard the R1 renderer — it reuses it as the DASHBOARD-component image source (needs a raw-PNG entry point, §5.4).

### 1.2 PE source map (where each piece lives in the jars)

| Area | PE jar / package |
|---|---|
| Typed config (~55 classes) | `data-4.2.0PE.jar` → `org.thingsboard.server.common.data.report.configuration.**` |
| Render engine + component renderers + Thymeleaf templates + fonts | `report-4.2.0PE.jar` → `org.thingsboard.server.report.*` (distinct package from R1's `service.report.*` — no FQCN collision) |
| Server-side query ctx + datasource | `report-4.2.0PE.jar` → `report.context.*`, `report.datasource.*`; local impls stubbed in boot jar `service/report/` |
| Rule nodes | `rule-engine-components-4.2.0PE.jar` → `org.thingsboard.rule.engine.report.*` |
| `DashboardReportService` iface (V1 only) | `rule-engine-api-4.2.0PE.jar` |
| Designer UI | `ui-ngx-4.2.0PE.jar` (built bundle — i18n + selectors recoverable, no source) |

### 1.3 Excluded (carried from R1 §Excluded, still out)

Microservice/distributed report mode (`Remote*` ctx/data services, report queue proto, `@TbReportComponent`); edge sync + VC export/import; API-usage report limits (stay permissive-stubbed); entity-group ownership (`OwnersCacheService`). **New R2 exclusion decisions in §15 (Open Decisions).**

---

## 2. Data Model — typed `ReportTemplateConfig`

R1's `ReportTemplate.configuration : JsonNode` becomes a typed tree. The **jsonb column is unchanged** — typing is at the **domain layer only**; the entity keeps `JsonNode` + the existing `@Convert(JsonConverter)` + `@JdbcType(PostgreSQLJsonPGObjectJsonbType)` (no DDL, no migration). Domain↔entity mapping does `treeToValue` / `valueToTree`.

### 2.1 Two polymorphic axes (exact discriminators)

Root config — `ReportTemplateConfig` (iface):
```
@JsonTypeInfo(use=NAME, property="format")          // As.PROPERTY, NO defaultImpl in PE
@JsonSubTypes({ PdfReportTemplateConfig="PDF", CsvReportTemplateConfig="CSV" })
@JsonIgnoreProperties(ignoreUnknown=true)
```
Component — `ReportComponent` (iface):
```
@JsonTypeInfo(use=NAME, include=As.EXISTING_PROPERTY, property="type")   // reads getType()
```
`ReportComponentType` (11, serialized by name): `HEADING, RICH_TEXT, ENTITY_TABLE, TIME_SERIES_TABLE, ALARM_TABLE, DASHBOARD, IMAGE, SUB_REPORT, PAGE_BREAK, ERROR, DIVIDER`.
`DataKeySettings` (iface): `@JsonTypeInfo(property="type", defaultImpl=DefaultDataKeySettings)`, subtypes `COLUMN`→`ColumnSettings`, `DEFAULT`→`DefaultDataKeySettings`.

> **Appendix-A corrections** (R1 spec was slightly off): the timeseries discriminator is **`TIME_SERIES_TABLE`** (not `TIMESERIES_TABLE`); there is **no `TABLE_WITH_LAYOUT` component** — `TableWithLayoutReportComponent` is an interface the three concrete table components implement.

### 2.2 Component field lists (inheritance flattened → exact JSON keys)

Inherited groups: **DATA** = `dataSources: List<DataSource>`; **LAYOUT** = `margins, paddings: Insets`, `background: String`, `borderWidth, borderRadius: Integer`, `borderColor: String`; **TABLE** = `showTableHeading: boolean`, `tableHeading: Heading`, `tableSortOrder: TableSortOrder`; **IMAGE-SIZE** = `widthType: ImageWidthType`, `customWidth: int`, `alignment: ImageAlignment`.

| type | class → super | own fields |
|---|---|---|
| DASHBOARD | `DashboardComponent → AbstractImageComponent` | `config: DashboardReportConfig` (+ IMAGE-SIZE, LAYOUT, DATA) |
| ENTITY_TABLE | `EntityTableComponent → AbstractTableWithLayoutReportComponent` | — (TABLE, LAYOUT, DATA) |
| ALARM_TABLE | `AlarmTableComponent → …` | `alarmSource: DataSource`, `timewindow: TimeWindowConfiguration` (TABLE, LAYOUT, DATA) |
| TIME_SERIES_TABLE | `TimeseriesTableComponent → …` | `timewindow`, `showTimestamp: boolean`, `timestampLabel`, `timestampPattern: String`, `timestampColumnSettings: ColumnSettings` (TABLE, LAYOUT, DATA) |
| HEADING | `HeadingComponent → AbstractDataWithLayoutReportComponent` | `value: String`, `font: Font`, `color`, `textAlignment`, `verticalAlignment`, `height: Integer` (LAYOUT, DATA) |
| RICH_TEXT | `RichTextComponent → …` | `value: String` (LAYOUT, DATA) |
| DIVIDER | `DividerComponent → AbstractLayoutReportComponent` | `length: BorderLength`, `borderType: BorderType`, `widthPx: Integer`, `color` (LAYOUT) |
| IMAGE | `ImageComponent → AbstractImageComponent` | `sourceType: ImageSourceType`, `imageUrl: String` (IMAGE-SIZE, LAYOUT, DATA) |
| SUB_REPORT | `SubReportComponent → AbstractDataReportComponent` | `templateId: ReportTemplateId`, `avoidPageBreakInside: boolean` (DATA) |
| PAGE_BREAK | `PageBreakComponent implements ReportComponent` | — |
| ERROR | `ErrorComponent implements ReportComponent` | `errorMessage: String`, `exception: Exception` ⚠ **must `@JsonIgnore` the raw `Exception`** or it won't round-trip |

Root config fields — `AbstractReportTemplateConfig`: `namePattern, timeDataPattern: String`, `entityAliases: List<EntityAlias>`, `filters: List<Filter>`, `components: List<ReportComponent>` (`@NotNull`). `PdfReportTemplateConfig` adds: `pageSize: PageSize`, `pageOrientation: PageOrientation`, `pageMargins: Insets`, `pageBackground: String`, `header, footer: HeaderFooter`. `CsvReportTemplateConfig` adds nothing.

### 2.3 Value objects & enums

Value objects: `DataSource{type: DataSourceType, deviceId, entityAliasId, filterId, dataKeys: List<DataKey>, latestDataKeys: List<DataKey>, alarmFilterConfig}`; `DataKey{name, type, label, decimals, units, aggregationType: kv.Aggregation, timewindow, usePostProcessing, postFuncBody, settings: DataKeySettings}`; `ColumnSettings{columnWidth, header: CellSettings, cell: CellSettings}`; `CellSettings{font, color, backgroundColor, textAlignment, verticalAlignment}`; `TableSortOrder{column, direction: {ASC,DESC}}`; `AlarmFilterConfig{typeList, statusList: List<AlarmSearchStatus>, severityList: List<AlarmSeverity>, assigneeId: UserId, searchPropagatedAlarms}`; `HeaderFooter{enabled, components: List<ReportComponent>, firstPage: HeaderFooter}` (recursive); `EntityAlias{id, alias, filter: query.EntityFilter}`; `Filter{id, filter, keyFilters: List<query.KeyFilter>}`; `style.Font{size: Float, weight, style, family}`; `style.Insets{left,right,top,bottom: int}`; `style.Heading{text, font, color, textAlignment, verticalAlignment, height}`; `timewindow.{TimeWindowConfiguration, History, FixedTimeWindow, AggregationConfiguration, Interval}`.

Enums — `@JsonValue` label-based: `DataSourceType{device,entity,entityCount,alarmCount}`, `FontStyle{normal,italic}`, `FontWeight{normal,bold}`, `TextAlignment{center,right,left,justify}`, `VerticalAlignment{bottom,top,middle}`, `BorderType{solid,dashed,dotted}`, `ImageAlignment{left,center,right}`, `ImageSourceType{image,entityKey}`, `ImageWidthType{fitWidth,original,custom}`. Name-based: `BorderLength{LONG,SHORT}`, `PageOrientation{PORTRAIT,LANDSCAPE}`, `PageSize{LETTER,LEGAL,TABLOID}` (carry int w/h, serialize by name), `DataKeySettingsType{COLUMN,DEFAULT}`, `QuickTimeInterval` (24 values).

⚠ **`timewindow.Interval` has custom `@JsonSerialize`/`@JsonDeserialize` codecs** — both must be ported or `history.interval` won't round-trip.

External CE types (already present, no port): `kv.Aggregation`, `query.EntityFilter`, `query.KeyFilter`, `alarm.AlarmSearchStatus/AlarmSeverity`, `id.UserId/ReportTemplateId`, `dashboardreport.DashboardReportConfig` (R1).

### 2.4 Migration safety — TWO real incompatibilities (see §15 Decision D1)

The jsonb column is unchanged so raw rows survive, but **R1's stored shape does not deserialize losslessly into PE's typed POJOs**:

1. **DASHBOARD nesting.** PE `DashboardComponent` has one field `config: DashboardReportConfig` → JSON `{"type":"DASHBOARD","config":{"dashboardId":…,"state":…,"timewindow":…}}`. **R1 writes it flat**: `{"type":"DASHBOARD","dashboardId":…,"state":…,"timewindow":…,"pageWidth":…}`. Under `@JsonIgnoreProperties(ignoreUnknown=true)` the flat keys are **silently dropped** → `config==null` → renderer loses the dashboard. Also `pageWidth` **does not exist** in the typed model (dashboard sizing is `widthType`/`customWidth`).
2. **Root discriminator key.** PE keys the root on `format` with **no `defaultImpl`**. R1 guidance put `type:"PDF"` at the root. A stored root without `format` throws "missing type id".

Otherwise lossless: every polymorphic node + big value object carries `@JsonIgnoreProperties(ignoreUnknown=true)`; `DataKeySettings` has a safe `defaultImpl`.

---

## 3. Database Schema

**No column change.** `report_template.configuration` stays jsonb; `report.format`/`report_template.format` stay `TbReportFormat.name()`. No DDL, no overlay change.

**One-time data migration (Decision D1 = adopt PE shape).** A Liquibase changeset (Inferrix overlay) rewrites existing `report_template.configuration` rows to PE-shape so typed deserialization is lossless: for each `components[]` entry with `type=="DASHBOARD"`, move the flat `dashboardId`/`state`/`timewindow` under a nested `config` object and drop `pageWidth`; ensure every config root carries `format` (default `"PDF"`). Idempotent (skip rows already nested). `.77` has ~0 real templates so the live blast radius is tiny, but the changeset ships for correctness and for any future-imported rows. R1's template-form (§10 designer) is updated to **emit PE-shape JSON** so new templates need no migration.

---

## 4. DAO / domain mapping

- `common/data/.../report/ReportTemplate.java` — `configuration` field `JsonNode` → `ReportTemplateConfig`; `deepCopy()` (line 52) becomes a typed clone.
- `common/data/.../report/ReportRequest.java`, `common/data/.../job/task/ReportTask.java` — `reportTemplateConfig` `JsonNode` → `ReportTemplateConfig` (wire type flips through).
- `dao/.../model/sql/AbstractReportTemplateEntity.java` — **entity column stays `JsonNode`+jsonb**; `toData`/ctor do `treeToValue`/`valueToTree`. Smallest blast radius, keeps the jsonb converter intact.
- R1 consumers that navigate the config JSON and change when typed: `PdfReportService.findDashboardComponent` (flat nav → `((DashboardComponent)c).getConfig()…`, gated by Decision D1), `ReportJobProcessor` (type flips through), `ReportController` (type flips through).

All ~55 config classes are **new, additive, Inferrix-authored** (Apache header, "The Inferrix Authors"). No net-new TB-core file for the typing itself — the changes are internal to already-Inferrix-owned R1 files; ledger rows for those files are **amended**, not added.

---

## 5. Render Engine (server-side HTML → PDF)

Ported from `report-4.2.0PE.jar` `org.thingsboard.server.report.*` into `application/.../service/report/**` (same in-`application` convention as R1; **no new Maven module** — avoids the cyclic-dependency trap the discarded `inferrix-reporting/` module hit).

### 5.1 Pipeline (PDF)

1. `TbReportService` (R1) — `EnumMap<TbReportFormat, ReportService>` dispatch; loads template, persists `Report`. **Unchanged surface**; R2 registers the new component-engine `PdfReportService` + `CsvReportService` beans.
2. `ReportService.generateReport(ReportTask, TbReportCtx)` → `PdfReportService extends AbstractReportService`. **⚠ This is the one R1 interface that must gain a parameter** (`TbReportCtx`), so table renderers get the server-side data context (§5.5 R1-interface change).
3. `AbstractReportService` — shared data layer: `fetchEntities`/`fetchEntityDataByQuery`, timeseries (`buildReadTsKvQueries`/`collectTsData`), alarms, aggregation, JS post-processing (`evalScript` via `TbelInvokeService`), value formatting. All queries go through `ReportDataService` under the task's `TbReportCtx` (§6).
4. Per component: `renderComponent` → `PdfReportComponentRenderer` keyed by `ReportComponentType` → emits an **HTML fragment** via a Thymeleaf template.
5. Header/footer measured separately (`measure-template.html` + throwaway `ITextRenderer` → `HtmlRenderUtils.measureHtmlHeight`).
6. Fragments assembled into `report-template.html` (`@page` size/margins/running header-footer).
7. `ThymeleafUtil.renderFromHtmlTemplate` → HTML → JSoup→W3C DOM → `org.xhtmlrenderer.pdf.ITextRenderer` (flying-saucer) → **openpdf** → PDF bytes → `ReportData`.
8. flying-saucer customizations (`report.util.itext.*`): `PdfReportUserAgent` (image/URI resolution via `ReportDataService`), `PdfReportFontResolver` + `PdfReportTextRenderer` (bundled-TTF embedding), `PdfReplacedElementFactory` + `PdfSvgDocument/PdfSvgImage` (SVG→raster via **jsvg**).

### 5.2 Component renderer inventory (`report.renderer.*`)

`HeadingRenderer`, `RichTextRenderer`, `DividerRenderer`, `PageBreakRenderer`, `ImageRenderer` (extends `AbstractImageRenderer`), `EntityTableRenderer`/`TimeseriesTableRenderer`/`AlarmTableRenderer` (extend `TableWithLayoutComponentRenderer` — the heaviest, ~20 KB; `CellVariables` builder), `DashboardRenderer` (extends `AbstractImageRenderer`; §5.4), `ErrorRenderer` (fallback on any component failure), `ReportComponentWithLayoutRenderer` (abstract layout wrapper → `component-layout.html`). Sub-report handled in `AbstractReportService.getSubReportEntities` + `renderSubReport` (recurse child template per entity; no dedicated renderer).

### 5.3 Thymeleaf templates + fonts (ship as `application` resources)

10 HTML + 1 CSS + 1 SVG, ~11 KB total (`report-template.html`, `measure-template.html`, `components/{component-layout,table-template,heading-template,image,divider-template,error-template}.html`, `styles/main.css`, `svg/error-image.svg`). **Fonts are the real volume: ~5.3 MB TTF** (DejaVuSansMono ×4, Roboto ×6, LiberationSans ×4, LiberationSerif ×4) under `fonts/` on the classpath — `PdfReportFontResolver` hard-references `/fonts/...`; confirm classloader path.

### 5.4 DASHBOARD component → R1 renderer adapter

`DashboardRenderer.getImageUrl` must return a **base64 PNG** of the dashboard component. R2 adds an adapter that calls R1's `PlaywrightWebReportRenderer` for **raw image bytes** (R1 currently returns a `ReportData` PDF/PNG; add a `renderDashboardRaw(...) → byte[] PNG` path). No change to the R1 browser protocol (§6.3 of R1 spec) — just a raw-bytes entry point.

### 5.5 The one R1 interface change (ledger)

`service.report.ReportService.generateReport(ReportTask)` → `generateReport(ReportTask, TbReportCtx)`. R1's `PdfReportService`/`TbReportService` callers updated to build+pass a ctx (via `TbReportCtxProvider`, §6). This edits R1-authored Inferrix files → **amend the R1 ledger rows**, not a new TB-core patch.

---

## 6. Server-side data-access context (`TbReportCtx` / `ReportDataService`)

R1 deferred this (spec §6.1). Ported from `report-4.2.0PE.jar` `report.context.*` + `report.datasource.*` (a PE module absent from CE) into `application/.../service/report/context` + `.../datasource`, with the `Local*` impls under `service/report/`.

- `TbReportCtxProvider { TbReportCtx newContext(ReportTask); }`
- `TbReportCtx` (abstract, `Closeable`) — `tenantId, configuration, timeZone, userId, userOwnerId, accessToken, accessTokenExpTs, reportCreatedTime, params, scripts, TbelInvokeService`; `createSubReportCxt(...)`.
- `ReportDataService` (12 methods, each takes a `TbReportCtx`): `findReportTemplate`, `downloadImage`/`downloadPublicImage`, `findEntityDataByQuery`, `countEntitiesByQuery`, `findAlarmDataByQuery`(+`ForEntities`), `countAlarmsByQuery`, `getTimeseries`, `findTimeseriesByQueries`, `createReport`.
- **SecurityUser reconstruction:** `LocalTbReportCtxProvider.newContext` calls `tokenFactory.parseAccessJwtToken(task.getAccessToken())` and stores the `SecurityUser`. **R1 did not touch `parseAccessJwtToken`** (CE `JwtTokenFactory.java:108`, public). R2 becomes a **second consumer — no edit to `JwtTokenFactory`.** `ReportTask` already exposes `accessToken`/`userOwnerId`/`accessTokenExpirationTs` (no field additions).
- `LocalReportDataService` (collaborators, all present in CE: `EntityQueryService, AlarmService, TbTelemetryService, AccessControlService, ReportTemplateService, ImageService, ReportService`) delegates every query through the reconstructed `SecurityUser` — all reads permission-scoped to the task's user.

Self-contained: new files, no upstream edit.

---

## 7. CSV format

`CsvReportService extends AbstractReportService`, `getFormat()==CSV`. Skips HTML/flying-saucer/Thymeleaf entirely: iterates components; each `TableReportComponent` → matching `CsvReportComponentRenderer` (`CsvEntityTableRenderer`/`CsvTimeseriesTableRenderer`/`CsvAlarmTableRenderer` extend `AbstractCsvComponentRenderer`) → `List<List<String>>` (columns from DataSource DataKeys + rows from the shared `AbstractReportService` data layer) → `report.util.CsvUtils` (`commons-csv`). Sub-reports recurse. **Only entity/timeseries/alarm tables produce output** — non-table components silently excluded (PE behavior). No `TbReportFormat` change (CSV already in the enum from R1).

---

## 8. Rule node — `generate report` (V2)

Ported into `rule-engine-components/.../engine/report/`. New files, auto-discovered by the ComponentType scan.

- `TbGenerateReportV2Node` — `@RuleNode(type=ACTION, name="generate report", configClazz=TbGenerateReportV2NodeConfiguration, configDirective="tbActionNodeGenerateReportConfig", icon="description")`, extends `TbAbstractExternalNode` (CE-present).
- Config `TbGenerateReportV2NodeConfiguration{ useConfigFromMessage: boolean, config: ReportConfig }`.
- onMsg: resolve `ReportConfig` (msg data or config) → build a REPORT job (reuse R1's `new Job(tenantId, JobType.REPORT, key, reportTemplateId, cfg)` ctor — **avoid touching `Job.java`**), set on `ReportJobConfiguration`: `ruleNode=ctx.getSelf()`, `outputTbMsgProto=Base64(TbMsg.toProto(outputMsg))`, `queueName`; `ackIfNeeded`; `ctx.getJobManager().submitJob(job)` (present, `TbContext:377`) with a `DonAsynchron` failure callback.

### 8.1 `produceOutputMsg` push-back (the missing async half of R1's `onJobFinished`)

Add `produceOutputMsg()` to `ReportJobProcessor` (R1-authored file → compounds the existing R1 patch), invoked from `onJobFinished`: decode `outputTbMsgProto` → set relation `Success`/`Failure` → on success `outputMsg.getMetaData().putValue("reports", reportId)` → `clusterService.pushMsgToRuleEngine(...)` (via `partitionService.resolve` + `actorSystemContext.persistDebugOutputIfNeeded`). New collaborators on the processor: `TbClusterService`, `PartitionService`, `ActorSystemContext`.

### 8.2 UI config directive

New Angular component `tbActionNodeGenerateReportConfig` (form for `useConfigFromMessage` + `ReportConfig`) — small, in `ui-ngx` rule-node config module.

> **V1 "generate dashboard report" is DEFERRED** (Decision D2, §15): it drags in `TbContext.getPeContext()`, `BlobEntity`/`BlobEntityId`/`BlobEntityService`, a `blob_entity` Liquibase table, and the `DashboardReportService`→`rule-engine-api` interface move — a large PE surface for one node. V2 covers the scheduled+rule-chain report path; delivery reuses R1's direct-byte email attach.

---

## 9. Delivery, REST API, Security — reused from R1

- **Delivery:** unchanged. Job COMPLETED → `onJobFinished` → `NotificationRequest.additionalConfig.reports` → `EmailNotificationChannel`/`DefaultMailService` direct-byte attach. V2's `outputTbMsgProto` adds the rule-engine push-back (§8.1) alongside.
- **REST API:** **no change.** `/api/v2/report*`, `/api/reportTemplate*`, `/api/report/{dashboardId}/download` all reused. The typed config flows through the existing endpoints (request/response bodies are the same JSON; typing is server-internal).
- **Security:** reuses R1's minted token + the existing public `parseAccessJwtToken` (§6). No new security model.

---

## 10. UI — report-template designer (~40 net-new components)

PE's designer is a **three-pane drag-and-drop builder** (palette → canvas → per-component config panel), recovered from the built `ui-ngx-4.2.0PE.jar` (i18n namespace `report-template.*`, 108 keys; full `tb-report-*` selector tree). R1 ships **1** component (the dashboard-only dialog); R2 extends `report.models.ts` to the typed config tree and builds the designer.

Decomposition (~40 net-new): editor shell/layout (~10: root page, container, toolbar, tabs content/data/layout, title, form, sections, page, header-footer, settings-dialog); palette + CDK drag-drop (~8: library, library-item + preview/title/drag-item, draggable, placeholder, empty-state, host); per-component config+preview panels (~16: HEADING, RICH_TEXT (TinyMCE), DIVIDER, IMAGE (dialog), SUB_REPORT each a config+preview pair; the 3 table types share one `tb-report-table*` family; **DASHBOARD reuses the R1 `tb-dashboard-report-config`**; PAGE_BREAK none); shared sub-editors (~5: text-style panel, `tb-report-insets` margins/paddings, background/border widget, alignment control, image dialog). Header/footer also host `PAGE_NUMBER`/`CREATED_TIME` dynamic fields (not body components). Page settings: paper-size, orientation, margins/paddings, background/border, first-page vs subsequent header/footer, file-name-pattern, default-date-format.

Not recoverable from the built bundle (design fresh): exact validators, paper-size enum→dimension table, drag/drop + preview logic, header/footer preset composition. **This is a large FE effort — comparable to a mini widget-editor** and dominated by the per-component panels + shared style sub-editors. Sequence: shell + palette + DnD canvas first (proves architecture), then editors cheapest-first (PAGE_BREAK → DIVIDER → HEADING → RICH_TEXT → IMAGE → the table family → SUB_REPORT), DASHBOARD slotted in from R1.

---

## 11. Dependencies & Licensing

Add to `application/pom.xml` (already Inferrix-touched by R1's Playwright line — append, watch ledger sequencing):

| Dep | Version | License |
|---|---|---|
| `org.thymeleaf:thymeleaf` | 3.1.3.RELEASE | Apache-2.0 |
| `org.xhtmlrenderer:flying-saucer-core` | 9.13.0 | LGPL-2.1+ |
| `org.xhtmlrenderer:flying-saucer-pdf` | 9.13.0 | LGPL-2.1+ |
| `com.github.librepdf:openpdf` | 2.0.5 | LGPL/MPL |
| `com.github.weisj:jsvg` | 1.6.1 | MIT |
| `org.apache.commons:commons-csv` | 1.10.0 | Apache-2.0 |
| `org.apache.xmlgraphics:xmlgraphics-commons` | 2.9 | Apache-2.0 (transitive) |

**⚠ Licensing — no AGPL exposure.** PE deliberately uses **openpdf 2.0.5 (LGPL/MPL)**, not iText 5 (AGPL). The `report.util.itext.*` package name is a red herring — those classes extend flying-saucer's `org.xhtmlrenderer.pdf.ITextRenderer` (its historical "iText" naming for the openpdf bridge). **Do not add any `com.itextpdf`/`com.lowagie` jar.** flying-saucer-pdf 9.13.0 pulls openpdf transitively — **verify the resolved graph does not substitute iText** (`mvn dependency:tree`). `jsvg` is uncommon — confirm it's acceptable (only SVG rasterizer, required for SVG widgets/images in PDF).

---

## 12. Deviations from PE (R2)

| # | PE | R2 | Why |
|---|---|---|---|
| 1 | `ReportService.generateReport(ReportTask, TbReportCtx)` | same (R1's no-ctx signature gains the param) | table renderers need the server-side ctx R1 deferred |
| 2 | V1 + V2 rule nodes | **V2 only** | V1 needs `PeContext`/`BlobEntity`/`blob_entity` table — disproportionate (Decision D2) |
| 3 | DASHBOARD rendered by PE's remote screenshot | R1 Playwright renderer raw-PNG adapter | reuse R1's in-process renderer; no microservice |
| 4 | `BlobEntity` attachment channel | direct-byte email attach (R1) | no blob store; V2 doesn't need it |
| 5 | config root discriminator `format`, nested DASHBOARD `config` | **adopt PE shape** (Decision D1 resolved) + one-time Liquibase data migration (§3); designer emits PE-shape JSON | PE byte-fidelity, supports future PE-template import |
| 6 | Microservice ctx/data (`Remote*`) | in-process `Local*` only | monolith |

---

## 13. TB-core files modified (ledger preview — extends the `## Feature: Reporting` section)

| target | module | nature |
|---|---|---|
| `ReportTemplate.java`, `ReportRequest.java`, `ReportTask.java` | `common/data` | **amend R1 rows** — `configuration`/`reportTemplateConfig` `JsonNode`→`ReportTemplateConfig` |
| `report/configuration/**` (~55 classes) | `common/data` | **new files**, additive (not ledger patches — new sources) |
| `AbstractReportTemplateEntity.java` | `dao` | amend R1 row — `toData`/ctor `treeToValue`/`valueToTree` (column unchanged) |
| `PdfReportService.java`, `TbReportService.java`, `AbstractReportService`(new), renderers, ctx/datasource (new) | `application` | replace/extend R1 impls + new engine + new ctx module |
| `ReportJobProcessor.java` | `application` | amend R1 row — add `produceOutputMsg()` (+ `TbClusterService`/`PartitionService`/`ActorSystemContext`) |
| `TbGenerateReportV2Node` + config | `rule-engine-components` | new files (Inferrix-authored) |
| `application/pom.xml` | `application` | amend — thymeleaf/flying-saucer/openpdf/jsvg/commons-csv deps + fonts/templates resources |
| `tbActionNodeGenerateReportConfig`, designer (~40 comps), `report.models.ts` | `ui-ngx` | new files |
| `JwtTokenFactory.java` | `application` | **no edit** — reused as 2nd consumer of `parseAccessJwtToken` |

No DB schema change; no REST/API change. Renderer beans stay `@ConditionalOnProperty(reports.renderer.enabled)`.

---

## 14. Testing strategy (TDD, mirrors R1 §17)

- **Config model:** round-trip tests — R1-stored JSON (flat DASHBOARD) ↔ typed POJOs per Decision D1; `Interval` custom codec; `ErrorComponent.exception` `@JsonIgnore`; unknown-key tolerance.
- **Renderers:** per-component HTML-fragment snapshot tests (Thymeleaf output stable); PDF smoke (bytes start `%PDF`, non-trivial size); font embedding present; SVG raster path.
- **CSV:** table→rows correctness; non-table exclusion; sub-report recursion.
- **Query ctx:** `LocalReportDataService` scopes to the token's `SecurityUser` (permission-boundary test — a user can't read another tenant's entities via a report).
- **Rule node V2:** job submitted with `outputTbMsgProto`; `produceOutputMsg` pushes `"reports"` metadata on completion; failure relation on failure.
- **Context-load regression:** renderer-on boots (mirrors R1's `ReportRendererWiringTest`) with the new engine beans + deps.
- **Designer:** karma specs for the config serializer (designer state → typed JSON byte-compatible with the backend model).
- **DoD:** full reactor `clean install` green; existing 94 R1 tests still green; frontend prod build green; `license:check` green (new deps have license entries); ledger audited; renderer opt-in preserved; E2E — a multi-component template (heading + entity table + dashboard) renders to a valid PDF and a CSV, delivered by email.

---

## 15. Decisions (RESOLVED 2026-07-21)

**D1 — config migration strategy → ADOPT PE SHAPE.** Type the config exactly as PE (`format` root discriminator, nested DASHBOARD `config: DashboardReportConfig`, no `pageWidth`). Ship a one-time idempotent Liquibase data migration (§3) rewriting existing `report_template.configuration` rows flat→nested; the designer emits PE-shape JSON. Rationale: PE byte-fidelity, supports future PE-template import; live blast radius on `.77` is ~0 templates.

**D2 — rule node scope → V2 ONLY.** Port `generate report` (V2) + the `produceOutputMsg` push-back. Defer V1 `generate dashboard report` (drags in `PeContext`/`BlobEntity`/`blob_entity`/`DashboardReportService`→`rule-engine-api`) until a rule-chain multi-attachment email is a hard requirement.

**D3 — phasing → SEQUENTIAL R2a → R2b → R2c.** Each its own plan → implement → verify → deploy to `.77` before the next (matches how R1 was run). Boundary is dependency-driven: the three **table** renderers and **CSV** fetch server-side data, so they land in R2b **with** the query context they need.
- **R2a — Typed config (+ Liquibase migration) + HTML→PDF engine core + non-data component renderers.** Foundational; typed config blocks everything. Engine skeleton (`AbstractReportService`, `TbReportService` dispatch, the `ReportService`+`TbReportCtx` signature change, Thymeleaf/flying-saucer/openpdf/font assembly) + renderers that need **no** server query: HEADING, RICH_TEXT, DIVIDER, PAGE_BREAK, IMAGE (image fetch via `ImageService`), DASHBOARD (R1 Playwright raw-PNG adapter). Delivers a multi-component PDF minus data tables. *(plan authored next.)*
- **R2b — Server-side query context + data component renderers + CSV + V2 rule node.** `TbReportCtx`/`ReportDataService`/`Local*` impls; the `AbstractReportService` data layer; ENTITY_TABLE / ALARM_TABLE / TIME_SERIES_TABLE PDF renderers; `CsvReportService` + CSV renderers (tables-only); the `generate report` (V2) node + `produceOutputMsg`. Depends on R2a.
- **R2c — Designer UI** (~40 Angular components). Independent of R2a/R2b backend once the typed model exists; largest FE body.

---

## 16. Appendix — analyzer provenance

Four `pe-feature-analyzer` passes (2026-07-21) over `docs/jars/BOOT-INF/lib/{data,report,rule-engine-components,rule-engine-api,ui-ngx}-4.2.0PE.jar`: typed-config hierarchy, render engine + component renderers, rule nodes + query ctx, designer UI. Decompiled scratch at `…/scratchpad/r2-pe/` and `…/scratchpad/peui/` (transient). This spec is the durable synthesis; raw maps are in the session transcript.
