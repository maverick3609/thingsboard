# Inferrix Patches — ThingsBoard-core modification ledger

Tracks every file in upstream ThingsBoard that Inferrix features have modified.
The purpose is **merge survival**: after each `git merge upstream/release-4.3`,
walk this ledger and verify every patch is still present (an upstream rewrite
of a file can silently revert our changes).

- **Baseline:** `upstream/release-4.3` (client version 4.3.1.3, last merged `684abbf911` on 2026-07-08; upstream tip `1053516151`). Upstream periodically **rebuilds** this branch's history, so each sync re-reconciles against a 2017 merge base (~68 conflicts) rather than a routine fast-forward — expect Bucket-A ledger conflicts, Bucket-B take-theirs, delete/delete removals, and spurious `wl/*.java` rename/rename conflicts.
- **Integration branch:** `inferrix-release-4.3`
- **Overlay paths (additive, not tracked here):** `inferrix-reporting/`, `ui-ngx/src/inferrix/`, `.claude/`, `INFERRIX.md`

A file goes in this ledger only if it existed in upstream and we changed it.
Wholly new files/directories under Inferrix-owned paths don't belong here.

---

## Feature: Reporting & Scheduling

- **Module:** `inferrix-reporting/` (new Spring Boot auto-config module; includes `scheduler/` package — `NextRunCalculator`, `ReportSchedulerService`)
- **UI:** `ui-ngx/src/inferrix/modules/reporting/` (new Angular overlay)
- **Status:** committed on `master` (commits `3cc5c003c0`, `86ba517d39`, `2df88f6c7b`). **Not yet on `inferrix-release-4.3`** — needs forward-port.

### TB-core files modified (6)

| # | File | Change | Why |
|---|------|--------|-----|
| 1 | `pom.xml` | (a) add `<module>inferrix-reporting</module>` to `<modules>` block; (b) add `<exclude>inferrix-reporting/**</exclude>` to `license-maven-plugin` excludes | Register the new Maven module + exempt Inferrix-owned code from Apache license header check |
| 2 | `application/pom.xml` | Add `<dependency>` on `com.inferrix:inferrix-reporting:${project.version}` | Pull the reporting module into the boot jar |
| 3 | `ui-ngx/tsconfig.json` | Add `"@inferrix/*": ["src/inferrix/*"]` to `compilerOptions.paths` | Path alias for Inferrix-overlay imports |
| 4 | `ui-ngx/src/app/core/services/menu.models.ts` | (a) add `reports` to `MenuId` enum; (b) add `MenuId.reports → 'Reports'` entry to `menuSectionMap`; (c) add `{id: MenuId.reports}` after `dashboards` in TENANT_ADMIN section of `defaultUserMenuMap` | Surface Reports in the left sidebar for tenant admins |
| 5 | `ui-ngx/src/app/modules/home/pages/home-pages.module.ts` | (a) `import { InferixReportingModule } from '@inferrix/modules/reporting/inferrix-reporting.module'`; (b) add `InferixReportingModule` to NgModule `exports` array | Register the reporting Angular module so its routes/components are available |
| 6 | `ui-ngx/yarn.lock` | Regenerated on `yarn install` | Auto-managed; no manual merge needed beyond `yarn install` after sync |

### Merge-recovery procedure

If any of the above show as conflicted or reverted after merging `upstream/release-4.3`:

1. **`pom.xml`** — check that `<module>inferrix-reporting</module>` and the license exclude are still present. Re-apply if missing.
2. **`application/pom.xml`** — check that the `com.inferrix:inferrix-reporting` dependency block is still present in `<dependencies>`. Re-apply if missing.
3. **`ui-ngx/tsconfig.json`** — verify the `@inferrix/*` path alias survives. If upstream restructured `paths`, re-insert.
4. **`ui-ngx/src/app/core/services/menu.models.ts`** — three insertion points; verify all three (enum entry, sectionMap entry, defaultUserMenuMap reference). Upstream regularly adds new menu items to these structures, so this is the highest-risk file in the ledger.
5. **`ui-ngx/src/app/modules/home/pages/home-pages.module.ts`** — verify the import and the entry in `exports`. Upstream may add new modules to the same arrays, so check ordering doesn't drop our entry.
6. **`ui-ngx/yarn.lock`** — run `yarn install` to regenerate; no manual merge.

---

## Feature: White Labeling

- **New overlay paths (additive, not tracked below):**
  - `common/data/.../wl/*` (domain models: `WhiteLabeling`, `WhiteLabelingParams`, `LoginWhiteLabelingParams`, `Palette`, `PaletteSettings`, `Favicon`, `WhiteLabelingType`)
  - `dao/.../wl/*` + `dao/.../sql/wl/*` (DAO + JPA repo + entity)
  - `application/.../controller/WhiteLabelingController.java`
  - `ui-ngx/.../core/http/white-labeling.service.ts`, `.../core/services/white-labeling-runtime.service.ts`
  - `ui-ngx/.../home/pages/white-labeling/*` (Angular page + sub-components)
  - `ui-ngx/.../shared/models/white-labeling.models.ts`
