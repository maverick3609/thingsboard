# Inferrix Patches — ThingsBoard-core modification ledger

Tracks every file in upstream ThingsBoard that Inferrix features have modified.
The purpose is **merge survival**: after each `git merge upstream/release-4.3`,
walk this ledger and verify every patch is still present (an upstream rewrite
of a file can silently revert our changes).

- **Baseline:** `upstream/release-4.3` (version 4.3.1.3, merge `06f05c6108`)
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

### TB-core files modified (22)

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

#### Frontend (12)

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
| 20 | `ui-ngx/src/app/modules/login/pages/login/login.component.ts` | (a) import `WhiteLabelingHttpService`, `WhiteLabelingRuntimeService`, `LoginWhiteLabelingParams`; (b) add `loginLogo`, `pageBackgroundColor`, `darkForeground` fields with defaults; (c) inject the two WL services; (d) in `ngOnInit`, call `wlHttp.getLoginWhiteLabelParams` and apply params + assign fields | Login page reads its own WL params before authentication |
| 21 | `ui-ngx/src/app/shared/components/footer.component.html` | Replace single `<small>Copyright © {{year}} The ThingsBoard Authors</small>` with a conditional `*ngIf="(showNameVersion$ \| async) && (platformName$ \| async) as name; else defaultFooter"` and a `#defaultFooter` template that preserves the original | Footer text comes from WL settings when available |
| 22 | `ui-ngx/src/app/shared/components/footer.component.ts` | (a) import + inject `WhiteLabelingRuntimeService`; (b) expose `showNameVersion$ = wlRuntime.showNameVersion$` and `platformName$ = wlRuntime.platformName$` as fields | Bind the observables consumed by the template |

### Merge-recovery procedure (WL-specific notes)

- **`menu.models.ts`** is the single highest-risk file in the whole ledger (5 insertion points; upstream regularly touches these maps). Always diff this file by hand after merging.
- **`BaseController.java`** is a hub class — upstream adds `@Autowired` fields here frequently. Make sure our `whiteLabelingService` field survives.
- **`admin-routing.module.ts`** — three sub-changes on the same `/settings` route. Verify all three (`auth` array, `redirectTo` map, new child route).
- **`Resource.java`** — upstream adds enum values here regularly. Verify `WHITE_LABELING,` survives in its expected position (between `MOBILE_APP_SETTINGS` and `JOB`).
- **`schema_update.sql`** and **`schema-entities.sql`** both create the `white_labeling` table — confirm both still contain the block (upstream may rewrite ordering).

---

## How to extend this ledger

When you modify a TB-core file for a new feature:

1. Add a `## Feature: <name>` section with the same structure (module path, status, files table).
2. List each modified file with a precise change anchor (line region, block name, or insertion point). Avoid copy-pasting whole diffs — they rot fast against upstream churn. Describe the *intent* of the patch so a future reader can reapply by hand.
3. Add the file to the merge-recovery procedure at the bottom of that section.

Keep this file in sync with `master` and `inferrix-release-4.3` (cherry-pick across branches as needed).
