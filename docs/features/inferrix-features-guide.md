# Inferrix Platform — Feature Guide: White Labeling, Scheduler & Reporting

**Audience:** the Inferrix team — for using, testing, and configuring the three PE-replication features
delivered on branch `inferrix-release-4.3`.
**Scope:** White Labeling, Scheduler, Reporting. Every behavioural claim here was verified against the
current source tree (2026-07-30). Where a behaviour can only be confirmed on a running server, it is
marked **(live only)**.

Each feature section follows the same layout: **Overview → Configuration → Access & Permissions →
How to Use → How to Test → Known Limitations**. Start with the two shared sections below.

---

## ⚡ Configuration quick reference

All properties live in `application/src/main/resources/thingsboard.yml` and are env-overridable. Defaults
shown are the shipped defaults.

| Env var | Property | Default | Feature | What it does |
|---|---|---|---|---|
| `REPORTS_RENDERER_ENABLED` | `reports.renderer.enabled` | **`false`** | Reporting | **Master opt-in.** Off = no report can be generated (designer/preview degrade, scheduled/on-demand fail). Must be `true` to use Reporting at all. |
| `REPORTS_RENDERER_MAX_CONCURRENT` | `reports.renderer.max_concurrent` | `2` | Reporting | Headless-Chromium render pool size. |
| `REPORTS_RENDERER_BROWSER_PATH` | `reports.renderer.browser_path` | *(empty)* | Reporting | Path to a Chromium binary; empty = Playwright-managed Chromium. |
| `REPORTS_RENDERER_BASE_URL` | `reports.renderer.base_url` | `http://localhost:8080` | Reporting | TB UI URL the **headless browser** must reach to open dashboards. |
| `REPORTS_GENERATION_TIMEOUT_MS` | `reports.generation_timeout_ms` | `120000` | Reporting | Per-report render timeout. |
| `SCHEDULER_MIN_INTERVAL_IN_SEC` | `scheduler.min_interval` | `60` | Scheduler | Floor for `TIMER`-repeat events; a shorter interval is rejected with HTTP 400 on save. |
| `CACHE_SPECS_WHITE_LABELING_TTL` | `cache.specs.whiteLabeling.timeToLiveInMinutes` | `1440` | White Labeling | WL params cache TTL (24 h). |
| `CACHE_SPECS_WHITE_LABELING_MAX_SIZE` | `cache.specs.whiteLabeling.maxSize` | `10000` | White Labeling | WL params cache size. |
| `AUDIT_LOG_MASK_REPORT` / `_REPORT_TEMPLATE` | `audit-log.…mask.report` / `report_template` | `W` | Reporting | Audit masking level. |
| `AUDIT_LOG_MASK_SCHEDULER_EVENT` | `audit-log.…mask.scheduler_event` | `W` | Scheduler | Audit masking level. |

**Two infrastructure prerequisites for Reporting only:**
- **Headless Chromium** on the JVM host (the renderer drives a real browser in-process). Either allow
  Playwright to manage it, or set `REPORTS_RENDERER_BROWSER_PATH`. One-time managed install:
  `mvn -pl application exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`.
- **SMTP** (tenant mail settings) — only for **emailing** scheduled reports. Designer preview and on-demand
  download never send email.

White Labeling and Scheduler have **no feature flag** — they are always on for the allowed roles.

---

## ⚠️ Before you test — three behaviours that look like bugs but aren't

These are PE-faithful design choices that will read as "broken" if you don't know them. (One of them is
exactly what produced the earlier "scheduler not generating reports" report.)

1. **Reporting does nothing until the renderer is switched on.** `REPORTS_RENDERER_ENABLED` ships `false`.
   With it off: the template designer opens and saves fine, but **Preview** shows a placeholder, **on-demand
   dashboard download** returns HTTP 400, and **scheduled report** events log a warning and generate nothing.
   Set `REPORTS_RENDERER_ENABLED=true` (on a Chromium-capable host) before testing anything that produces a file.