- **Status:** 15 commits on `inferrix-release-4.3` — fully committed. Latest: `2248c8bc55 feat(wl): add permission wiring, transactional cache, and legal-content support`.

### TB-core files modified (23)

#### Backend (10)

| # | File | Change | Why |
|---|------|--------|-----|
| 1 | `application/src/main/data/upgrade/basic/schema_update.sql` | Append `CREATE TABLE IF NOT EXISTS white_labeling (...)` block at end, wrapped in `-- WHITE LABELING TABLE START/END` comments | Upgrade path for existing installs |
| 2 | `application/src/main/java/org/thingsboard/server/controller/BaseController.java` | (a) `import org.thingsboard.server.dao.wl.WhiteLabelingService;` near other dao imports; (b) `@Autowired protected WhiteLabelingService whiteLabelingService;` field, after `apiKeyService` | Make WL service available to all controllers via inheritance |
| 3 | `application/src/main/java/org/thingsboard/server/service/security/permission/Resource.java` | Add `WHITE_LABELING,` enum entry between `MOBILE_APP_SETTINGS` and `JOB(EntityType.JOB)` | Declare the WL resource for permission checks |
| 4 | `application/src/main/java/org/thingsboard/server/service/security/permission/CustomerUserPermissions.java` | `put(Resource.WHITE_LABELING, new PermissionChecker.GenericPermissionChecker(Operation.READ, Operation.WRITE));` after `MOBILE_APP_SETTINGS` entry | Customer users can read/write their own WL settings |
| 5 | `application/src/main/java/org/thingsboard/server/service/security/permission/SysAdminPermissions.java` | `put(Resource.WHITE_LABELING, PermissionChecker.allowAllPermissionChecker);` after `MOBILE_APP_SETTINGS` entry | Sys admin full WL control |
| 6 | `application/src/main/java/org/thingsboard/server/service/security/permission/TenantAdminPermissions.java` | `put(Resource.WHITE_LABELING, PermissionChecker.allowAllPermissionChecker);` after `MOBILE_APP_SETTINGS` entry | Tenant admin full WL control |
| 7 | `application/src/main/resources/thingsboard.yml` | Add `whiteLabeling` block under `cache.specs:` with `timeToLiveInMinutes` and `maxSize` envs `CACHE_SPECS_WHITE_LABELING_TTL` / `CACHE_SPECS_WHITE_LABELING_MAX_SIZE` | WL cache config (cache name matches `CacheConstants.WHITE_LABELING_CACHE`) |
| 8 | `common/data/src/main/java/org/thingsboard/server/common/data/CacheConstants.java` | Add `public static final String WHITE_LABELING_CACHE = "whiteLabeling";` as the last constant | Cache name constant referenced by DefaultWhiteLabelingService |
| 9 | `dao/src/main/java/org/thingsboard/server/dao/model/ModelConstants.java` | Add a `White labeling constants.` block with `WHITE_LABELING_TABLE_NAME`, `WHITE_LABELING_SETTINGS_PROPERTY`, `WHITE_LABELING_TYPE_PROPERTY`, `WHITE_LABELING_DOMAIN_ID_PROPERTY`, after the API key constants block (~line 762) | Schema constants for the JPA entity |
| 10 | `dao/src/main/resources/sql/schema-entities.sql` | Append `CREATE TABLE IF NOT EXISTS white_labeling (...)` at end of file | Schema for fresh installs |

#### Frontend (13)

