# Reporting R2c — C2: Data Components + Full PE Designer Parity (design)

**Status:** design · **Date:** 2026-07-26 · **Branch:** `inferrix-release-4.3`
**Predecessors:** R2a (typed config + HTML→PDF engine + non-data renderers), R2b (server-side data layer + table/CSV renderers + `generate report` V2 rule node), **R2c C1** (designer skeleton + palette + canvas + preview + page settings + the 5 non-data config panels, complete & pushed). Parent design: `docs/specs/2026-07-24-reporting-r2c-design.md`.
**Directive (user, 2026-07-26):** *replicate PE; verify against PE only.* **Scope (user, 2026-07-26): Full PE designer parity.**
**PE reference:** decompiled from `docs/jars/BOOT-INF/lib/{data,report,ui-ngx}-4.2.0PE.jar` — backend POJOs (`data`/`report`), and the compiled Angular designer (`ui-ngx`, logic in `public/chunk-UILY2NXB.js`; i18n + 25 palette SVGs + **23 palette `defaultConfig` object literals** all recovered). Two agents grounded this spec: `pe-c2-analyzer` (PE) and `c2-fe-recon` (CE reuse surface).

---

## 0. Overview & scope

C2 completes the R2c report-template **designer** into a replica of the ThingsBoard **PE** designer. C1 delivered the three-pane shell (palette → CDK canvas → config panel + Edit⇄Preview) and the 5 non-data panels. C2 adds:

1. **Data-component configuration** — ENTITY_TABLE, ALARM_TABLE, TIME_SERIES_TABLE, SUB_REPORT, DASHBOARD — via PE's **tabbed** config (Content / Data / Layout) and PE's exact field set. Per §E the three tables share **one generic** config path, not three bespoke panels.
2. **The Data tab** — reuses TB's **platform** datasource / entity-alias / data-key / filter editors (PE does the same), plus the report-only `ColumnSettings`/table-settings/timewindow sub-forms, behind thin report-shaped glue.
3. **Template-level composition** — entity-alias and filter management, backed by an `IAliasController` shim over the report's own `entityAliases[]`/`filters[]`.
4. **Header & footer** — enabled + first-page zones hosting ordinary components, plus the dynamic-field presets (Page number, Created time).
5. **The 23-item palette** — 10 real component types + **13 presets** (11 → RICH_TEXT, 2 → HEADING), each a single component with a canned `value`/`defaultConfig`.
6. **Import / Export** report template (JSON round-trip).
7. **Retrofit PE's tab layout** onto C1's non-data panels for a uniform designer.

**C2 is pure frontend — zero backend.** `pe-c2-analyzer` confirmed the R2a/R2b model + renderer are **byte-for-byte identical to PE** for every C2 component; the engine already renders all of them to PDF and CSV. Preview reuses C1's `POST /api/v2/report/test`. Only TB-core touch = the shared locale file (as in R45).

**Naming principle (like C1):** adopt PE's **structure** (which components exist, how tabs/tables/data-editors decompose) but keep **C1's existing `tb-report-*` names** — C1 already shipped `tb-report-palette`/`-canvas`/`-page-settings`/`-insets`/… and renaming to PE's `tb-report-component-library`/… would churn and break C1. PE selectors are a structural reference, not names to copy (same call as the i18n-namespace decision, F5).

**Out of scope:** V1 `generate dashboard report` rule node; microservice `Remote*` ctx/data; renderer opt-in changes; any backend/model change.

---

## 1. PE-fidelity basis — what "verify against PE only" means, and its ceiling

| PE artifact | Grounds | Status |
|---|---|---|
| `data-4.2.0PE.jar` | Config-model POJO field parity | ✅ 100%, zero drift (§2) |
| `report-4.2.0PE.jar` (`report.*`, 48 cls) | Renderer consumption, token vocab, header/footer placement, CSS | ✅ (§2, §7) |
| `ui-ngx-4.2.0PE.jar` (`chunk-UILY2NXB.js`) | Designer decomposition (selectors), 154 `report-template.*` i18n keys, palette SVGs, **23 `defaultConfig` literals** | ✅ (§4, §5, §6) |