2. **Most Scheduler event types fire but take no action out of the box.** `Update Attributes` and
   `Send RPC Request` events reach the tenant's rule chain as a message whose type has **no handler in the
   stock rule chain** — they land in the "Log Other" debug node and stop. No attribute is saved, no RPC is
   sent, **unless you add a rule-chain relation** for that message type. Only three types act on their own:
   `Update Firmware`, `Update Software` (direct device action), and `Generate Report` (native executor, needs
   the renderer on). `Generate Dashboard Report` currently has **no consumer at all** — it is a dead end.
   → When testing `Update Attributes`/`Send RPC`, confirm success by the **rule-chain debug log**, not by
   expecting the attribute/RPC to happen.

3. **White-Labeling login-page branding is System-scope only.** A Tenant or Customer admin can fill in and
   save the "Login page" tab, but the pre-auth `/login` page **only ever reads System-scope** settings — tenant
   /customer login branding never renders there. Also, the login **"Base URL"** field is saved but currently
   has no runtime effect. (In-app/post-login branding *does* honour Tenant/Customer scope — this caveat is
   specific to the `/login` screen.)

---

# 1. White Labeling

## 1.1 Overview
Re-brands the platform — logo, favicon, app title, colour palette, help links, platform name/version,
custom CSS, and a separate login-page look — with **3-tier inheritance: System → Tenant → Customer**. No
license/subscription gate; it is always on for the allowed roles.

- **Page:** left-nav **Settings → White labeling**, route `/settings/whiteLabeling`.
- **Runtime:** `WhiteLabelingRuntimeService` applies branding to the live DOM (logo subject, favicon
  `<link>`, injected `<style>` for custom CSS and the Material palette override). Applied post-login and,
  for the login page, pre-login via the anonymous endpoint `/api/noauth/whiteLabel/loginWhiteLabelParams`.

## 1.2 Configuration
- No feature flag. Only tunables are the two WL cache settings (see quick reference).
- No prerequisites — no image needs to pre-exist; the gallery picker can upload one inline.

## 1.3 Access & Permissions
| Role | WL access |
|---|---|
| **SYS_ADMIN** | System scope only (no scope selector shown). Full edit incl. Privacy Policy / Terms of Use. |
| **TENANT_ADMIN** | Own tenant scope; can switch to a customer scope via the scope selector. Full edit incl. legal tabs. |
| **CUSTOMER_USER** | Own customer scope only. Can edit branding, but **cannot upload or make images public** (gallery picks limited to already-public images — see limitations); no legal tabs. |

The scope selector (TENANT_ADMIN only) is an **"Edit customer settings"** toggle + a **raw Customer-ID text
box** (paste the customer UUID — it is not a dropdown).

## 1.4 How to Use
Open **Settings → White labeling**. Tabs:

**General tab**
- **Logo image** / **Favicon** — click **Browse from gallery** → the platform Image Gallery opens (pick an
  existing image or upload a new one) → the picked image is made public and its public link is stored. Image
  fields open the **gallery**, not the OS file browser.
- **Logo image height (px)**, **Application title** (browser-tab title).
- **Colour palette** — for **Primary** and **Accent**: an **"Extends palette"** dropdown (18 Material
  palettes) plus a 14-swatch shade grid; each swatch is a custom colour picker with a clear button. Blank
  swatches fall back to the extended palette's stock colour.
- **Help links** — help/UI base URLs + an "Enable help links" toggle.
- **Platform info** — **"Show platform name and version"** toggle; **"Platform name"** (becomes **required**
  when the toggle is on); **"Platform version"** (optional). Renders at the **bottom of the left nav** as
  `{name} v.{version}`.
- **Advanced** — **Custom CSS** (injected verbatim) and **Hide connectivity dialog** toggle.

**Login page tab** — independent login **Logo image** + height, **Login window color** (card),
**Page background color**, **Dark foreground text** toggle, **Show platform name at bottom** toggle, and a
**Base URL** field. *(Reminder: these render only at System scope on `/login`; Base URL is currently inert —
see §1.6.)*

**Privacy policy / Terms of use tabs** (SYS_ADMIN & TENANT_ADMIN) — HTML editors with their own Save button.

**Buttons (bottom):** **Save** (persists General + Login for the current scope), **Preview** (applies the
unsaved General settings live to your current in-app session — does **not** preview the Login tab),
**Delete** (confirm dialog "Reset white labeling settings?" → resets General + Login at the current scope;
does not touch the legal tabs).