| # | File | Change | Why |
|---|------|--------|-----|
| 11 | `ui-ngx/src/app/core/auth/auth.service.ts` | (a) import `WhiteLabelingHttpService` and `WhiteLabelingRuntimeService`; (b) inject both in constructor; (c) in `notifyAuthenticated`, call `wlHttp.getWhiteLabelParams({ignoreErrors:true}).subscribe(params => wlRuntime.applyParams(params), () => {})` | Load tenant WL params right after login |
| 12 | `ui-ngx/src/app/core/http/public-api.ts` | Append `export * from './white-labeling.service'` | Public-API surface for the WL HTTP service |
| 13 | `ui-ngx/src/app/core/services/menu.models.ts` | **Five insertion points** (highest-risk file): (a) `white_labeling = 'white_labeling'` in `MenuId` enum; (b) `menuSectionMap` entry mapping `MenuId.white_labeling` → name `'white-labeling.white-labeling'`, path `/settings/whiteLabeling`, icon `format_paint`; (c) add `{id: MenuId.white_labeling}` to SYS_ADMIN settings section; (d) add `{id: MenuId.white_labeling}` to TENANT_ADMIN settings section; (e) add new `{id: MenuId.settings, pages: [{id: MenuId.white_labeling}]}` block to CUSTOMER_USER menu | Sidebar entry for sysadmin, tenant admin, and customer user |
| 14 | `ui-ngx/src/app/core/services/title.service.ts` | (a) import + inject `WhiteLabelingRuntimeService`; (b) in `setTitle`, compute `const appTitle = wlRuntime.currentAppTitle || env.appTitle` and use `appTitle` in both `title.setTitle` calls | Dynamic browser tab title from WL settings |
| 15 | `ui-ngx/src/app/modules/home/components/dashboard-page/dashboard-page.component.ts` | (a) import + inject `WhiteLabelingRuntimeService`; (b) in `ngOnInit`, push `wlRuntime.logo$.subscribe(url => this.defaultDashboardLogo = url)` into `rxSubscriptions` (must be FIRST entry — runs before route.data subscribe) | Dynamic dashboard logo |
| 16 | `ui-ngx/src/app/modules/home/home.component.ts` | (a) import + inject `WhiteLabelingRuntimeService`; (b) in `ngOnInit`, subscribe to `wlRuntime.logo$.pipe(takeUntil(this.destroy$))` and set `this.logo = url` (must be FIRST line in ngOnInit) | Dynamic sidebar logo |
| 17 | `ui-ngx/src/app/modules/home/pages/admin/admin-routing.module.ts` | (a) import `WhiteLabelingComponent`; (b) on `/settings` route data: add `Authority.CUSTOMER_USER` to both `auth` arrays (parent + index child) and add `CUSTOMER_USER: '/settings/whiteLabeling'` to `redirectTo`; (c) add a new child route `{path: 'whiteLabeling', component: WhiteLabelingComponent, ...}` after `...aiModelRoutes` | Customer users can hit `/settings/whiteLabeling`; nests WL page under the existing settings shell |
| 18 | `ui-ngx/src/app/modules/home/pages/home-pages.module.ts` | (a) `import { WhiteLabelingModule } from '@home/pages/white-labeling/white-labeling.module';` (b) add `WhiteLabelingModule` to NgModule `exports` | Register WL page module |
| 19 | `ui-ngx/src/app/modules/login/pages/login/login.component.html` | Replace `<tb-logo link="https://thingsboard.io" target="_blank" class="login-logo">` with `<tb-logo [src]="loginLogo" target="_blank" class="login-logo">` | Login screen logo is data-bound, not hardcoded |
| 20 | `ui-ngx/src/app/modules/login/pages/login/login.component.ts` + `.html` | (a) import `WhiteLabelingHttpService`, `WhiteLabelingRuntimeService`, `LoginWhiteLabelingParams`; (b) add `loginLogo`, `loginLogoHeight`, `loginCardColor`, `pageBackgroundColor`, `darkForeground` fields with defaults; (c) inject the two WL services; (d) in `ngOnInit`, call `wlHttp.getLoginWhiteLabelParams` and apply params + assign fields; (e) in the template: `tb-logo` gets `[src]` + `[style.height.px]`, the outer `.tb-login-content` div gets `[class.tb-dark]="!darkForeground"` (static `tb-dark` class removed) + `[style.background-color]="pageBackgroundColor"`, and the `mat-card` gets `[style.background-color]="loginCardColor"` | Login page reads its own WL params before authentication and applies logo/size/card color/page background |
| 20b | `ui-ngx/src/app/shared/components/logo.component.scss` | Inside the `:host-context(.login-logo)` block add `img { object-fit: contain; }` | Custom login logos keep aspect ratio in the fixed 280px-wide slot |
| 21 | `ui-ngx/src/app/shared/components/footer.component.html` | Replace single `<small>Copyright © {{year}} The ThingsBoard Authors</small>` with a conditional `*ngIf="(showNameVersion$ \| async) && (platformName$ \| async) as name; else defaultFooter"` and a `#defaultFooter` template that preserves the original | Footer text comes from WL settings when available |
| 22 | `ui-ngx/src/app/shared/components/footer.component.ts` | (a) import + inject `WhiteLabelingRuntimeService`; (b) expose `showNameVersion$ = wlRuntime.showNameVersion$` and `platformName$ = wlRuntime.platformName$` as fields | Bind the observables consumed by the template |
| 23 | `ui-ngx/src/assets/locale/locale.constant-en_US.json` | Add the top-level `"white-labeling": { ... }` i18n section (inserted between `version-control` and `widget`) containing the menu label key `white-labeling.white-labeling` plus all WL page labels | i18n for the White-labeling menu entry, breadcrumb, and settings page. (Was dropped once already — 2026-07 merge — and rediscovered on the deployed server as raw `white-labeling.white-labeling` text. The earlier row text wrongly said a single key in a `functions` section; the code references `white-labeling.*` keys.) This is the ONLY Inferrix content in a 10k-line file upstream churns constantly, so it is trivially dropped on merge — the merge guard must grep this file's "ours" side for `white-labeling` before take-theirs |