**Recovered:** selector tree, i18n vocabulary, palette items, and **real PE-emitted component JSON** (the palette `defaultConfig` literals — §6/§10).
**Not recovered (design-fresh, reconstruct from C1):** per-component form-control wiring / validation / write-back (minified). C1 already established these patterns.
**Token mechanism (§2.C):** C2 keeps CE's data-only `${...}` scanner, NOT PE's Thymeleaf-inline — security (R2a SSTI fix) overrides literal PE fidelity in the data-var branch only; the token *vocabulary* is PE-identical.

**Fixture reality:** no full exported PE template JSON exists in any jar (only `report_template` DDL: `configuration varchar(10000000)`). **But** the 23 palette `defaultConfig` object literals in `chunk-UILY2NXB.js` **are** real PE-emitted per-component JSON — extractable verbatim (incl. the 13 preset HTML `value`s with inline styles). These are C2's byte-fidelity fixtures for per-component + header/footer round-trip tests (§10).

---

## 2. Verified PE ground-truth (backend)

### A. Config-model parity — field-complete, zero drift
The R2a/R2b TS model `shared/models/report-configuration.models.ts` (+ Java `common/data/.../report/configuration/**`) matches PE class-for-class, field-for-field. **C2 adds no model types and no fields.** Shapes C2 edits: `DataSource{type,deviceId,entityAliasId,filterId,dataKeys[],latestDataKeys[],alarmFilterConfig}`; `DataSourceType` labels `device|entity|entityCount|alarmCount`; `DataKey{name,type,label,decimals,units,aggregationType,timewindow,usePostProcessing,postFuncBody,settings}`; `DataKeySettings` = `ColumnSettings{type:'COLUMN',columnWidth,header:CellSettings,cell:CellSettings}` | `{type:'DEFAULT'}`; `CellSettings{font,color,backgroundColor,textAlignment,verticalAlignment}`; `TableSortOrder{column,direction:ASC|DESC}`; `EntityAlias{id,alias,filter:EntityFilter}`; `Filter{id,filter,keyFilters:KeyFilter[]}`; `AlarmFilterConfig{typeList,statusList,severityList,assigneeId,searchPropagatedAlarms}`. The 5 components' own fields: ENTITY_TABLE none; ALARM_TABLE `{alarmSource:DataSource,timewindow}` (authors via `alarmSource`, `getDataSources()` is `@JsonIgnore`+`List.of(alarmSource)`); TIME_SERIES_TABLE `{timewindow,showTimestamp,timestampLabel,timestampPattern,timestampColumnSettings}`; SUB_REPORT `{templateId,avoidPageBreakInside}` (+ inherited `dataSources[]`, no layout); DASHBOARD `{config:DashboardReportConfig}` (+ layout + image-sizing).

**Enum JSON encodings (verified from PE literals, match CE):** ReportComponentType=UPPERCASE; DataSourceType `device|entity|entityCount|alarmCount`; ImageSourceType `image|entityKey`; ImageWidthType `original|custom|fitWidth`; TextAlignment `left|center|right`; VerticalAlignment `top|middle|bottom`; FontWeight `"500"` (string); FontStyle `normal`; BorderType `solid|dashed|dotted`; BorderLength `LONG` (UPPERCASE). *(Plan: re-confirm BorderLength/PageSize `@JsonValue` against CE enums — §12 open.)*

**FE-only nuance:** PE's designer default `font` emits `{size, sizeUnit:"pt", weight, style, family}` — `sizeUnit` is **not** in the Java `Font` POJO (server drops it via `FAIL_ON_UNKNOWN=false`; the FE re-adds the constant each load). It is **server-inert**. Decision (F8): C2's TS `Font` may carry `sizeUnit:"pt"` for FE fidelity; the serializer must not choke on it. Byte-fidelity on the persist path is unaffected.