## 1.5 How to Test (live only unless noted)
1. **Logo/favicon** — General → pick a gallery logo → Save. Expect the sidenav logo and browser-tab favicon
   to update immediately.
2. **Platform info footer** — enable "Show platform name and version" + fill Platform name → Save. Expect
   `{name} v.{version}` at the bottom of the left nav.
3. **Gallery logo on logged-out `/login`** — at **System** scope, set a logo → Save → open `/login` in an
   incognito window. Expect the logo to render (fetched anonymously). *This is the one path no automated test
   covers — verify on a real server.*
4. **Colour palette** — change Primary/Accent extends-palette or swatches → Save/Preview → expect the app's
   primary/accent chrome colour to change.
5. **Reset** — Delete → Yes → forms revert to inherited/blank.
6. **Scope inheritance** — set a value at System only, view as a Tenant admin with no override → expect the
   System value to show through in-app.
7. **Customer make-public 403** — as a CUSTOMER_USER, pick a **non-public** gallery image → expect the pick
   to silently not apply (a generic error toast may appear; the field keeps its previous value).

*Automated coverage that already exists:* `image-input.component.spec.ts` unit-tests the gallery-picker
contract (public pick, private-pick-makes-public, cancel, 403 handling, never emits the private ref, base64
round-trip).

## 1.6 Known Limitations & Gotchas
- **Login-page branding is System-scope only** — Tenant/Customer "Login page" edits are saved but never
  reach `/login` (the backend merge ignores tenant/customer scope for the login page). In-app branding still
  honours scope.
- **"Base URL" (Login tab) is inert** — persisted and validated, but nothing reads it at runtime
  (PE-compatibility field). Flag if you expected it to drive links/emails.