### Merge-recovery procedure (WL-specific notes)

- **`menu.models.ts`** is the single highest-risk file in the whole ledger (5 insertion points; upstream regularly touches these maps). Always diff this file by hand after merging.
- **`BaseController.java`** is a hub class — upstream adds `@Autowired` fields here frequently. Make sure our `whiteLabelingService` field survives.
- **`admin-routing.module.ts`** — three sub-changes on the same `/settings` route. Verify all three (`auth` array, `redirectTo` map, new child route).
- **`Resource.java`** — upstream adds enum values here regularly. Verify `WHITE_LABELING,` survives in its expected position (between `MOBILE_APP_SETTINGS` and `JOB`).
- **`schema_update.sql`** and **`schema-entities.sql`** both create the `white_labeling` table — confirm both still contain the block (upstream may rewrite ordering).
- **`locale.constant-en_US.json`** — the `functions.white-labeling` key is the only Inferrix content in this 10k-line file; upstream churns it every release and it does NOT stand out by name in a big diff. The merge procedure greps each Bucket-B "ours" side for `inferrix|whitelabel|white-labeling|...` precisely to catch this file before a blind take-theirs. Keep that guard.

---

## Feature: Default Inferrix Cortex Branding

Bakes Inferrix branding into the default build (every `yarn build:prod` output is Inferrix-branded — no white-labeling runtime config involved). Brand colors: primary blue `#2b388f`, red `#ec1c24` (sampled from `docs/images/FF_INFERRIX_slogan black.png`).

| # | File | Change | Why |
|---|------|--------|-----|
| B1 | `ui-ngx/src/assets/logo_title_white.svg` | Entire file replaced with an SVG-wrapped white-text variant of the Inferrix slogan logo (blue→white, red kept; embedded base64 PNG) | Default logo for login page, sidebar, dashboards — rendered on dark chrome |
| B2 | `ui-ngx/src/assets/logo_white.svg` | Entire file replaced with the icon-only white variant (radial mark) | Icon-only brand mark |
| B3 | `ui-ngx/src/thingsboard.ico` | Binary replaced with Inferrix favicon (`docs/images/favicon.ico`) | Browser tab icon; filename kept so `index.html` link and angular.json assets need no change |
| B4 | `ui-ngx/src/index.html` | `<title>ThingsBoard</title>` → `<title>Inferrix Cortex</title>` | Initial tab title before Angular boots |
| B5 | `ui-ngx/src/environments/environment.ts` + `environment.prod.ts` | `appTitle: 'ThingsBoard'` → `appTitle: 'Inferrix Cortex'` | Runtime page titles |
| B6 | `ui-ngx/src/scss/constants.scss` | `$tb-primary-color: #2b388f`, `$tb-secondary-color: #4553a8`, `$tb-hue3-color: #b0b7e6` | Theme primary (light 500/600, dark surface 800 all derive from these) |
| B7 | `ui-ngx/src/theme.scss` | New `$tb-mat-inferrix-red` palette map (500 `#ec1c24`) inserted before `$tb-primary`; `$tb-accent` now `mat.m2-define-palette($tb-mat-inferrix-red)` instead of deep-orange | Accent color = brand red |
| B8 | 13 component files: `script-lang`, `markdown`, `widget-button-toggle`, `alarm-rule-filter-text`, `image-cards-select`, `getting-started-widget`, `getting-started-completed-dialog`, `recent-dashboards-widget`, `rate-limits-text`, `filter-text`, `rulechain-page`, `device-check-connectivity-dialog` (.scss) + `notification.component.ts`, `sent-table-config.resolver.ts` | Hardcoded `#305680` → `#2b388f` | Stray old-primary accents in component styles |