### B. Renderer consumption (why the fields matter) — `report-4.2.0PE.jar` → CE `render/ReportQueryUtils`
- `DataSource.type==DEVICE` → single-entity query from `deviceId`; else `entityAliasId` → the **template-level** `entityAliases[]` alias's `EntityFilter`. ⟹ alias management is a **prerequisite**, not optional.
- `filterId` → template-level `filters[]` → `KeyFilter[]`. `alarmFilterConfig` → alarm query. `latestDataKeys` → latest-snapshot datasource.
- `dataKeys` split by the **`DataKey.type` string**: `entityField`→ENTITY_FIELD, `attribute`→ATTRIBUTE, `timeseries`→TIME_SERIES, `alarm`→ALARM_FIELD. ⟹ the data-key editor must offer those four key types.

### C. Dynamic fields — RESOLVED (PE-token-identical; only the data-var *engine* deviates)
No `PAGE_NUMBER`/`CREATED_TIME` type exists (`ReportComponentType` has 11 values, byte-identical). Both are **`${...}` tokens inside a HEADING `value`** — confirmed from BOTH ends:
- **PE palette default configs** (verbatim from the chunk): "Page number" → `HEADING`, `value:"Page: ${pageNumber}/${totalPages}"`; "Created time" → `HEADING`, `value:"Created: ${reportCreatedTime}"`.
- **Backend token switch** (PE `ThymeleafUtil` ≈ CE `substituteVariables`): `pageNumber`→`<span class="page-number">` (CSS `counter(page)`), `totalPages`→`<span class="page-count">` (CSS `counter(pages)`); **`default` branch:** PE = Thymeleaf inline `[(${key})]` (SSTI) · CE = data-only map lookup (R2a hardening). `.page-number`/`.page-count` CSS in `main.css` (identical PE/CE).

⟹ **The token vocabulary is PE-identical** — `${pageNumber}`, `${totalPages}`, `${reportCreatedTime}`, `${entityName}`, `${entityLabel}`, `${id}`, `${<dataKeyLabel>}` (names normalized: spaces→underscores). C2's "insert dynamic field" emits exactly these. The only PE↔CE difference is the *substitution engine* for data vars (PE OGNL vs CE data-only scanner) — invisible in the stored `value`, and C2 must keep CE's (security). **This makes C2's dynamic fields fully PE-faithful at the wire level.**

### D. Header/footer placement
`PdfReportService.renderHeaderFooter` renders `HeaderFooter.components[]` through the **same** `renderContent` dispatch as body content, measures pixel height, places via CSS paged-media running elements (`position:running(header)` + `@page{@top-center{content:element(header)}}`) — **every page**; `firstPage` → `@page:first`. ⟹ header/footer components reuse the body config path; no special panels.

---

## 3. PE designer decomposition (selector tree) — the structure C2 mirrors

From `chunk-UILY2NXB.js` (names PE-verbatim; C2 keeps its own C1 `tb-report-*` names but adopts this structure):

