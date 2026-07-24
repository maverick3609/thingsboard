# Reporting R2c — Report Template Designer (design)

**Status:** design · **Date:** 2026-07-24 · **Branch:** `inferrix-release-4.3`
**Predecessors:** R2a (typed config + HTML→PDF engine + non-data renderers, complete), R2b (data layer + table/CSV renderers + `generate report` V2 rule node, complete). Parent design: `docs/specs/2026-07-21-reporting-r2-design.md` (§10 designer outline; phasing D3: R2a→R2b→R2c).

## 0. Overview & scope

R2c is the tenant-admin-facing Angular UI to author and edit report templates — a full-fidelity, PE-style **three-pane drag-and-drop designer** (palette → canvas → config) that emits the typed `ReportTemplateConfig` JSON the R2a/R2b engine already renders. The engine, data layer, and rule node are done; R2c's job is producing valid config and previewing it.

**Two deviations from a pure FE effort, both decided in brainstorming (2026-07-24):**
1. Preview is **server-rendered** (a browser can't run the Java Thymeleaf/flying-saucer engine), so R2c adds one **new backend endpoint** — R2c is full-stack, not FE-only.
2. Preview shows the **whole assembled template** (real pagination + header/footer), not per-component client mocks — which removes PE's per-component preview widgets, collapsing the ~40-component estimate to ~30–35 config-only panels + one shared preview pane at the same fidelity.

**Prerequisites already in place (verified 2026-07-24):**
- Typed config model (~60 POJOs) exists in Java: `common/data/.../report/configuration/**` (R2a).
- Engine renders any `ReportTemplateConfig` to PDF/CSV, permission-scoped to a task `SecurityUser` via F1's `getSecurityUser(ctx)` chokepoint (R2b).
- `@angular/cdk` (drag-drop) and TinyMCE / `tb-html` (rich text) are already ui-ngx deps — **no new FE deps**.
- R1 seed: a dashboard-only `report-template-dialog.component.ts`; a report-templates table at `/features/reports/templates`; `report.models.ts` (R1-shape — the typed component tree is **not** yet in TS).
- No preview endpoint exists yet.

**Out of scope (carried from R1/R2):** V1 `generate dashboard report` rule node (D2); microservice `Remote*` ctx/data; any change to the renderer opt-in posture.

## 1. Architecture & layout

A **full-page editor route** — a three-pane builder needs the room; R1's dialog is superseded as the create/edit entry.

- Route: `/features/reports/templates/:reportTemplateId` (edit) and `/features/reports/templates/new` (create). The R1 templates **table** stays; its row-edit + "add" now navigate to the editor page instead of opening the dialog.
- **Left — palette:** draggable component library (HEADING, RICH_TEXT, DIVIDER, PAGE_BREAK, IMAGE, ENTITY_TABLE, ALARM_TABLE, TIME_SERIES_TABLE, SUB_REPORT, DASHBOARD).
- **Center — canvas:** a CDK drag-drop ordered list of component **cards**. Drag from palette to insert, drag within to reorder, click to select. **Header** and **footer** are separate drop zones that host the `PAGE_NUMBER` / `CREATED_TIME` dynamic fields (not body components).
- **Right — config panel:** the selected component's config form, or page/template settings when nothing is selected.
- **Preview:** a toolbar `Edit ⇄ Preview` toggle. Preview swaps in the whole-template WYSIWYG pane (§2), refreshed debounced-on-change + a manual **Refresh** button.

**Designer state** is an in-memory typed `ReportTemplateConfig` tree (§3). Every edit mutates it; **Save** serializes it byte-compatible to the existing R1/R2 template controller (`POST/PUT /api/reportTemplate`); **Preview** POSTs it to the new endpoint (§2).

## 2. Backend — the preview endpoint (new; R2c is full-stack)

`POST /api/reportTemplate/preview`

- **Body:** a `ReportTemplateConfig` (the unsaved designer tree) + minimal render context (timezone; optionally a sample entity/originator for data components — plan-time detail). **Returns:** rendered **PDF** bytes (`application/pdf`), inline. (PDF over PNG-of-page-1 — decided 2026-07-24: shows true multi-page pagination, and the browser renders PDF natively.)
- **Gating:** the controller injects `Optional<TbReportService>` and rejects when the renderer is off with the **same HTTP status R1's existing `/report/request` gate returns** (confirm at plan time; default **501 Not Implemented**) — the same posture as R1's job-producing entrypoints. The designer detects this and shows an "enable the report renderer to preview" placeholder. **Save is unaffected renderer-off** — authoring needs no renderer.
- **Permission boundary:** the preview renders scoped to the **calling tenant-admin's own `SecurityUser`**. The request is already authenticated, so — unlike the scheduled/rule-node job path — **no server-minted access token is needed**; the endpoint reuses F1's `getSecurityUser(ctx)` chokepoint with the current user, so every data read a preview performs obeys the caller's own permissions.
- **`@PreAuthorize`:** TENANT_ADMIN (templates are tenant-authored). A customer user has no designer.
- **Bounds:** render timeout + output-size cap (reuse the engine's existing bounds); sub-report recursion bounds (R2b) apply unchanged.
- **Security:** the endpoint renders **attacker-influenceable** config, so it inherits all R2a/F1/F2 hardening (classpath-only image resolution, data-only `${var}` no-SSTI, permission-scoped data reads, sub-report depth+budget). It is a new externally-reachable surface ⇒ it gets its own adversarial security review (per the standing cadence).

This is the **only** backend change in R2c.

## 3. FE typed config model + serializer (the DoD anchor)

`report.models.ts` is R1-shape (no component tree). R2c builds a TypeScript mirror of the R2a Java `ReportTemplateConfig` tree — the root (discriminator `format: 'PDF' | 'CSV'`, `components[]`, page settings, header/footer) and each component type + its shared sub-objects (text style, insets, background/border, alignment). Likely a new `report-configuration.models.ts` alongside `report.models.ts`.

A **serializer** converts designer state ⇄ typed JSON, **byte-compatible** with the backend model (PE shape: nested DASHBOARD `config`, no `pageWidth`, `format` root). This is R2c's correctness anchor — covered by **karma specs** asserting round-trip byte-equivalence against fixtures the backend model accepts.

## 4. Component decomposition (~30–35 net-new Angular components)

| Group | Components (~count) | Notes |
|-------|--------------------|-------|
| Editor shell / layout | ~9 | root page, container, toolbar, tabs (content/data/layout), title, form, sections, page settings, settings-dialog |
| Palette + CDK drag-drop | ~7 | library, library-item (+ preview/title), draggable, placeholder, empty-state, drop-host |
| Component config panels | ~10 | HEADING, RICH_TEXT (`tb-html`/TinyMCE), DIVIDER, PAGE_BREAK (trivial), IMAGE (+ dialog), SUB_REPORT, one shared `tb-report-table*` for ENTITY/ALARM/TIME_SERIES, DASHBOARD **reuses R1 `tb-dashboard-report-config`** |
| Shared sub-editors | ~5 | text-style, `tb-report-insets` (margins/paddings), background/border, alignment, image dialog |
| Preview | ~2 | whole-template PDF preview pane + its renderer-off placeholder |
| Header/footer | ~2 | header/footer drop zones + `PAGE_NUMBER`/`CREATED_TIME` dynamic-field editors |

Net **~30–35** vs the parent spec's ~40 — the whole-template server preview removes PE's per-component preview widgets.

## 5. Sub-phasing — C1 then C2

Each sub-phase is its own plan → implement (per-task review) → verify → commit, mirroring how R2a/R2b ran.

**C1 — end-to-end skeleton + non-data components** (proves the whole architecture with a working subset, like R2a's "non-data renderers first"):
- Backend preview endpoint + gating + security.
- TS config model + serializer + karma specs.
- Editor shell (route, toolbar, page/template settings), palette + CDK DnD canvas, whole-template preview pane + renderer-off placeholder.
- Cheap component panels: PAGE_BREAK, DIVIDER, HEADING, RICH_TEXT, IMAGE + the shared sub-editors those need (text-style, insets, background/border, alignment, image dialog).
- i18n subset; route/menu wiring; table row-edit/add → navigate to the editor.
- **C1 DoD:** author a PDF template with heading + rich-text + image + divider in the designer, preview it (renderer-on), save it, and have the engine render it — end to end.

**C2 — data components + composition** (like R2b's data layer):
- The `tb-report-table*` family (ENTITY_TABLE / ALARM_TABLE / TIME_SERIES_TABLE sharing one config with a type switch + data-key/query/filter sub-forms).
- SUB_REPORT config (template picker + bounds already enforced server-side).
- DASHBOARD (slot in R1's `tb-dashboard-report-config`).
- Header/footer drop zones + `PAGE_NUMBER`/`CREATED_TIME` dynamic fields.
- Remaining i18n; full E2E.
- **C2 DoD:** author a multi-component template (heading + entity table + dashboard, with a header/footer) → renders to a valid PDF **and** CSV → delivered by email.

## 6. Data flow

Editor loads → GET template → deserialize `configuration` into the typed tree → render panes. Edit → mutate tree → (Preview mode) debounced POST `/api/reportTemplate/preview` → display returned PDF; (Save) serialize → `POST/PUT /api/reportTemplate`. Generate/deliver stay the existing R1/R2 paths (unchanged).

## 7. Error handling & degradation

- **Renderer off:** preview pane shows the placeholder; Save/authoring fully functional.
- **Preview render failure:** the engine already emits per-component `ErrorRenderer` boxes (R2a) — a bad component previews as an error box inside a valid PDF rather than failing the whole preview; a hard engine error surfaces as an inline message.
- **Invalid config:** client-side validators block Save; the serializer guarantees byte-compatible output (no partial/invalid JSON reaches the backend).

## 8. Design-fresh (not recoverable from the PE built bundle)

Recoverable from `ui-ngx-4.2.0PE.jar`: i18n `report-template.*` (~108 keys) and the `tb-report-*` selector tree. **Not** recoverable, designed fresh: exact validators, the paper-size enum→dimension table, drag/drop + canvas logic, header/footer preset composition, and (new to R2c) the whole-template preview integration.

## 9. Testing & DoD

- **FE:** karma serializer specs (state→JSON byte-match against backend-accepted fixtures); AOT production build green (catches template/type errors).
- **Backend:** preview-endpoint tests — renders when renderer-on, rejects (501) when off, permission-scoped to the caller, and the security-review regression cases.
- **Per-task adversarial security + code review** (standing directive) — the preview endpoint especially.
- **Overall DoD:** full reactor build + all existing reporting tests green; FE prod build + `license:check` green; INFERRIX-PATCHES ledger rows (preview controller = TB-core touch; FE route/menu/i18n touches); renderer opt-in preserved; C1 + C2 E2E above.

## 10. Decisions

- **E1 — fidelity → FULL PE three-pane drag-drop designer** (~30–35 comps). Chosen over a lean form-based editor: the user wants PE-fidelity UX + future PE-template import.
- **E2 — preview → SERVER-RENDERED** via a new endpoint. A browser can't run the Java PDF engine; exact fidelity over a client-side approximation. Makes R2c full-stack.
- **E3 — preview scope → WHOLE-TEMPLATE WYSIWYG** pane (one render/refresh; real pagination + header/footer). Removes PE's per-component previews.
- **E4 — preview format → PDF** (not PNG-of-page-1): true multi-page pagination, native browser rendering.
- **E5 — phasing → C1 (skeleton + non-data) then C2 (data + composition)**, each its own plan→implement→verify→commit.

## 11. Anticipated TB-core touches (ledger, at DoD)

- Preview endpoint: a method on the existing `ReportTemplateController` (Inferrix-owned R1 file) — **no** new TB-core row unless a new controller/route file is added. If `report.models.ts` / routing / menu / locale are extended, those are the same FE-touch classes as R30–R35 (re-verify at plan time).
- New additive FE paths: `ui-ngx/.../pages/report/report-template/designer/**` (the ~30–35 components), `report-configuration.models.ts`.
- The new REST route needs a ledger entry only if it lands in a TB-core-shared controller/route file; the designer components are additive.

## 12. Security review focus (for the preview endpoint)

`tenantId`/user scope is the caller's own `SecurityUser` (never body-supplied); renderer-gating prevents orphan/unavailable renders; the config body is attacker-influenceable ⇒ confirm R2a/F1/F2 hardening still holds on this path (classpath-only images, no SSTI, permission-scoped data, sub-report bounds); output-size + timeout bounds; no token minting (authenticated caller); `@PreAuthorize` TENANT_ADMIN.