- **CUSTOMER_USER cannot upload or make images public** — limited to already-public gallery images.
- **Gallery-picked logo on `/login`** is statically verified end-to-end but needs one live logged-out check.
- **Customer-scope selector is a raw UUID text box**, not a dropdown — you must know the customer's ID.
- **Save/delete/preview toasts are hardcoded English** (locale keys exist but aren't used) — relevant only
  for non-English testing.
- **`Dark foreground text` does not inherit across scopes** (a child scope's value always wins).
- **Custom CSS is injected without client-side sanitisation** — CSS only (not script), but any WRITE-capable
  role (incl. CUSTOMER_USER) can inject CSS into every session loading their scope.

## 1.7 Reference
- FE page: `ui-ngx/src/app/modules/home/pages/white-labeling/` (`white-labeling`, `general-wl-settings`,
  `login-wl-settings`, `palette-settings`, `image-input`, `legal-content` components).
- FE runtime: `ui-ngx/src/app/core/services/white-labeling-runtime.service.ts`; HTTP client
  `ui-ngx/src/app/core/http/white-labeling.service.ts`.
- Backend: `application/.../controller/WhiteLabelingController.java`; `dao/.../wl/DefaultWhiteLabelingService.java`;
  models `common/data/.../wl/*`.
- Key endpoints: `GET /api/noauth/whiteLabel/loginWhiteLabelParams` (anon), `GET/POST/DELETE
  /api/whiteLabel/currentWhiteLabelParams`, `/currentLoginWhiteLabelParams`, `POST
  /api/whiteLabel/previewWhiteLabelParams`. Public-image infra reused: `GET /api/images/public/{key}` (anon),
  `PUT /api/images/{type}/{key}/public/{bool}` (admin only).

---

# 2. Scheduler

## 2.1 Overview
Tenant/customer-scoped **scheduler events** that fire at a configured time (one-shot or repeating) and
dispatch an action: push a message to the rule engine, assign OTA firmware/software, or generate a report.
PE-replication; the engine is always on (no feature flag).

- **Page:** **Advanced features → Scheduler** for tenant admins (a top-level nav entry for customer users),
  route `/features/scheduler`. List view + Calendar view (toggle, no route change).
- **Engine:** `DefaultSchedulerService` — partition-aware (cluster ownership fails over automatically),
  re-arms after each fire, and never stalls on a failing event.

## 2.2 Configuration
- **`SCHEDULER_MIN_INTERVAL_IN_SEC`** (default 60) — floor for `TIMER` repeats; enforced **server-side only**
  (the editor does not pre-check it — a too-frequent interval is rejected with HTTP 400 on save).
- **Audit mask** `AUDIT_LOG_MASK_SCHEDULER_EVENT` (default `W`).
- **DB:** the `scheduler_event` table ships in the Inferrix schema overlay — a normal install/upgrade creates
  it automatically; nothing extra to run.
- **`Generate Report` cross-dependency:** that event type only submits a job if
  `REPORTS_RENDERER_ENABLED=true` (see Reporting).

## 2.3 Access & Permissions
| Role | Access |
|---|---|
| **TENANT_ADMIN** | Full CRUD, all event types. Optional Customer picker to scope an event to a customer. |
| **CUSTOMER_USER** | Full self-service CRUD, scoped to own customer. `Generate Report` type is hidden in the UI (**UI-only** — not blocked at the API). |
| **SYS_ADMIN** | No access (403). |

## 2.4 How to Use
**List view** — table (Created time, Name, Type, Customer, Enable scheduler). Row actions: edit, delete,
enable/disable. **+** adds an event.
**Calendar view** — FullCalendar with month/week/day/agenda/list sub-views; click an occurrence to
edit/view/delete; drag an occurrence to reschedule.

**Create/edit dialog fields:**
- **Name** (required), **Event type** (required — 6 options below), optional **Customer** (tenant admin only),
  **Enable scheduler** (default on).
- **Schedule:** **Timezone** (required), **Start time** (required), **Repeat** checkbox. When repeating:
  **Repeats** = Daily / Every N days / Weekly (with day-of-week checkboxes) / Every N weeks / Monthly /
  Yearly / **Timer-based** (interval + Seconds/Minutes/Hours). Every repeat requires an **Ends on** date
  (there is no "never ends"). *No CRON type exists* (PE 4.2 parity).

**Event types & what they do:**
| Type (label) | Config | Acts on its own? |
|---|---|---|
| **Generate Report** | native form: report template, recipient user, timezone, notification targets, notification template | **Yes** — but needs `REPORTS_RENDERER_ENABLED=true` |
| **Update Firmware** / **Update Software** | originator (Device / Device profile) + OTA package id | **Yes** — sets the device's firmware/software directly |
| **Update Attributes** | originator + attribute scope + key/values | **No** — needs a rule-chain relation for msg type `updateAttributes` (else Log-Other only) |
| **Send RPC Request to Device** | device + method + params + one-way/persistent/timeout | **No** — needs a rule-chain relation for msg type `sendRpcRequest` |
| **Generate Dashboard Report** | generic message-type/body/metadata editor | **No consumer today** — dead end (see limitations) |

## 2.5 How to Test
- **One-shot** — create an event, Repeat off, Start time a couple minutes out. At the time, the engine logs
  `Firing scheduler event`. For `Update Attributes`/`Send RPC`, **verify via rule-chain debug** (they hit
  "Log Other" in a stock chain — see the trap in the intro), or add a rule-chain relation first.
- **Recurring** — a `TIMER` event with a 60 s interval + `Update Firmware`/`Update Software` gives a directly
  observable result each cycle (the device's firmware assignment changes) with no rule-chain dependency —
  the easiest "watch it fire repeatedly" test.
- **Min-interval floor** — try to save a `TIMER` event with a 10 s interval → expect HTTP 400
  ("Timer-based repeats are too frequent").
- **Generate Report** — needs `REPORTS_RENDERER_ENABLED=true`; then a fired event submits a report job (see
  Reporting §3 for verifying the output/email).
- **Timezone / DST** — set a near-DST start for a Daily repeat and confirm wall-clock time is preserved.
  Unknown/malformed timezones silently fall back to UTC.

*Automated coverage:* `DefaultSchedulerServiceTest` (fires update-attributes/RPC as rule-engine msgs, OTA sets
firmware, timer re-arms after error), `SchedulerEventControllerTest` (customer scoping, sys-admin 403,
too-frequent-timer 400).

## 2.6 Known Limitations & Gotchas
- **`Update Attributes` / `Send RPC` do nothing without custom rule-chain wiring** (the headline trap — see
  intro). Stock chain routes them to a "Log Other" debug node.
- **`Generate Report` no-ops silently when the renderer is off** — a server WARN only, no UI error; the event
  still "fires" each cycle. The WARN names the remedy (`REPORTS_RENDERER_ENABLED=true`).
- **`Generate Dashboard Report` is a dead end today** — no rule node consumes it and the stock chain has no
  relation for it; its config hint ("activates when the Reporting module is installed") is stale. Use
  **`Generate Report`** (the native type) for scheduled reports.
- **`Generate Report` hidden-from-customer is UI-only** — a customer user can still create one via direct REST.
- **Min-interval floor has no client-side pre-check** — discovered only as a 400 on save.
- **One-shot events are dropped if their owning node is down when due** (no catch-up on failover).

## 2.7 Reference
- Engine: `application/.../service/scheduler/DefaultSchedulerService.java`; report seam
  `.../scheduler/report/InferrixSchedulerReportExecutor.java`.
- Controller: `application/.../controller/SchedulerEventController.java` (base `/api/schedulerEvent[s]`);
  validator `dao/.../service/validator/SchedulerEventDataValidator.java`.
- FE: `ui-ngx/src/app/modules/home/pages/scheduler/*`; models `shared/models/scheduler-event.models.ts`.
- Config: `thingsboard.yml` `scheduler:` block; DB `dao/src/main/resources/sql/schema-inferrix.sql`.

---

# 3. Reporting

## 3.1 Overview
Renders a **report template** (a layout + data-component tree, PE-replicated) to **PDF** or **CSV** via three
entry points:
1. **Report-template designer** — author/edit/preview reusable templates (TENANT_ADMIN).
2. **On-demand dashboard "Download Report"** — render the dashboard currently open in the browser, ad hoc
   (TENANT_ADMIN or CUSTOMER_USER).
3. **Scheduled reports** — a Scheduler `Generate Report` event fires a template on a schedule and emails the
   result.

- **Pages:** **Features → Reports → Report History / Report Templates**, routes `/features/reports/history`
  and `/features/reports/templates` (TENANT_ADMIN only). The designer is the full-page editor at
  `/features/reports/templates/new` and `/…/:id`.
- **Formats:** PDF (Playwright/HTML → openpdf) and CSV. CSV output is formula-injection-hardened (leading
  `= + - @` cells are neutralised). The on-demand dashboard path also supports PNG/JPEG.

## 3.2 Configuration — read this first
Reporting is **opt-in and off by default.** Set **`REPORTS_RENDERER_ENABLED=true`** and provide headless
Chromium (see the quick reference). Other `reports.*` tunables: pool size, browser path, base URL, timeout.
**SMTP** is required only to email scheduled reports.

**Exactly what happens with the flag OFF:**
- **Designer** opens; drag/configure/reorder/**Save** all work. Only **Preview** degrades → a placeholder
  ("Enable the report renderer to preview this template"), not an error.
- **On-demand download** → HTTP 400 "Report renderer is not enabled".
- **Scheduled `Generate Report`** → a server WARN, no job.
- Any submitted report **job** (rule node / REST / the scheduler's own) → the job **FAILS** with
  `IllegalStateException("Report renderer is not enabled")` (visible as a failed Job, not a silent drop).

## 3.3 Access & Permissions
- **Designer, templates, report history pages:** TENANT_ADMIN only (UI routes are tenant-admin-gated).
- **Template CRUD, report download/history, preview:** TENANT_ADMIN.
- **On-demand dashboard "Download Report":** TENANT_ADMIN **or** CUSTOMER_USER (needs READ on that dashboard) —
  reached via the dashboard toolbar, since the report *pages* themselves are tenant-admin-only.

## 3.4 How to Use
**(a) Designer** (`/features/reports/templates/new`) — three panes: **palette** (left), **canvas** (center),
**config / page-settings** (right); toolbar has name, **Edit/Preview** toggle, **Import/Export**, **Save**.
- **New templates are always PDF.** There is no format toggle in the UI — a **CSV** template can only be
  created by **importing** a previously-exported CSV template JSON.
- **Palette:** 10 component types — Heading, Rich Text, Divider, Page Break, Image, Entity Table, Alarm Table,
  Time Series Table, Sub-report, Dashboard — plus 13 ready-made **Presets** (page-number/created-time headings
  and logo/footer text blocks).
- **Page settings** (right pane when nothing is selected; PDF only): page size (A4/Letter/Legal/A5/A3/Tabloid),
  orientation, margins, background, name pattern, time pattern, and **Header/Footer** (each a nested canvas,
  with an optional "different first page").
- **Dynamic fields** (on Heading / Rich Text): `${pageNumber}`, `${totalPages}`, `${reportCreatedTime}`,
  `${entityName}`, `${entityLabel}`, `${id}`.
- **Import/Export** — Export downloads `<name>.json`; Import loads it into the editor for review (never
  auto-saves). **Preview** renders via the renderer; **Save** stores the template.

**(b) On-demand "Download Report"** — the **download** button on a dashboard's toolbar opens a dialog
pre-filled with the current state + timewindow: **Report type** (pdf/png/jpeg), **timezone**, **Dashboard
state**, **Time window**. Confirm → the file downloads.

**(c) Scheduled reports** — create a Scheduler **`Generate Report`** event (report template + recipient user +
timezone + notification target/template). See the Scheduler section for scheduling; the report is generated
and emailed to the target when it fires (renderer + SMTP required).

## 3.5 How to Test
Renderer **ON** + Chromium needed for 2, 4–6; SMTP for 5. The authoritative live scripts are
`.superpowers/sdd/c1-e2e-checklist.md` and `c2-e2e-checklist.md`.
1. **Author** a template (drag components, header/footer, dynamic fields) — no renderer needed.
2. **Preview a PDF** — confirm components render in order, dynamic fields are substituted, a dashboard
   component renders as an embedded image. (Off → placeholder, no crash.)
3. **Save + reopen** — the template round-trips unchanged.
4. **Generate via scheduler** — a `Generate Report` event → a report appears in Report History and downloads
   as PDF/CSV.
5. **Email delivery** (SMTP) — the report attaches to the notification target's email.
6. **On-demand download from a MULTI-STATE dashboard, on a NON-ROOT state** — this is the specific condition
   the recent empty-report fix targets; confirm the file reflects **that state's** data, not an empty root
   state. (A root-state-only test won't catch a regression here.)
7. **Renderer-disabled sanity** (no Chromium) — designer opens/saves; Preview shows the placeholder;
   on-demand returns 400; a submitted report job FAILS cleanly rather than hanging.

## 3.6 Known Limitations & Gotchas
- **Renderer off by default** — the whole feature is inert until it's enabled + Chromium is provisioned.
- **New templates are always PDF; CSV is import-only** — no format toggle in the designer.
- **On-demand empty-data fix is targeted** (non-root state on a multi-state dashboard) — test that exact case.
- **Dashboard component renders as a static image (PNG)**, not a live embed.
- **Live end-to-end (real Chromium) is operator-pending** — the checklists are unexecuted scripts; a real
  render has not been driven in CI/build.
- **Two same-named `ReportService` FE classes** exist (a browser protocol driver vs an HTTP client) — a
  code-level note, not user-facing.

## 3.7 Reference
- Backend: `application/.../controller/{ReportController,ReportTemplateController,DashboardReportController}.java`;
  `application/.../service/report/**` (`TbReportService`, `PdfReportService`, `CsvReportService`,
  `ReportJobProcessor`, `render/PlaywrightWebReportRenderer.java`, `util/CsvUtils.java`).
- FE: `ui-ngx/src/app/modules/home/pages/report/**` (designer under `report-template/designer/`); on-demand
  dialog under `…/dashboard-page/report/`.
- Key endpoints: `POST /api/report/{dashboardId}/download` (on-demand), `POST /api/v2/report/test` (designer
  preview), `GET/POST/DELETE /api/reportTemplate/{id}`, `GET /api/v2/report/{id}/download`.
- Config: `thingsboard.yml` `reports:` block.

---

## Appendix — operator-pending live validations
These are correct-by-construction / statically verified but not yet confirmed on a running server:
- **Reporting:** any real render (needs the renderer on + Chromium); email delivery (needs SMTP); the
  multi-state on-demand download.
- **White Labeling:** a gallery-picked logo rendering on a real logged-out `/login`.
- **Scheduler:** full click-through on a live instance (unit/controller tests pass; live E2E was left
  operator-pending).

*This guide reflects the code as of 2026-07-30 on `inferrix-release-4.3`. If a behaviour here doesn't match a
running system, the running system's config (especially `REPORTS_RENDERER_ENABLED`) is the first thing to check.*