- **Shell/route:** `tb-report-template-page` (full-page route), `tb-report-template-tabs`, `tb-report-template-form`, `tb-report-template-settings`(+`-dialog`), `tb-report-template-header-footer`, `tb-report-template-autocomplete`, `tb-report-template-filter`, `tb-report-template-table-header`.
- **Canvas/palette:** `tb-report-component-library` (palette), `tb-report-components` (canvas), `tb-report-component` (card), `tb-report-component-config` (**right-panel host**), `tb-report-component-layout-settings` (Layout tab).
- **Per-type:** `tb-report-heading`(+`-config`/`-preview`), `tb-report-rich-text`(+`-config`), `tb-report-divider-config`(+`-preview`), `tb-report-table-header`, `tb-sub-report-config`(+`-preview`), `tb-report-image-dialog`, `tb-report-config`, `tb-report-filter`.
- **Shared:** `tb-report-insets` (**C1 already ships this exact name**).
- **Dashboard / empty:** `tb-dashboard-report-config`, `tb-empty-report-config` (the nothing-selected panel = C1's page/template settings).

**Structural findings that shape C2:**
1. **No per-table config selectors** — ENTITY/ALARM/TIME_SERIES tables share ONE generic config path (`tb-report-component-config` host + `tb-report-table-header` for shared table settings + the Data tab for datasources). Matches the POJO hierarchy (all extend `AbstractTableWithLayoutReportComponent`). ⟹ C2 builds **one** table config path, type-switched, not three panels.
2. **No `tb-report-datasource`/`tb-report-data-key` selectors** — the Data tab **reuses the platform** datasource/entity-alias/data-key/filter editors (the dashboard widgets), consistent with the backend reusing `data.query.EntityFilter`/`KeyFilter`. `tb-report-filter`/`tb-report-template-filter` are the report's filter-list glue. ⟹ C2 reuses TB leaf editors, thin glue only (§5) — *not* heavy bespoke composites.
3. **Sub-report form** = `{dataSources, templateId, avoidPageBreakInside}` — `avoidPageBreakInside` shown only when `format != CSV` (the "isPlainFormat" gate).
4. **Time series:** when `showTimestamp`, the renderer prepends a synthetic column `{name:"ts",type:"timeseries",label:timestampLabel||"Timestamp",settings:timestampColumnSettings}`.
5. **Image:** `sourceType:"entityKey"` resolves `imageUrl` from `dataSources[0]`'s key; else literal `imageUrl`. `widthType` original→auto, custom→`customWidth`px, else 100%. *(Confirm C1's IMAGE panel handles the `entityKey` source — possible C1 gap, §12.)*

---

## 4. Reuse strategy (c2-fe-recon)

**Reuse TB leaf widgets; do NOT reuse `tb-datasource`/`tb-datasources`** (hard-inject `WidgetConfigComponent`, no `@Optional()` → `NullInjectorError`). PE inlines the platform editors into the Data tab; C2 does the same via thin glue.

| Need | TB widget | CVA value | Coupling / shim |
|---|---|---|---|
| Entity-alias picker | `tb-entity-alias-select` | `string` aliasId | `[aliasController]` (shim §5.3) + `[callbacks]` |
| Filter picker | `tb-filter-select` | `string` filterId | `[aliasController]` + `[callbacks]` |
| Data-key list | `tb-data-keys` | `DataKey[]` | no widget-config coupling; report DataKey ≈ subset (§5.1) |
| Alarm filter | `tb-alarm-filter-config` | `AlarmFilterConfig` | standalone; **shim** `assignedToCurrentUser`↔`assigneeId` (§5.4) |
| Device picker | `tb-entity-autocomplete` | `string` deviceId | direct |
| Timewindow | `tb-timewindow [historyOnly]` | `Timewindow` | **shim** to `TimeWindowConfiguration` (§5.2) — except DASHBOARD (raw passthrough, §6.5) |
| Dashboard picker | `tb-dashboard-autocomplete` | `string` dashboardId | direct (confirm CVA at plan) |
| Alias/filter create/edit | `EntityAliasDialogComponent`/`FilterDialogComponent` | data-in `MatDialog` | unchanged; opened by shim callbacks (§5.3) |

`tb-dashboard-report-config` **exists in PE, not CE** — C2 builds it fresh (§6.5), keeping the PE selector name is fine here since C1 has no conflicting component.

---

## 5. Data-tab sub-editors (thin glue over reused leaves)

Under `.../report-template/designer/data/` (C1 naming; behavior reconstructed from C1 patterns):