| B9 | `ui-ngx/src/app/modules/home/home.component.html` | Removed the `<tb-github-badge class="lt-md:!hidden">` line from the primary toolbar | No "Star on GitHub" button in a branded product |
| B10 | `ui-ngx/src/app/modules/home/home.component.scss` | `.tb-logo-title` height `36px` → `52px` | Larger sidebar logo (header is 64px tall) |
| B11 | `ui-ngx/src/app/modules/home/components/dashboard-page/dashboard-page.component.html` | Dashboard footer span: `Powered by <a href="https://thingsboard.io">ThingsBoard v.{{ thingsboardVersion }}</a>` → `Powered by <a href="https://www.inferrix.com">Inferrix Cortex v.{{ thingsboardVersion }}</a>` | Branded dashboard footer linking to inferrix.com |
| B12 | `ui-ngx/src/assets/locale/locale.constant-en_US.json` | Both `dashboard.socialshare-*` values: "powered by ThingsBoard" → "powered by Inferrix Cortex" | Branded share text (same merge-guard caveat as row 23) |
| B13 | `application/src/main/java/org/thingsboard/server/service/install/DefaultSystemDataLoaderService.java` | Default account emails `sysadmin/tenant/customer/customerA/customerB/customerC@thingsboard.org` → `@inferrix.com` (6 `createUser` calls, ~lines 201/353/367-370) | Fresh installs create Inferrix-domain logins. Existing installs must rename users via REST/DB (done on .77 on 2026-07-13) |
| B14 | `TwoFactorAuthConfigController.java`, `LoginRequest.java` (application), `AuditLog.java` (common/data) | Swagger `example` email strings `tenant@thingsboard.org` / `separate-email-for-2fa@thingsboard.org` → `@inferrix.com` | API docs show Inferrix-domain examples. (`OtaPackageInfo.java` `http://thingsboard.org/fw/1` URL example intentionally left) |
| B15 | `pom.xml` (root, license-maven-plugin config ~line 878) + new root file `license-header-template-thingsboard.txt` | (a) `<owner>` property → `The Inferrix Authors`; (b) `<validHeaders><validHeader>${main.dir}/license-header-template-thingsboard.txt</validHeader></validHeaders>` added before `<properties>` | New files get Inferrix-owner Apache headers; the validHeader (hardcoded `2016-2026 The Thingsboard Authors`) keeps the ~20k legacy files passing `license:check` without a repo-wide reformat. On merge: keep both (owner + validHeaders block); upstream may reorder the plugin config |

**Intentionally NOT changed** (persisted widget-content defaults, not chrome): `widget-action-dialog.component.ts`, `unread-notification-widget.models.ts`, `segmented-button-widget.models.ts`, `value-stepper-widget.models.ts`, and the mobile-page preview HTML mocks.

**Merge recovery:** all rows are simple value/file replacements. On conflict, keep ours for B1–B7; for B8 re-run `grep -rln '305680' ui-ngx/src/app --include='*.scss'` and re-apply the sed. The `src/inferrix/` overlay (angular.json `inferrix*` configs) still exists but is now redundant for logo/title — defaults carry the branding.

---

## Feature: Scheduler (PE replication)

- **Spec:** `docs/specs/2026-07-13-scheduler-design.md`; plan: `docs/superpowers/plans/2026-07-13-scheduler.md`
- **New additive paths (not tracked below):** `common/data/.../scheduler/*`, `common/data/.../id/SchedulerEventId.java`, `dao/.../scheduler/*`, `dao/.../sql/scheduler/*`, `dao/.../model/sql/*SchedulerEvent*`, `dao/.../service/validator/SchedulerEventDataValidator.java`, `application/.../service/scheduler/*`, `application/.../service/entitiy/scheduler/*`, `application/.../controller/SchedulerEventController.java`, `ui-ngx/src/app/modules/home/pages/scheduler/*`, `ui-ngx/src/app/shared/models/scheduler-event.models.ts`, `ui-ngx/src/app/core/http/scheduler-event.service.ts`
- **Status:** in progress on `inferrix-release-4.3`

### TB-core files modified