- **5.1 Datasource + data-keys** — a report `DataSource` editor: `type` select (device/entity/entityCount/alarmCount); DEVICE→`tb-entity-autocomplete`, ENTITY*→`tb-entity-alias-select` + optional `tb-filter-select`; `dataKeys`/`latestDataKeys`→`tb-data-keys` wrapped to add the report `ColumnSettings` sub-form (`columnWidth`, `header`/`cell` `CellSettings`) TB's stock data-key lacks; key **type** offers `entityField|attribute|timeseries|alarm`. Report `DataKey` ↔ platform `DataKey` mapping at the CVA boundary (report subset + `timewindow`/`postFuncBody`). Rendered as a list for `dataSources[]` (ENTITY/TIME_SERIES/SUB_REPORT) or a single source for ALARM_TABLE `alarmSource`.
- **5.2 Timewindow shim** — `tb-timewindow [historyOnly]`; translate `history.historyType` (report raw `int` ↔ platform enum) + `interval` encoding (report `number|name-string` ↔ platform `Interval`).
- **5.3 Alias/Filter shim** — a bounded local `IAliasController` (17 members, several no-ops) over `config.entityAliases[]`/`config.filters[]`; callbacks open the existing `EntityAliasDialogComponent`/`FilterDialogComponent`. Report `EntityAlias.filter`/`Filter.keyFilters` are already `data.query.EntityFilter`/`KeyFilter` (zero mapping). Template-level "Manage aliases/filters" in the template settings.
- **5.4 Alarm-filter shim** — map `assignedToCurrentUser`↔`assigneeId` so "assigned to me" survives round-trip.
- **5.5 Table settings** (`tb-report-table-header` role) — `showTableHeading` + `tableHeading:Heading` + `tableSortOrder{column,direction}`; shared by all tables. Cell/column styling via `CellSettings` (reuse C1 font/alignment sub-editors).

---

## 6. Component config — one generic host, tabbed (PE structure)