| # | File | Change | Why |
|---|------|--------|-----|
| S1 | `common/data/.../EntityType.java` | `SCHEDULER_EVENT(103)` appended after `API_KEY(44)` (comma-swap on API_KEY) | New entity type; proto number 103 matches PE |
| S2 | `common/data/.../id/EntityIdFactory.java` | `case SCHEDULER_EVENT -> new SchedulerEventId(uuid);` in `getByTypeAndUuid` after `API_KEY` case | Id factory for the new type |
| S2a | `common/data/.../id/EntityId.java` | `@DiscriminatorMapping(value = "SCHEDULER_EVENT", schema = SchedulerEventId.class)` added to the `@Schema` polymorphic discriminator list on `EntityId`, in alphabetical position before `TB_RESOURCE` | Registers `SchedulerEventId` in the Swagger polymorphic schema for `EntityId` so generated API docs/clients resolve the new id type correctly |
| S3 | `common/data/.../msg/TbMsgType.java` | `generateReport("Generate Report"),` after `REST_API_REQUEST` | Msg type fired by generateDashboardReport events (PE constant name kept lowercase for msg-type string parity) |
| S4 | `dao/src/main/resources/sql/schema-entities.sql` | Append `CREATE TABLE IF NOT EXISTS scheduler_event (...)` DDL at end of file | Schema for fresh installs |
| S5 | `dao/src/main/resources/sql/schema-entities-idx.sql` | Append `CREATE INDEX IF NOT EXISTS idx_scheduler_event_originator_id ON scheduler_event(tenant_id, originator_id);` at end of file | Index for scheduler_event queries |
| S6 | `application/src/main/data/upgrade/basic/schema_update.sql` | Append the same `CREATE TABLE ... scheduler_event` + index block wrapped in `-- SCHEDULER EVENT TABLE START/END` comments after the `-- WHITE LABELING TABLE END` marker | Upgrade path for existing installs |
| S7 | `dao/src/main/java/org/thingsboard/server/dao/model/ModelConstants.java` | Add `Scheduler event constants.` block with `SCHEDULER_EVENT_TABLE_NAME`, `SCHEDULER_EVENT_TENANT_ID_PROPERTY`, `SCHEDULER_EVENT_CUSTOMER_ID_PROPERTY`, `SCHEDULER_EVENT_NAME_PROPERTY`, `SCHEDULER_EVENT_TYPE_PROPERTY`, `SCHEDULER_EVENT_SCHEDULE_PROPERTY`, `SCHEDULER_EVENT_CONFIGURATION_PROPERTY`, `SCHEDULER_EVENT_ENABLED_PROPERTY`, `SCHEDULER_EVENT_ORIGINATOR_ID_PROPERTY`, `SCHEDULER_EVENT_ORIGINATOR_TYPE_PROPERTY`, `SCHEDULER_EVENT_ADDITIONAL_INFO_PROPERTY` after the White labeling constants block (~line 772) | Schema constants for Task 4's JPA entity mapping |
| S7b | `dao/src/test/resources/application-test.properties` | Add `cache.specs.whiteLabeling.timeToLiveInMinutes=1440` + `cache.specs.whiteLabeling.maxSize=10000` after the `apiKeys` cache block | **Incidental White-Labeling gap fix** (not scheduler-specific): WL ledger row 7 added a `whiteLabeling` cache spec to the main `thingsboard.yml` but the dao-module test properties were never given the mirror entry, so every `@DaoSqlTest` that boots the full cache config fails Spring context init. Uncaught until now because the reactor baseline builds with `-DskipTests`. Surfaced while making Task 4's DAO tests green. Belongs conceptually with WL row 7 |
| S8 | `common/proto/src/main/proto/queue.proto` | `ToCoreMsg` field 11 `schedulerServiceMsg` + new `SchedulerServiceMsgProto` message (`tenantIdMSB/LSB`, `eventIdMSB/LSB`, `added`, `updated`, `deleted`) placed after `NotificationSchedulerServiceMsg` | Queue transport for scheduler add/update/delete events between core nodes; PE used field 5, which is already occupied in CE (`EdgeNotificationMsgProto`), so field 11 (next free after CE's 1–10 block, before the 50+ device-activity block) was used instead |
| S9 | `application/.../service/entitiy/EntityStateSourcingListener.java` | Add `private final Optional<SchedulerService> schedulerService;` field; add `case SCHEDULER_EVENT` arm in the `SaveEntityEvent` switch (routes to `onSchedulerEventAdded`/`onSchedulerEventUpdated` based on `event.getCreated()`) and in the `DeleteEntityEvent` switch (routes to `onSchedulerEventDeleted` via an `instanceof SchedulerEventInfo` pattern match) | Sources CRUD events from `BaseSchedulerEventService`'s `SaveEntityEvent`/`DeleteEntityEvent` publications into the scheduler engine's queue-propagation path (`DefaultSchedulerService.onSchedulerEvent*` → `sendSchedulerEvent` → `ToCoreMsg`); `Optional` so the listener still wires up on nodes where `DefaultSchedulerService` isn't a registered bean |
| S10 | `application/.../service/queue/DefaultTbCoreConsumerService.java` | Add `private final Optional<SchedulerService> schedulerService;` field + constructor param (explicit constructor, appended last); add `else if (toCoreMsg.hasSchedulerServiceMsg())` branch in the `processMsgs` dispatch chain after the `hasNotificationSchedulerServiceMsg` branch; add `forwardToSchedulerService(TransportProtos.SchedulerServiceMsgProto, TbCallback)` next to `forwardToNotificationSchedulerService` | Consumer-side half of the queue round-trip: delivers `SchedulerServiceMsgProto` (proto field 11, S8) off the `tb-core` topic to `DefaultSchedulerService.onQueueMsg`; falls back to `callback.onSuccess()` when the `Optional` is empty so an unconfigured node doesn't stall pack processing |
| S11 | `application/src/main/resources/thingsboard.yml` | Add `scheduler:` block (`min_interval: "${SCHEDULER_MIN_INTERVAL_IN_SEC:60}"`) after the `audit-log:` section (before `state:`); add `"scheduler_event": "${AUDIT_LOG_MASK_SCHEDULER_EVENT:W}"` to the `audit-log.logging-level.mask` map after `"ai_model"` | Config knob for timer-based scheduler event minimum interval (PE parity); audit-log mask entry so scheduler_event CRUD is covered by the existing audit-log masking mechanism like every other entity type |
| S12 | `application/.../service/security/permission/Resource.java` | `SCHEDULER_EVENT(EntityType.SCHEDULER_EVENT),` appended after `WHITE_LABELING,` (~line 55) | New permission resource so the generic `checkEntityId`/`checkEntity` machinery and `@PreAuthorize`-gated controller can resolve `Resource.of(EntityType.SCHEDULER_EVENT)` |
| S13 | `application/.../service/security/permission/TenantAdminPermissions.java` | `put(Resource.SCHEDULER_EVENT, tenantEntityPermissionChecker);` after the `Resource.WHITE_LABELING` line | Tenant admins get full CRUD on scheduler events, same as every other tenant-owned resource (`tenantEntityPermissionChecker` unconditionally allows CREATE and gates READ/WRITE/DELETE on tenant match) |
| S14 | `application/.../service/security/permission/CustomerUserPermissions.java` | `put(Resource.SCHEDULER_EVENT, schedulerEventPermissionChecker);` after the `Resource.WHITE_LABELING` line, plus a new `schedulerEventPermissionChecker` constant (structurally cloned from `customerEntityPermissionChecker`, scoped to `Operation.CREATE/READ/WRITE/DELETE`) | Customer users can self-service create/read/update/delete their own scheduler events (PE behavior, ported verbatim). **Deviation from the original task brief**, which specified reusing the shared `customerEntityPermissionChecker`: that checker's `GenericPermissionChecker` allowed-operations set has no `Operation.CREATE`, so `BaseController.checkEntity`'s null-id branch (`accessControlService.checkPermission(user, resource, Operation.CREATE, null, entity)`) always 403'd for a brand-new scheduler event. Broadening the shared checker would have silently changed CREATE semantics for `ASSET`/`DEVICE`/`ENTITY_VIEW`/`EDGE` too, so a dedicated checker was added instead — same tenant/customer-match logic, minus `READ_CREDENTIALS`/`CLAIM_DEVICES`/telemetry operations the scheduler controller never exercises |
| S15 | `application/.../controller/BaseController.java` | Field `protected SchedulerEventService schedulerEventService;` after `whiteLabelingService` (~line 400); helpers `checkSchedulerEventId`/`checkSchedulerEventInfoId` next to `checkEdgeId`/`checkEdgeInfoId` (~line 774); `case SCHEDULER_EVENT -> checkSchedulerEventId(...)` added to the `checkEntityId` switch (~line 665, alongside `AI_MODEL`/`API_KEY`) | Wires the Task 4 DAO service into the generic permission-gated entity lookup used by the controller and by `checkReferencedEntities`/calculated-field flows |
| S16 | `application/.../service/edge/EdgeEventSourcingListener.java` | `SCHEDULER_EVENT` added to the `case API_USAGE_STATE, EDGE, SCHEDULER_EVENT: return false;` arm in `isValidSaveEntityEventForEdgeProcessing` (~line 230) | **Found while making `SchedulerEventControllerTest.testSaveSchedulerEvent` green, not pre-planned in the brief.** `BaseSchedulerEventService.saveSchedulerEvent` (Task 4) publishes a plain `SaveEntityEvent` like every other entity's DAO save; this listener is a shared TB-core sourcing hook with a default-allow switch, so without an explicit exclusion arm it forwarded every scheduler save to `TbClusterService.sendNotificationMsgToEdge` — observable via `@SpyBean` invocation even though the call is internally a no-op (`EdgeEventType` has no `SCHEDULER_EVENT` mapping, so `sendNotificationMsgToEdge` would immediately return). Excluding it outright matches the product intent (scheduler events are not edge-syncable) and the existing pattern for `API_USAGE_STATE`/`EDGE` |

### Merge-recovery procedure

- **`EntityType.java`** — verify `SCHEDULER_EVENT(103)` is still the last enum constant (comma-swapped with `API_KEY`). Upstream regularly appends new entity types here; re-apply the comma-swap if a merge drops it.
- **`EntityIdFactory.java`** — verify the `case SCHEDULER_EVENT -> new SchedulerEventId(uuid);` arm survives in `getByTypeAndUuid`. This is an exhaustive `switch` expression — if upstream adds a new `EntityType` constant, the compiler will fail loudly until every arm (including ours) is present, so a dropped case is unmissable.
- **`EntityId.java`** — verify the `@DiscriminatorMapping` for `SCHEDULER_EVENT` survives in the class-level `@Schema` annotation on `EntityId`. This is annotation metadata only (no compile-time enforcement), so a merge could silently drop it without breaking the build — check by hand.
- **`TbMsgType.java`** — verify `generateReport("Generate Report"),` survives after `REST_API_REQUEST`. Not an exhaustive switch; a merge could silently drop it — check by hand.
- **`schema-entities.sql`** and **`schema_update.sql`** — verify both contain the `scheduler_event` table block. Upstream may reorder or restructure these files; confirm the table definition with the exact column names and constraints (especially `scheduler_event_external_id_unq_key`) is still present in both.
- **`schema-entities-idx.sql`** — verify the `idx_scheduler_event_originator_id` index survives at the end. Upstream may reorder; re-apply if dropped.
- **`ModelConstants.java`** — verify the `Scheduler event constants.` block survives after the White labeling constants block. All 10 constants must be present and the generic constants (`TENANT_ID_PROPERTY`, `CUSTOMER_ID_PROPERTY`, `ADDITIONAL_INFO_PROPERTY`) must remain as references, not hardcoded strings.
- **`queue.proto`** — verify `ToCoreMsg.schedulerServiceMsg = 11` survives and that field number 11 is still unique within `ToCoreMsg`. Proto field numbers are compile-safe for us (a collision fails codegen loudly), but a merge that renumbers or reuses field 11 in `ToCoreMsg` would silently break wire compatibility between nodes running different builds — re-check the field list by hand after every upstream merge, not just on build failure.
- **`EntityStateSourcingListener.java`** — verify the `Optional<SchedulerService> schedulerService` field and both `case SCHEDULER_EVENT` switch arms survive. Neither switch is exhaustive (both have a `default -> {}`), so a dropped case fails silently — check by hand.
- **`DefaultTbCoreConsumerService.java`** — verify the `Optional<SchedulerService> schedulerService` constructor param (last positional arg) and the `hasSchedulerServiceMsg()`/`forwardToSchedulerService` dispatch branch survive. The constructor is explicit (not `@RequiredArgsConstructor`), so a merge that reorders params here is a compile error if types collide, but a silent drop of the whole param+branch pair would just mean scheduler queue messages get unhandled (falls through the `if/else if` chain with no `callback.onSuccess()/onFailure()` — the pack processing timeout would eventually cover it, but check by hand after merges touching this file).
- **`thingsboard.yml`** — verify the `scheduler:` block (`min_interval`) survives after `audit-log:`/before `state:`, and the `"scheduler_event"` mask entry survives in `audit-log.logging-level.mask` after `"ai_model"`. Pure config; no compile-time enforcement — check by hand.
- **`Resource.java`** — verify `SCHEDULER_EVENT(EntityType.SCHEDULER_EVENT),` survives in the `Resource` enum. Not exhaustive against `EntityType`; a merge could silently drop it — check by hand (breakage shows up as a 403 or an `IllegalArgumentException` from `Resource.of()` at runtime, not a compile error).
- **`TenantAdminPermissions.java`** / **`CustomerUserPermissions.java`** — verify the `Resource.SCHEDULER_EVENT` `put(...)` lines survive, and that `CustomerUserPermissions` still has its own dedicated `schedulerEventPermissionChecker` (not silently merged back onto the shared `customerEntityPermissionChecker`, which lacks `Operation.CREATE` — see S14). Pure map registration; no compile-time enforcement — check by hand.
- **`BaseController.java`** — verify the `schedulerEventService` field, `checkSchedulerEventId`/`checkSchedulerEventInfoId` helpers, and the `case SCHEDULER_EVENT -> checkSchedulerEventId(...)` switch arm all survive. The switch is exhaustive over `EntityType`, so a dropped case fails the build loudly; the field/helpers are not compiler-enforced — check by hand.
- **`EdgeEventSourcingListener.java`** — verify `SCHEDULER_EVENT` survives in the `case API_USAGE_STATE, EDGE, SCHEDULER_EVENT: return false;` arm of `isValidSaveEntityEventForEdgeProcessing`. Not exhaustive (falls through to a default-allow `return true`), so a dropped case fails silently — a scheduler save would start round-tripping through `sendNotificationMsgToEdge` again — check by hand.

---

## How to extend this ledger

When you modify a TB-core file for a new feature:

1. Add a `## Feature: <name>` section with the same structure (module path, status, files table).
2. List each modified file with a precise change anchor (line region, block name, or insertion point). Avoid copy-pasting whole diffs — they rot fast against upstream churn. Describe the *intent* of the patch so a future reader can reapply by hand.
3. Add the file to the merge-recovery procedure at the bottom of that section.

Keep this file in sync with `master` and `inferrix-release-4.3` (cherry-pick across branches as needed).