C2 routes all types through a generic config host (C1's shell `*ngIf` switch, extended) with a shared **tab wrapper** (Content / Data / Layout), tabs hidden when empty for a type. Write-back unchanged (`onComponentChange` → `Object.assign(this.selected,value)`; each panel emits a full component). `hasConfigPanel()` extends to the 5 data types; the placeholder (`report-template-editor.component.html:82`) is removed once all 10 have panels.

- **6.1 Tables (ENTITY / ALARM / TIME_SERIES — one path).** Data tab: datasources (§5.1) — `dataSources[]` for ENTITY/TIME_SERIES, single `alarmSource` for ALARM; `timewindow` for ALARM/TIME_SERIES. Settings: shared table settings (§5.5); TIME_SERIES adds `showTimestamp`/`timestampLabel`/`timestampPattern`/`timestampColumnSettings` (drives the synthetic `ts` column, §3.4). Layout: C1 layout sub-editors.
- **6.4 SUB_REPORT.** `templateId` picker (report-template autocomplete, excluding self; server-side depth/budget bounds already enforced), `avoidPageBreakInside` (only when `format != CSV`), `dataSources[]`. No layout tab.
- **6.5 DASHBOARD (`tb-dashboard-report-config`, built fresh, security-bounded).** Content: `tb-dashboard-autocomplete`(`config.dashboardId`), `state` text, `tb-timewindow` bound **raw** to `config.timewindow` (`JsonNode`/`any` passthrough — no shim), `useDashboardTimewindow`. Layout: layout + image-sizing. **Security bound:** exposes exactly `dashboardId`/`state`/`timewindow`/`useDashboardTimewindow`; **never** `baseUrl`/`userId`/`useCurrentUserCredentials` (renderer hardcodes/ctx-overrides them; exposing re-opens the token-exfil/SSRF vector R2a closed). PE's own dashboard keys are only `no-dashboard`/`open-dashboard-new-tab`, confirming the bound.

---

## 7. Header / footer + dynamic-field presets

- **Model:** `header`/`footer:HeaderFooter{enabled,components[],firstPage}` on `PdfReportTemplateConfig` (PDF only; CSV has none). Rendered per-page (§2.D).
- **UI** (`tb-report-template-header-footer` role): page/template settings gains Header and Footer sections — each `enabled` toggle + a nested `tb-report-canvas` editing `components[]` + a "different first page" toggle exposing `firstPage` (its own mini-canvas). Header/footer components select into the same config host as body components.
- **Dynamic-field presets** (palette): "Page number" drops `HEADING{value:"Page: ${pageNumber}/${totalPages}", …}`; "Created time" drops `HEADING{value:"Created: ${reportCreatedTime}", …}` — exact PE defaultConfigs (§10). HEADING/RICH_TEXT panels also gain an **"Insert dynamic field"** menu emitting the §2.C token set.

---

## 8. The 23-item palette (10 types + 13 presets)

PE's palette entry = `{key, i18n title, previewImage (svg), type, defaultConfig}`; the presets instantiate a **single** RICH_TEXT or HEADING with a canned `value`/config (not multi-component groups):
- **10 real types:** heading, richText, entityTable, timeSeriesTable, alarmTable, image, dashboard, subReport, divider, pageBreak.
- **11 RICH_TEXT presets:** textSection, textImage, imageText, logoHeading, headingLogo, logoText, textLogo, logoText2, footer1, footer2, footer3 (canned HTML `value`s with inline-styled tables; logo presets embed `tb-image;/assets/report/components/logo-placeholder.svg` — the `tb-image;` prefix the `PdfReportUserAgent` parser accepts).
- **2 HEADING presets:** pageNumber, createdTime (§7).

**Mechanism:** a preset registry `key → defaultConfig` (the extracted PE literals, adapted to the Inferrix logo asset). Dropping a preset inserts a clone of its `defaultConfig` onto the canvas/header/footer. Preview SVGs ship under `assets/report/components/` (25 svgs). **The `defaultConfig` literals are extracted verbatim from PE (§10) — this is both the implementation seed and the fixture.**

---

## 9. Import / Export + CSV

- **Export:** serialize `{name,format,type,configuration}` → downloaded JSON (reuse platform `ImportExportService`/download utils).
- **Import:** file-picker → parse → validate structure (`format` present, `components[]` array) → `deserialize` into the editor **without saving** (author reviews, then Saves); invalid → the PE error toast (`invalid-report-template-file-error`). Never eval imported content.
- **CSV:** `CsvReportTemplateConfig` has only base fields; the designer hides page-settings/header/footer/layout tabs for CSV (renderer ignores layout). Data panels identical. Satisfies the "PDF **and** CSV" DoD.

---

## 10. Serializer / byte-fidelity (DoD anchor)

C1's serializer already round-trips the data types (type-agnostic). C2's work = **fixtures + specs**:
- **Fixtures = the 23 PE palette `defaultConfig` literals** (real PE-emitted JSON, extracted from `chunk-UILY2NXB.js`) + hand-authored table/header-footer templates round-tripped through CE Jackson. Cross-checked against the backend accept path (`RootConfigJsonTest`/`ComponentPolymorphismJsonTest`).
- Karma specs: `serialize(deserialize(fixture)) === fixture` for each data component (datasources, dataKeys incl. `ColumnSettings`, alarmFilterConfig, timewindow), header/footer (incl. nested `firstPage`), template-level `entityAliases`/`filters`, each of the 13 presets, and a full multi-component template.
- CVA specs: each config emits a full component; the shims (§5.2/5.4) round-trip without dropping `assignedToCurrentUser`/`historyType`/`interval`; `font.sizeUnit` (F8) survives or is cleanly re-added.
- Re-assert C1's mutation-tested `onComponentChange` preservation for data components.

---

## 11. Sub-phasing (TDD) — leaner than a naive decomposition (PE reuse cuts components)

Test-first per task (spec/fixture → component), then per-task adversarial security + code review, then commit (R2b/C1 loop). Phases:

- **P0 — Tab wrapper + retrofit.** Shared Content/Data/Layout tab wrapper; re-host C1 panels; specs prove C1 emit contracts unchanged. Fold in carry-from-C1 T3-M3 (editor load/save error handler).
- **P1 — Data-tab sub-editors.** Datasource+data-keys glue (§5.1, +`ColumnSettings`), table settings (§5.5), timewindow shim (§5.2), alarm-filter shim (§5.4) — each with round-trip specs against PE fixtures.
- **P2 — Alias/filter shim** (§5.3) + template-level manage UI.
- **P3 — Tables (one generic path)** — ENTITY/TIME_SERIES/ALARM via the shared config + Data tab; extend `hasConfigPanel` + shell switch.
- **P4 — SUB_REPORT** (CSV gate) **+ DASHBOARD** (fresh, security-bound spec). Drop the placeholder.
- **P5 — Header/footer** (nested canvases, enabled/firstPage) **+ dynamic-field presets** (Page number/Created time) + insert menu.
- **P6 — Composite presets** — the 11 RICH_TEXT presets from the extracted PE `defaultConfig` literals (SVG-guided; Inferrix logo).
- **P7 — Import/Export.**
- **P8 — i18n + wiring + E2E.** Full `report.designer.*` sweep mapping PE's `report-template.*` vocabulary; module registration; C2 E2E checklist.

---

## 12. Testing / DoD · Security · Ledger · Open

**Testing/DoD.** Full karma suite green (C1's 47 + C2's new specs); AOT prod build green; `license:check` green; per-task adversarial review. **C2 DoD:** author heading + entity table + dashboard with a header (page-number preset) + footer → valid **PDF and CSV** → email (operator E2E, renderer-on `.77`, checklist like C1's — documented, not executed). Renderer opt-in preserved (no backend touch).

**Security focus.** DASHBOARD 4-field bound (spec asserts emitted `config` excludes `baseUrl`/`userId`/`useCurrentUserCredentials`); token vocabulary fixed (no OGNL/expression affordance — insert menu offers only the §2.C set); no new backend surface (preview reuses gated `/api/v2/report/test`; data reads stay server-side permission-scoped, R2b F1); import validates structure + no eval; alias/filter shim resolves only the template's own arrays.

**Ledger (TB-core).** Only `locale.constant-en_US.json` — added `report.designer.*` keys (extending R45) mapping PE's `report-template.*` vocabulary into our namespace; one row, same merge-guard as R45. All else additive Inferrix files (`.../designer/**`, `data/`, `presets/`, `shared/models/**`) — no rows.

**Decisions.** F1 full PE parity · F2 zero backend · F3 CE data-only token engine (not PE OGNL; vocab PE-identical) · F4 reuse TB leaf editors (DASHBOARD fresh; alias/filter via shim over existing dialogs) · F5 keep `report.designer.*` namespace + `tb-report-*` names (PE structure, C1 names) · F6 fixtures = extracted PE palette `defaultConfig` literals + CE round-trips · F7 preset content from extracted PE literals (SVG-guided) · F8 TS `Font` may carry FE-only `sizeUnit:"pt"` (server-inert).

**Open (plan-time).**
1. Tab-retrofit blast radius on C1 panels (keep all C1 specs green); fold T3-M3.
2. `tb-dashboard-autocomplete` CVA shape (recon un-traced).
3. Extract the full 23 `defaultConfig` literal set from `chunk-UILY2NXB.js` (offset ~1.48M–1.54M) for fixtures/presets.
4. Confirm C1's IMAGE panel handles `sourceType:"entityKey"` (resolve from `dataSources[0]`) — possible C1 gap (§3.5).
5. Re-confirm BorderLength/PageSize `@JsonValue` encodings vs CE enums (§2.A).
6. Carry-from-C1: T2-M1/M2/M4 (header/footer serializer recursion + round-trip) → P5; T11-M2 (sourceType fallback) → P1/P4.
