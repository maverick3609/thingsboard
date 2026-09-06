# Inferrix Cortex — Feature Documentation

Inferrix Cortex is a fork of the open-source ThingsBoard IoT platform. This document describes every
feature Inferrix has added on top of upstream ThingsBoard: what it does, how to configure it, how it
behaves, and where it deliberately differs from ThingsBoard Professional Edition (PE), which several of
these features replicate.

**Audience.** Operators deploying and configuring the platform, and developers extending it.

**Scope.** Features Inferrix built. Everything else — devices, assets, dashboards, rule chains, alarms,
edge, telemetry — is stock ThingsBoard and is documented at
[thingsboard.io/docs](https://thingsboard.io/docs/).

**Companion document.** `INFERRIX-PATCHES.md` is the *merge-survival ledger*: every upstream file an
Inferrix feature modified, why, and how to recover the change after `git merge upstream/release-4.3`.
It is for people maintaining the fork, not for people configuring it. This file never duplicates it —
if you need to know which line of which upstream file carries a feature, read the ledger.

> **Branch note.** This file documents `inferrix-release-4.3`. The `master` branch carries an unrelated,
> older `INFERRIX.md` (commit `f2e6ce9aa9`) describing the superseded "Inferrix Synapse" `src/inferrix/`
> branding overlay. The two branches are divergent; these are not versions of the same document and must
> not be merged or reconciled as if they were.

---

## Contents

1. [Overview](#1-overview)
2. [Before you deploy](#2-before-you-deploy)
3. [License Control](#3-license-control)
4. [Roles and Permissions](#4-roles-and-permissions)
5. [White Labeling](#5-white-labeling)
6. [Scheduler](#6-scheduler)
7. [Reporting](#7-reporting)
8. [Widget Data Export](#8-widget-data-export)
9. [Branding and navigation](#9-branding-and-navigation)
10. [Configuration reference](#10-configuration-reference)
11. [REST API reference](#11-rest-api-reference)
12. [Troubleshooting](#12-troubleshooting)
13. [Maintaining the fork](#13-maintaining-the-fork)

---

## 1. Overview

| | |
|---|---|
| Product | Inferrix Cortex |
| Base | ThingsBoard CE `4.3.x` (Apache 2.0) |
| Current version | `4.3.1.5` |
| Integration branch | `inferrix-release-4.3` |
| Upstream baseline | `upstream/release-4.3` |
| Backend | Java 17 / Spring Boot 3.5, Maven multi-module |
| Frontend | Angular 20 (`ui-ngx/`), Yarn |

### The features

| Feature | What it adds | Who sees it | Enabled by default |
|---|---|---|---|
| [License Control](#3-license-control) | Offline signed licence: device/asset caps + expiry, bound to one install | Sys admin, tenant admin (capacity card) | **Yes** — platform will not boot without a key |
| [Roles and Permissions](#4-roles-and-permissions) | PE-style RBAC: named roles of `resource → [operations]`, assigned to customer users | Tenant admin authors; customer users are restricted | Yes, but **opt-in per user** — a user with no role is unaffected |
| [White Labeling](#5-white-labeling) | Runtime branding: logo, colours, titles, favicon, custom CSS, login page, mail templates, legal pages | Sys admin, tenant admin, customer user | Yes |
| [Scheduler](#6-scheduler) | PE-style scheduled events: recurring attribute updates, RPC, firmware/software updates, report generation | Tenant admin, customer user | Yes |
| [Reporting](#7-reporting) | PE-style report templates, a drag-and-drop designer, PDF/CSV rendering, scheduled email delivery | Tenant admin | **No** — renderer is opt-in |
| [Widget Data Export](#8-widget-data-export) | CSV / XLS / XLSX export button on dashboard widgets | Anyone who can view a dashboard | Yes, for table-type widgets |
| [Branding](#9-branding-and-navigation) | Inferrix logos, colours, titles, mail text baked into the default build | Everyone | Yes |
| [Navigation](#9-branding-and-navigation) | Slimmed tenant sidebar; Edge Management and OTA removed; Utilities and Administration groups | Tenant admin | Yes |

### "PE replication"

Scheduler, Reporting, Roles and White Labeling are **replications of ThingsBoard Professional Edition
features**, rebuilt on the Community Edition codebase from analysis of the PE distribution. Where a PE
concept has no CE equivalent (entity groups, for example), the feature is reshaped rather than dropped,
and every such deviation is called out in the relevant section below. Two are deliberately *stricter*
than PE — the [anonymous public-dashboard viewer](#46-the-anonymous-public-dashboard-viewer) and the
[report sub-report recursion bounds](#78-limits-and-safety-rails) — and one is deliberately *narrower*
([roles apply to customer users only](#41-who-a-role-applies-to)).

---

## 2. Before you deploy

### 2.1 The database schema overlay

All Inferrix tables live in **one file**: `dao/src/main/resources/sql/schema-inferrix.sql`. It is applied
automatically on every fresh install and on every upgrade, and it is idempotent (`CREATE ... IF NOT
EXISTS`, guarded `ALTER`), so re-running it is safe.

It currently creates:

| Table | Feature |
|---|---|
| `scheduler_event` (+ `idx_scheduler_event_originator_id`) | Scheduler |
| `report_template`, `report` (+ two indexes) | Reporting |
| `inferrix_license_state` | License Control |
| `role`, `user_role` (+ `idx_user_role_role_id`) | Roles and Permissions |

**This matters operationally.** Swapping the boot jar on a running server does *not* apply the overlay —
the install/upgrade tool does. If you hot-swap a jar without running the upgrade, features whose tables
are missing degrade rather than crash:

- **Roles** log `RBAC tables are missing - roles are not enforced until the database upgrade is run.`
  once, then fall back to legacy access for every customer user. This fail-open is deliberate: a missed
  migration must not lock a whole tenant out.
- **Scheduler** and **Reporting** fail their queries loudly.

ThingsBoard's Liquibase is **not** used on this fork. This overlay plus the platform's own
install/upgrade application is the only migration path. To add new Inferrix DDL, append idempotent
statements to that one file — nothing else changes.

### 2.2 Configuration file layout

Two files, and the distinction is load-bearing:

| File | Holds | Survives a jar swap |
|---|---|---|
| `/etc/thingsboard/conf/thingsboard.conf` | shell `export VAR=value` lines — environment variables | Yes |
| `/etc/thingsboard/conf/thingsboard.yml` | the platform's YAML configuration | Yes |

The `thingsboard.yml` **inside the jar** is a default template. The external file at
`/etc/thingsboard/conf/thingsboard.yml` is what a real deployment reads, and it overrides the packaged
one. A configuration block that exists only inside the jar will not take effect on a server that has an
external `thingsboard.yml` — this is the single most common cause of "I set it and nothing happened".

**Licensing in particular cannot be configured by swapping a jar.** The `license:` block must be present
in the external `thingsboard.yml`, and `INFERRIX_LICENSE_KEY` must be exported from `thingsboard.conf`.

### 2.3 Building

```bash
# Backend (full reactor)
mvn clean install -DskipTests

# Frontend only — requires Node 22
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"
cd ui-ngx && yarn install && yarn build:prod
```

The default frontend build is already Inferrix-branded — see
[Branding](#9-branding-and-navigation). No special build configuration is needed.

---

## 3. License Control

Offline, install-wide licence enforcement. A signed key caps the number of devices and assets a
deployment may create and expires on a schedule. There is no per-tenant licensing and no phone-home.

### 3.1 The key

Inferrix issues each deployment an Ed25519-signed licence key: a customer name, an expiry date, and a
device/asset cap, bound to that one deployment's instance UUID so a key cannot be copied to a second
install. The key is a single opaque string.

It goes in `thingsboard.conf` as an environment variable:

```sh
export INFERRIX_LICENSE_KEY="..."
```

`thingsboard.yml` reads it via `license.key: "${INFERRIX_LICENSE_KEY:}"`. Four more tunables live in the
same `license:` block — see the [configuration reference](#101-license-control).

### 3.2 First boot

On its first boot the platform generates a random instance UUID and stores it in a single-row table
(`inferrix_license_state`). That UUID is the deployment's permanent licensing identity.

If `INFERRIX_LICENSE_KEY` is not set, the platform logs that instance UUID and exits with code 13.
Send the UUID to Inferrix, receive a key issued specifically for it, set `INFERRIX_LICENSE_KEY`, and
restart. From then on the key is re-verified at boot and on a timer (`license.check_interval_ms`,
default one hour) for as long as the process runs.

### 3.3 What each role sees

`GET /api/license/info` backs a card on the `/home` fallback grid, visible to SYS_ADMIN and TENANT_ADMIN
alike. Both roles see the capacity numbers — devices and assets used against the cap, plus days
remaining — because that is the point of the card: a tenant admin needs to see capacity pressure coming.

The licensing **customer name** and the **instance ID** are SYS_ADMIN-only. The API omits both fields
(rather than blanking them client-side) for every other caller, so a TENANT_ADMIN never receives them.
On a shared multi-tenant install those two values would identify the paying customer and the deployment
itself to every tenant on it.

### 3.4 Exit codes

A licence problem is fatal: the platform logs the reason and terminates. Each failure mode has its own
exit code so the cause is visible from a process supervisor without reading logs.

| Code | Meaning |
|---|---|
| 13 | No licence key is configured |
| 14 | The licence key is malformed (not a valid key at all) |
| 15 | The signature does not verify (payload altered, or not a genuine Inferrix key) |
| 16 | The key was issued for a different deployment's instance UUID |
| 17 | The licence has expired |
| 18 | The system clock moved backwards past the recorded high-water mark, beyond tolerance |

Reaching a device/asset cap does **not** exit the process — it only blocks further creates. Existing
devices and assets keep operating normally. Only the six conditions above terminate the platform.

The systemd unit template sets `RestartPreventExitStatus=13 14 15 16 17 18`, so a licence exit stops the
service instead of restarting it. **Docker Compose has no exit-code predicate** for its `restart:`
directive, so under `restart: always` a licence exit will loop the container every restart delay. If a
container is exiting on a licence code, stop it (`docker compose stop`) rather than waiting on it, and
fix the licence before starting it again.

### 3.5 Recovering from exit code 18

Exit 18 does not always mean *this* node's clock is wrong.

`checkClock` compares the current clock against `high_water_ts`, a mark in `inferrix_license_state` that
only ever moves forward. The usual cause is a **different** node whose clock once ran ahead of real
time: it still passed `checkExpiry` (its fast clock had not reached the licence's expiry) and
`checkClock` (nothing had recorded a higher mark yet), so it persisted its own future timestamp as the
new high-water mark. Because the mark cannot move backward, every node afterwards — including the
offending one, once corrected — reads a real "now" behind that stale future mark.

**Confirm before touching anything.** The exit-18 log line prints both values it compared:

```
System clock moved backwards past the recorded high-water mark: clock reads <now> but the recorded high-water mark is <highWater>
```

Convert both to readable times and check them against a clock you trust (NTP, another host).

- If **`highWater`** is the one in the future — this is the scenario above. Proceed to the recovery.
- If **`now`** is the one that looks wrong — the check is doing its job. Fix *this* node's clock and let
  it re-verify normally. **Do not run the recovery**; it would recreate the same stuck mark for whoever
  checks in next.

**Blast radius.** `license.max_high_water_advance_ms` (default 24h) caps how far one check can push the
mark ahead, not how many checks can push it.

- A **transient** bad reading (a one-off NTP jump that self-corrects, or a node that stops checking in)
  costs at most one clamp period of lockout, then the install heals itself.
- A **persistent** skew (dead RTC battery, wrong timezone) walks the mark forward by one clamp period
  per check until it reaches that node's own reading, then tracks it exactly. The lockout lasts as long
  as that node keeps running and checking in. The real fix is correcting or stopping the offending
  node's clock; the recovery below buys time but a still-running bad node will push the mark straight
  back up.

A WARN in the log (`DefaultLicenseService`, "high-water mark clamped") fires every time a check gets
clamped, so this is visible well before the lockout lands.

**Recovery**, only once the clocks involved are confirmed good:

```sql
-- Only after confirming no clock is actually wound back. Sets the mark to the current true time.
UPDATE inferrix_license_state SET high_water_ts = <current epoch millis> WHERE singleton = TRUE;
```

This is a deliberate override of an anti-tamper control, applied directly against the database and
bypassing the platform entirely. Run it only once the clocks are known good — used on a node whose clock
is genuinely wound back, it is exactly the tampering this mechanism exists to catch, defeated silently
by an operator's own hand.

### 3.6 No internet access required

Verification is entirely offline. The public key used to verify signatures is compiled into the
platform, and the only persisted state is the single `inferrix_license_state` row. The platform never
calls out to Inferrix or anywhere else to check a licence.

### 3.7 Install and upgrade are unlicensed

The installer and the upgrade tool run under Spring's `install` profile, which selects a separate no-op
licence bean that never checks a key and never exits. Enforcement activates only once the platform
starts in its normal (non-install) profile. A fresh install or a version upgrade therefore always
completes regardless of whether a key is configured yet — the key is needed to start serving traffic
afterward, not to migrate.

### 3.8 Issuing keys

Keys are produced by a separate tool, not by this repository:
`/Users/maverick/Office/Product/inferrix-license-keygen/`.

The generator's private signing key lives **only in the password manager** — it is not checked into
either repository. Both repositories share a test-only "golden vector" fixture (a throwaway keypair used
purely to pin the key format in tests); that fixture's private key is confined to the generator repo's
copy and is deliberately absent from this repo's copy.

---

## 4. Roles and Permissions

PE-style role-based access control, ported as *generic roles assigned directly to users*. A role is a
set of `resource → [operations]` grants; a user may hold several and gets the union.

**Where:** Administration → Roles (`/roles`, tenant admin only) to author; Administration → Users
(`/users`) to assign.

### 4.1 Who a role applies to

**Customer users only.** A tenant administrator has full rights over their own tenant and cannot be
restricted by a role — assigning one is refused (`403 Roles may only be assigned to customer users!`),
and the *Manage roles* action is disabled on non-customer rows. A sys admin is never restricted either.

A customer user with **no** role keeps the plain authority-based access ThingsBoard has always given
them, so the feature is opt-in per user and needs no migration or backfill.

> **PE differs here.** PE routes tenant admins through the same role machinery and merely seeds them a
> generic `ALL: [ALL]` role, so PE *can* restrict a tenant admin. This fork deliberately cannot. The
> out-of-the-box behaviour is identical; the capability is narrower.

### 4.2 Authoring a role

**Administration → Roles → Add role.** Each row grants a set of operations on one resource; `All` is a
wildcard on either side.

The available operations are: `ALL`, `CREATE`, `READ`, `WRITE`, `DELETE`, `ASSIGN_TO_CUSTOMER`,
`UNASSIGN_FROM_CUSTOMER`, `RPC_CALL`, `READ_CREDENTIALS`, `WRITE_CREDENTIALS`, `READ_ATTRIBUTES`,
`WRITE_ATTRIBUTES`, `READ_TELEMETRY`, `WRITE_TELEMETRY`, `CLAIM_DEVICES`, `ASSIGN_TO_TENANT`,
`READ_CALCULATED_FIELD`, `WRITE_CALCULATED_FIELD`.

Three things regularly surprise people:

- **Operations match exactly.** `Read` does **not** imply `Read telemetry`, `Read attributes` or
  `Read credentials`. A role that must serve a dashboard needs those listed explicitly.
- **Dashboards need `Widget type` Read.** Every dashboard — including the built-in Home page — resolves
  each of its widgets through `GET /api/widgetType?fqn=`, which is permission-checked. Without the grant
  the page renders as a column of *"Problem loading widget configuration. Probably associated widget
  type was removed."* That is ThingsBoard's generic message; nothing in it says "permission". Browsing
  the widget library additionally needs `Widgets bundle` Read.
- **A user needs nothing extra to be themselves.** Reading your own user record, editing your own
  profile, and — for a customer user — reading your own customer record are never role-gated. Every
  session bootstraps with `GET /api/user/{self}`, `checkCustomerId` fronts every customer-scoped list,
  and editing your own name or language is something the platform gives every user, so gating any of
  them would lock the account down rather than restrict it. The exemption stops there: **deleting** your
  own account is never exempt, and the write exemption is your *user record* only — a customer user
  still has no write on their own Customer.

### 4.3 Assigning

**Administration → Users** lists every user in the tenant for a tenant admin (read-only — creating and
deleting a user stays on the page that owns its authority, the tenant's or the customer's own users
page). The *Manage roles* action replaces a user's whole set; an empty selection removes all roles and
restores default access.

The same page shown to a *customer* user lists only their own customer's users, and is read-only unless
they hold the customer-administrator grant described below.

Nobody may change their **own** roles, in the UI or over the API — clearing them would restore
unrestricted access to the person doing the clearing.

### 4.4 The two seeded roles

The first time a tenant opens **Administration → Roles**, two roles are created if absent (PE parity
with `findOrCreateCustomerAdminRole` / `findOrCreateCustomerUserRole`):

| Role | Grants | For |
|---|---|---|
| `Customer Administrator` | `All: [All]` | a customer user who manages their own customer's users |
| `Customer User` | `All: [Read, Read credentials, Read attributes, Read telemetry, RPC call]` | plain read-only access |

They are ordinary roles — edit them, or ignore them and author your own. Note they are **re-created on
the next visit to the Roles page if deleted**, so removing one is not permanent; edit it to empty
instead. Seeding is idempotent and never touches a role that already exists under that name.

The seeded `Customer User` role does grant `Read credentials` and `RPC call` — PE's read-only set
includes both. That is a deliberate contrast with the anonymous viewer below, who is refused both: a
named customer user is someone the tenant chose to create and can revoke, while a public dashboard link
is held by whoever happens to have the URL.

PE seeds five roles per tenant; we seed these two. The other three do not port:

| PE role | Why not |
|---|---|
| `Tenant Administrator` | PE reaches full tenant access by *seeding* an `ALL: [ALL]` role. Here a tenant admin has full rights inherently, so the role would grant nothing already untrue. |
| `Tenant User` | Its whole purpose is to restrict a tenant admin to read-only. Roles restrict customer users only here, so no tenant admin could ever hold it. |
| `Public User` | Ported, but **not as a role** — see below. CE does have public users, but they are synthetic (no database row), so there is nothing to assign a role to. |

The cost of leaving the tenant pair out is a **read-only junior tenant administrator**: someone who can
see everything in the tenant but change nothing. That role is not expressible here, and will not be
while roles stay customer-scoped. Reversing it means dropping three guards — the authority checks at the
top of `DefaultUserPermissionsService.getMergedPermissions` and in `UserPermissionsUtil.granted`, and
the "customer users only" check in `RoleController.updateUserRoles` — after which an explicitly assigned
role would restrict a tenant admin too. Nobody's access would change until a role is actually assigned,
since a role-less user keeps full authority-based access either way.

### 4.5 The customer administrator

Give a customer user a role granting `User` **Write** — the seeded *Customer Administrator* does, via
its `All: [All]` — and they may manage the other users of **their own customer**:

- **Administration → Users** becomes read-write for them, and lists only their own customer's users.
- Create, edit and delete a colleague, and copy an activation link so the colleague can set a password.
- Add and Delete additionally require `User` **Create** / **Delete**; `All: [All]` covers both.

Scope is pinned on the server, never taken from the request body: a new user's tenant, customer and
authority all come from the caller. An attempt to create a `TENANT_ADMIN`, or a user inside another
customer, is silently downgraded to a customer user of the caller's own customer rather than refused.

Still refused for a customer administrator, and the buttons are hidden accordingly: impersonation
(*Login as user*), assigning roles (a tenant admin only), disabling an account, and resending activation
mail. The activation **link** is available — that is the intended way to hand off a new account.

The capability is **explicit**: it needs a role that names `User` Write. A customer user with no role
sees the page exactly as read-only as before, so shipping this widened nobody's access by default.

### 4.6 The anonymous public-dashboard viewer

Publishing a dashboard makes it readable by anyone holding the link. That viewer authenticates with
nothing but the public id and arrives as a **customer user of the public customer** — synthetic, with no
database row, so no role can be assigned to it. Its permissions are therefore fixed in code
(`DefaultUserPermissionsService.PUBLIC_USER_PERMISSIONS`) rather than seeded as a role, which is the one
place this fork *has* to diverge from PE's shape while matching its effect.

The set is PE's two public roles unioned, minus RPC — `{ALL: [READ, READ_ATTRIBUTES, READ_TELEMETRY]}`.
Unioned because we have no entity groups to carry the second half. `CustomerUserPermissions` still
confines the viewer to the public customer's own entities, which is what group membership did in PE.

Stock ThingsBoard grants a public viewer far more than reading. Measured against a published dashboard,
before and after:

| An anonymous link holder could… | before | now |
|---|---|---|
| read the device's **access token** (`/credentials`) | 200 | **403** |
| write shared attributes | 200 | **403** |
| write timeseries | 200 | **403** |
| rename or otherwise modify the device | 200 | **403** |
| send RPC to the device (actuate it) | 200 | **403** |
| read the dashboard, device, attributes, telemetry, alarms | 200 | 200 |
| delete the device, list tenant devices | 403 | 403 |

The credentials read is the one worth dwelling on: the access token does not expire, so anyone who
opened a public dashboard could publish data as that device indefinitely, from anywhere, long after the
link was withdrawn.

> **Stricter than PE, on purpose.** PE's public entity-group permissions include `RPC_CALL`, so a PE
> public dashboard can carry working control widgets. We drop it: anyone holding a dashboard link could
> otherwise actuate the devices on it. The consequence is that a **control widget on a public dashboard
> is inert** — it renders, and the command is refused with 403. If a public dashboard ever legitimately
> needs to actuate, that is the line to revisit (`PUBLIC_USER_PERMISSIONS` in
> `DefaultUserPermissionsService`), not a per-widget workaround.

### 4.7 How it is enforced

- Roles are AND-ed in after the normal authority check, in `DefaultAccessControlService`. A role can
  only ever **restrict** what the authority already allows, never extend it.
- Collection endpoints that read entity data without a per-entity check are covered by a read gate keyed
  on the URL pattern (115 endpoints). It logs `RBAC read gate active on 115 endpoints` at boot, and logs
  an error naming any pattern that no longer resolves, so an upstream rename cannot silently open a
  hole.
- `/api/entitiesQuery/*` and the WebSocket v2 commands are gated on the entity type the query resolves
  to.
- The UI hides menu entries, Add/Delete buttons and details-panel actions the role denies. That is
  **cosmetic** — the server is the enforcer. A few entity-specific buttons (Unassign from customer,
  Manage credentials) are still drawn; the server refuses them. The **Users** page is fully covered: a
  customer user never sees Disable/Enable Account or Resend activation (tenant-only endpoints), and sees
  Display activation link only with the customer-administrator grant.

### 4.8 Operational notes

- **Permissions are never in the JWT.** They are cached per user and re-read each request, so a role
  edit applies on the **next request** — no logout needed. The menu follows on the next page load.
- **Cache eviction is cluster-wide.** A role change broadcasts one message to every tb-core node
  carrying the affected user ids, so a revocation cannot linger on another node. This matters because
  `cache.type` defaults to node-local caffeine.
- **The `role` and `user_role` tables must exist.** They ship in the Inferrix schema overlay
  (§[2.1](#21-the-database-schema-overlay)). If the jar is swapped without applying it, the platform
  logs `RBAC tables are missing - roles are not enforced until the database upgrade is run.` once and
  falls back to legacy access for every customer user rather than failing their requests — a deliberate
  fail-open, chosen so a missed migration cannot lock a whole tenant out. Apply the overlay.

---

## 5. White Labeling

Runtime branding, configured through the UI rather than baked into a build. Where the
[default build branding](#9-branding-and-navigation) is compiled in, white labeling is data — it lives
in the `white_labeling` table, is cached, and takes effect without a redeploy.

**Where:** its own top-level sidebar entry, **White labeling** (`/white-labeling`). For a tenant admin
it sits inside the **Administration** group; sys admins and customer users see it top-level.

### 5.1 The three scopes

Settings inherit down a chain. Each level fills in only what the level below left blank:

```
System (sys admin)  →  Tenant (tenant admin)  →  Customer (customer user / tenant admin on a customer)
```

A field left empty at the customer level inherits the tenant's; a field left empty at the tenant level
inherits the system's. This is why an **emptied** field is stored as `null`, not `""` — a blank string
would count as a deliberate override and block inheritance. Both form components run every text field
through `blankToNull()` on save for exactly this reason.

A tenant admin can edit a specific customer's branding; the page keeps that scope across tab switches.

### 5.2 The tabs

| Tab | Route | Who |
|---|---|---|
| General | `/white-labeling/general` | sys admin, tenant admin, customer user |
| Login | `/white-labeling/login` | sys admin, tenant admin, customer user |
| Mail templates | `/white-labeling/mail-templates` | **sys admin only** |
| Privacy policy | `/white-labeling/privacy-policy` | sys admin, tenant admin |
| Terms of use | `/white-labeling/terms-of-use` | sys admin, tenant admin |

`/settings/whiteLabeling` still resolves for old bookmarks and redirects to `/white-labeling`.

### 5.3 General tab

Applies to the authenticated application: sidebar, dashboards, browser tab.

| Field | Effect |
|---|---|
| `logoImageUrl` | Sidebar logo, dashboard logo. Picked from the image gallery. |
| `logoImageHeight` | Rendered logo height in px. |
| `appTitle` | Browser tab title, replacing the built-in one. |
| `faviconUrl` | Browser tab icon. |
| `paletteSettings` | Primary and accent Material palettes; each may extend a stock palette or define its own colours. Edited via the palette dialog. |
| `showNameVersion` | Renders `{platformName} v.{platformVersion}` at the bottom of the left nav. |
| `platformName`, `platformVersion` | The text that toggle renders. `platformName` is required when the toggle is on. |
| `enableHelpLinks`, `helpLinkBaseUrl`, `uiHelpBaseUrl` | Whether in-app help links render, and where they point. |
| `hideConnectivityDialog` | Suppresses the device "check connectivity" dialog. |
| Advanced CSS | Free-form CSS injected into the app, edited in its own dialog. |

**Images are stored as public links.** White-labeling image fields hold
`/api/images/public/{key}`, not the gated `tb-image;` reference the rest of the platform uses. Picking an
image through the gallery picker makes it public and stores its public link. This is required so
branding renders on `/login`, before any authentication exists.

### 5.4 Login tab

Applies to the unauthenticated `/login` page, which loads its own parameters before any token exists via
`GET /api/noauth/whiteLabel/loginWhiteLabelParams`.

| Field | Effect |
|---|---|
| `logoImageUrl`, `logoImageHeight` | Login card logo. Custom logos keep their aspect ratio inside the fixed 280px slot. |
| `pageBackgroundColor` | Page background behind the card. |
| `loginCardColor` | The card itself. |
| `darkForeground` | Whether text on the page renders light-on-dark or dark-on-light. |
| `appTitle`, `faviconUrl` | Tab title/icon on the login page. |
| `showNameVersion`, `platformName`, `platformVersion` | Renders `{platformName} v.{platformVersion}`. |
| `namePosition` | Where that text sits — under the logo, or at the bottom of the card. |
| `paletteSettings` | Palettes applied pre-auth. |
| `baseUrl` | The base URL used in generated links. |

### 5.5 Mail templates (sys admin)

Overrides the subject and body of the platform's ten outgoing emails:

`test`, `activation`, `account.activated`, `account.lockout`, `reset.password`, `password.was.reset`,
`2fa.verification.code`, `state.enabled`, `state.warning`, `state.disabled`.

Every send in the platform routes through a single choke point, so a saved template wins over the
bundled defaults. Bodies are Freemarker with the platform's usual model variables, edited in a rich-text
editor.

> **Security.** Bodies are operator-supplied Freemarker. The engine is configured with
> `TemplateClassResolver.ALLOWS_NOTHING_RESOLVER`, which is what stops
> `<#assign x="freemarker.template.utility.Execute"?new()>` in a saved template from being remote code
> execution. This is a requirement, not a hardening nicety — if you are reviewing a merge that touched
> `DefaultMailService`, confirm that line survived.

The bundled default templates are already Inferrix-branded (see
[Branding](#9-branding-and-navigation)) and no longer load remote images or name the upstream product.

### 5.6 Privacy policy and terms of use

Free-form content served to both authenticated and anonymous callers
(`GET /api/noauth/whiteLabel/privacyPolicy`, `.../termsOfUse`). Sys admin and tenant admin can edit.

### 5.7 Preview, reset, and caching

- **Preview** — `POST /api/whiteLabel/previewWhiteLabelParams` merges a candidate set against the
  inherited chain without saving, so you can see the result before committing.
- **Reset** — `DELETE /api/whiteLabel/currentWhiteLabelParams` (or `.../currentLoginWhiteLabelParams`)
  clears this level's overrides and falls back to the inherited values.
- **Caching** — parameters are cached under the `whiteLabeling` cache. Tune with
  `CACHE_SPECS_WHITE_LABELING_TTL` (default 1440 minutes) and `CACHE_SPECS_WHITE_LABELING_MAX_SIZE`
  (default 10000). Saves evict transactionally, so a change is visible immediately.

### 5.8 Permissions

`WHITE_LABELING` is a first-class permission resource: sys admin and tenant admin have full control;
customer users may read and write their own. Under [Roles](#4-roles-and-permissions), a role can grant
or withhold it like any other resource.

---

## 6. Scheduler

PE-style scheduled events. An event has a name, a type, a schedule, an originator entity, and a
type-specific configuration. When it fires, the platform pushes a message into the tenant's rule engine
(or, for reports, submits a render job directly).

**Where:** **Features → Scheduler** (`/features/scheduler`). Visible to tenant admins and customer
users; customer users may create, read, update and delete their own events.

The page is a combined **list + calendar**: switch views, drag an event on the calendar to reschedule
it, right-click for its context menu.

### 6.1 Event types

| Type | What it does |
|---|---|
| `updateAttributes` | Writes attributes to the originator. Fires as `POST_ATTRIBUTES_REQUEST`. |
| `sendRpcRequest` | Sends an RPC to the originator device. Fires as `RPC_CALL_FROM_SERVER_TO_DEVICE`. |
| `updateFirmware` | Assigns an OTA firmware package. |
| `updateSoftware` | Assigns an OTA software package. |
| `generateReport` | Submits a report render job. See [Reporting](#7-reporting). Tenant admin only — the type is removed from the picker for customer users. |
| `generateDashboardReport` | Renders a dashboard directly to PDF/PNG/JPEG. |

### 6.2 Scheduling

| Field | Notes |
|---|---|
| `timezone` | IANA zone the schedule is interpreted in. |
| `startTime` | First fire. |
| `repeat.type` | `DAILY`, `EVERY_N_DAYS`, `WEEKLY`, `EVERY_N_WEEKS`, `MONTHLY`, `YEARLY`, `TIMER`. |
| `repeat.endsOn` | **Mandatory** for any recurring event. |
| `repeat.repeatOn` | `WEEKLY` — days of week, `0` = Sunday. |
| `repeat.days` / `repeat.weeks` | `EVERY_N_DAYS` / `EVERY_N_WEEKS` interval. |
| `repeat.repeatInterval` + `repeat.timeUnit` | `TIMER` — every N `SECONDS`/`MINUTES`/`HOURS`. |

`TIMER` events are floored at `SCHEDULER_MIN_INTERVAL_IN_SEC` (default 60).

> **`endsOn` is mandatory and recurring events die silently on that date.** The list carries a **Status**
> column — Active / Disabled / Expired — precisely so an expired event does not read as healthy. If a
> schedule "just stopped working", check Status first.

### 6.3 Originator

The originator is the entity the message is addressed to, and it is stored as a top-level field on the
event, not inside its configuration. The per-type configuration form picks it (a device for RPC, for
example) and the dialog lifts it out before saving.

Custom or raw event types with no originator picker default to the event's own id as originator.

> **Known limitation.** The rule engine's `TenantIdLoader` throws *"Unexpected entity type"* if a rule
> node attempts tenant resolution on a `SCHEDULER_EVENT` originator. Set an explicit originator
> (device or asset) on events whose downstream rule chains need tenant resolution. The per-type
> originator picker makes this the normal path for `updateAttributes` / RPC / OTA; only custom events
> are affected.

### 6.4 No rule chain of your own is needed

This is PE's design and now ours: the scheduler writes a **stock message type** that the default root
rule chain already routes.

| Event type | Message type pushed |
|---|---|
| `updateAttributes` | `POST_ATTRIBUTES_REQUEST` |
| `sendRpcRequest` | `RPC_CALL_FROM_SERVER_TO_DEVICE` |
| anything else | the event type verbatim (PE behaviour) |

An explicit `configuration.msgType` always wins. The mapping is applied in the engine, not only in the
UI, so events stored before this behaviour existed are repaired without a migration.

> This was a real defect: events saved with `msgType: null` fell through to the raw event type
> (`"updateAttributes"`), which is not a message type at all. The Message Type Switch dropped them down
> its `Other` branch into a Log node, and the event silently did nothing.

### 6.5 Message metadata

If the configuration carries a `metadata` map, it is used verbatim. Otherwise the engine supplies
`customerId`, `eventName`, and `additionalInfo`. `sendRpcRequest` additionally gets `originServiceId`,
and an `expirationTime` derived from `timeout` when one is set.

### 6.6 Clustering

Scheduler events propagate between tb-core nodes over the queue: a create/update/delete publishes a
`SchedulerServiceMsgProto` on the `tb-core` topic, and every node re-arms its in-memory timers. This is
why **scheduler events must be changed through the API, never with SQL** — a direct database write does
not re-arm the live event.

### 6.7 Permissions and audit

- `SCHEDULER_EVENT` is a permission resource. Tenant admins get full CRUD; customer users get CRUD on
  their own events.
- Scheduler CRUD is audit-logged. Tune with `AUDIT_LOG_MASK_SCHEDULER_EVENT` (default `W` — writes).
- Scheduler events are **not** edge-syncable.

---

## 7. Reporting

PE-style reporting: author a report template in a drag-and-drop designer, render it to PDF or CSV, and
deliver it on a schedule by email. Dashboards are captured by an in-process headless Chromium.

**Where:** **Reporting** (`/reporting`), tenant admin only, with three tabs:

| Tab | Route | What |
|---|---|---|
| Templates | `/reporting/templates` | Author, edit, import/export and generate report templates |
| Scheduling | `/reporting/scheduling` | The `generateReport` scheduler events, in report terms |
| Reports | `/reporting/reports` | Generated report history; download or delete |

### 7.1 The renderer is opt-in

**Nothing in this feature renders until the renderer is turned on.** Every render bean is
`@ConditionalOnProperty(reports.renderer.enabled)`, and the platform boots cleanly with it off — which
is the default, because rendering needs a headless Chromium that not every environment has.

```sh
export REPORTS_RENDERER_ENABLED=true
export REPORTS_RENDERER_BASE_URL="https://your-server"   # must be reachable BY the headless browser
```

With the renderer off:

- The Templates page and the designer still work; **Preview returns a placeholder** rather than a PDF.
- `generateReport` scheduler events hit a WARN branch and produce nothing.
- The `generate report` rule node fails the job at intake — no orphan render task is enqueued.
- On-demand download endpoints refuse the request.

`REPORTS_RENDERER_BASE_URL` must be the URL the *headless browser inside the server process* can reach,
which is not always the URL your users type. It defaults to `http://localhost:8080`.

`REPORTS_RENDERER_BROWSER_PATH` may point at a system Chromium; left empty, Playwright's own managed
browser is used.

### 7.2 Report templates

A template is a name, a description, a file-name pattern, a default date format, and an ordered list of
**components**. Its format is either `PDF` or `CSV`.

**PDF templates** additionally carry page settings — page size (`A4`, `A3`, `A5`, `LETTER`, `LEGAL`,
`TABLOID`), orientation (`PORTRAIT` / `LANDSCAPE`), margins, and a page background — plus a running
**header** and **footer**, each of which can have a distinct first-page variant.

A new PDF template opens with its header and footer already enabled, a file-name pattern of
`report-%d{yyyy-MM-dd_HH:mm:ss}`, a date pattern of `yyyy-MM-dd HH:mm:ss`, and a white page background.

### 7.3 Components

| Component | Renders | PDF | CSV |
|---|---|---|---|
| `HEADING` | A heading line, with font, colour and alignment | yes | — |
| `RICH_TEXT` | Formatted HTML | yes | — |
| `DIVIDER` | A horizontal rule | yes | — |
| `PAGE_BREAK` | A forced page break | yes | — |
| `IMAGE` | A gallery image, or an image resolved from an entity key | yes | — |
| `ENTITY_TABLE` | A table of entities and their attributes/telemetry | yes | yes |
| `ALARM_TABLE` | A table of alarms | yes | yes |
| `TIME_SERIES_TABLE` | A timeseries table | yes | yes |
| `SUB_REPORT` | Another report template, embedded | yes | yes |
| `DASHBOARD` | A dashboard, captured by headless Chromium and embedded as an image | yes | — |

Every layout-bearing component supports margins, paddings, background, border width, border radius and
alignment.

### 7.4 The designer

Full-page, three panes, at `/reporting/templates/:reportTemplateId` (or `/new`):

- **Palette** (left) — 23 items: the 10 component types plus 13 ready-made presets (logos, title blocks,
  common table shapes). Drag onto the canvas.
- **Canvas** (centre) — the report body, with the header and footer as their own drop zones above and
  below it. Reorder by dragging. A header or footer can be collapsed or disabled without losing its
  contents.
- **Configuration panel** (right) — the selected component's settings, or, with nothing selected, the
  **Common settings** (name, file-name pattern, default date format, description) and **Page settings**
  cards.

**Toolbar:** Settings, Aliases, Filters, fullscreen, Decline, Save. Decline and Save activate only once
something has actually changed.

**Preview** toggles the canvas for a rendered PDF of the current, unsaved configuration. It renders as
*you*, the logged-in tenant admin — which is why a preview can look right while the scheduled version
of the same report arrives empty. See [§7.9](#79-why-a-scheduled-report-arrives-empty).

**Dynamic fields.** Headings and rich text can embed `${…}` expressions inserted from a menu.

**Import / export.** A template round-trips as JSON. Editing preserves fields the designer does not
itself expose — a template authored elsewhere is not silently rewritten by opening it.

**Entity aliases and filters** are authored in the toolbar dialog and behave as they do on dashboards;
table components bind their data sources to them.

> **Not available:** version control for report templates. ThingsBoard CE has no version-control
> support for the `REPORT_TEMPLATE` entity type, so the designer has no Versions button.

### 7.5 Scheduling a report

A scheduled report **is a scheduler event of type `generateReport`**. The Reporting → Scheduling tab is
a filtered, report-shaped view of the same events you would see under Features → Scheduler.

Its configuration:

| Field | Meaning |
|---|---|
| `reportTemplateId` | The template to render. |
| `userId` | **The identity the report is rendered as** — *not* the recipient. See [§7.9](#79-why-a-scheduled-report-arrives-empty). |
| `timezone` | Zone used for the report's time window and name pattern. |
| `targets` | Notification targets — **this is who receives it**. Required. |
| `notificationTemplateId` | Optional. Left empty, an inline WEB + EMAIL template is used. |

The generated report is stored, then delivered as an email attachment through the platform's
notification system.

### 7.6 On-demand generation

- **Templates page → Generate** renders immediately, as the calling tenant admin.
- `POST /api/v2/report/test` renders a candidate configuration without saving it — what the designer's
  Preview uses.
- `POST /api/report/{dashboardId}/download` renders a dashboard directly to PDF, PNG or JPEG.

### 7.7 The `generate report` rule node

An action node named **generate report** (`ComponentType.ACTION`), available in the rule-chain editor
with its own configuration form.

| Setting | Effect |
|---|---|
| `useConfigFromMessage = false` (default) | Uses the static `ReportConfig` configured on the node. |
| `useConfigFromMessage = true` | Parses the `ReportConfig` from the incoming message body. |

The node submits a job and acknowledges the message; the finished report is pushed back into the chain
as an output message.

> **`useConfigFromMessage = true` lets an incoming message choose any user and any notification target
> within the tenant.** That is PE-parity behaviour and it is the rule-chain author's decision to enable
> it. Treat it as you would any other node that takes its configuration from message data.

### 7.8 Limits and safety rails

Several of these are Inferrix hardening that PE does not have:

- **Sub-report recursion is bounded** — maximum depth 10, and a shared cap of 1000 total sub-report
  renders per report. PE bounds neither, so a self-referencing or cyclic template can be made to fan out
  exponentially.
- **CSV formula injection is neutralised** — leading `=`, `+`, `-`, `@`, including full-width and
  leading-whitespace variants. PE does not do this.
- **Images resolve from bundled assets and the tenant's own image store only.** Arbitrary `http`,
  `https`, `file` or `ftp` image URIs return an error image rather than being fetched, closing an SSRF
  and local-file-read path.
- **The dashboard capture URL is always the trusted server URL.** A per-component `baseUrl` override is
  ignored, so template JSON cannot redirect the minted access token to an attacker's host.
- **User-supplied text is not evaluated as a template.** Headings and rich text go through a data-only
  `${var}` substitution, not an expression engine.
- **Fonts and colours are allowlisted / normalised**, closing CSS injection through a font-family or
  colour field.
- Unsupported component/feature combinations render an **error box** in place of that component rather
  than failing the whole report.
- `REPORTS_GENERATION_TIMEOUT_MS` (default 120s) bounds a single render;
  `REPORTS_RENDERER_MAX_CONCURRENT` (default 2) bounds how many run at once.

The PDF stack is Thymeleaf + Flying Saucer + **OpenPDF** (LGPL). iText/AGPL is deliberately absent —
`mvn dependency:tree` should show `com.github.librepdf:openpdf` and zero `com.itextpdf`.

### 7.9 Why a scheduled report arrives empty

This is the single most common reporting support question, so it gets its own heading.

**A scheduled report is rendered by a headless browser logged in as the event's `userId`.** The report
therefore contains exactly what that user is permitted to see, and nothing more. A customer user whose
customer owns none of the dashboard's devices produces a report with all the dashboard chrome and
*"No data to display on widget"* in every widget. Nothing fails and nothing is logged — the render
succeeds, and the empty PDF is stored and emailed.

Manual download and the designer's Preview default to the **calling tenant admin**, which is why the
same template downloaded locally has data. Same template, same minute, different identity — that is the
only variable.

**Fix it as configuration, by intent:**

- Point the event's user at a **tenant admin** for a tenant-wide report, or
- **assign the entities to the customer** for a customer-scoped report.

Delivery is controlled by *Notification targets*, not by this user field — changing the user does not
change who receives the mail.

Apply the change through `POST /api/schedulerEvent`, **never with SQL**: the engine re-arms a live event
from the API path only.

> The scheduler event form labels this field **"Generate report as user"** for exactly this reason. If
> you are looking at an older build that calls it *"Recipient user"*, that label is wrong — it was never
> the recipient.

### 7.10 Delivery checklist

When a scheduled report is not arriving, check these in order:

1. **`REPORTS_RENDERER_ENABLED=true` on the server.** Default is false.
2. **Event type.** Both `generateReport` and `generateDashboardReport` work. With the renderer on,
   `generateDashboardReport` renders directly and needs no rule chain — which also means the
   *generate dashboard report* rule node does not receive scheduler-originated messages on a
   renderer-on node.
3. **The schedule has not expired.** `endsOn` is mandatory; check the Status column.
4. **Notification targets are non-empty.** Required for `generateReport`. For `generateDashboardReport`
   they are top-level `targets` / `notificationTemplateId` on the event configuration — an Inferrix
   addition PE has neither of, so PE-era events carry none and generate but never send.
5. **SMTP is configured.** Otherwise delivery throws "No delivery methods", logged by
   `ReportJobProcessor.onJobFinished`.
6. **Render identity** — see [§7.9](#79-why-a-scheduled-report-arrives-empty). This is the gate that
   produces a successful-but-empty report.

---

## 8. Widget Data Export

An export button on dashboard widgets that downloads the widget's data as **CSV**, **XLS** or **XLSX**.

**Where:** the widget header, next to the fullscreen button, on hover. Hidden in dashboard edit mode.

### 8.1 Which widgets have it

Controlled per widget instance by `config.enableDataExport`. When that field is not set, the default is:

| Widget type | Export button |
|---|---|
| `timeseries` (Timeseries table and friends) | **on** |
| `latest` (Entities table and friends) | **on** |
| `alarm` (Alarms table) | **on** |
| everything else | off |

Override it per widget: **widget settings → Advanced → Card buttons → Enable data export**. The toggle
sits next to the existing "Enable fullscreen" toggle and works on any widget type.

> This is PE's exact rule. Note that the default is keyed on *widget type*, not on the three specific
> table widgets — so a custom widget declared as `timeseries` also gets the button by default.

### 8.2 What gets exported

**The currently loaded page/window of the widget's subscription** — bounded by that widget's own
pagination and time window. It is not a fresh, unbounded re-query, so what you export is what the widget
is showing.

- **Timeseries** exports as a flattened long-format table: `Entity`, `Timestamp`, then one column per
  data key, across **all** configured datasources together. This differs from the Timeseries table
  widget's own per-source tabs, which are private to that component.
- **Latest** exports the entity rows and their key columns.
- **Alarm** exports the alarm rows and their key columns.

Cell values are the **raw subscribed values**, not each widget's custom per-key cell-content function. A
widget that renders `42` as `"42 °C"` via a cell function exports `42`.

### 8.3 Safety

The writer neutralises spreadsheet formula injection (leading `=`, `+`, `-`, `@`) so an attacker-supplied
device name or attribute value cannot execute when the file is opened in Excel or Sheets. This mirrors
the server-side guard used by [CSV reports](#78-limits-and-safety-rails).

---

## 9. Branding and navigation

### 9.1 Default build branding

Inferrix branding is **compiled into the default build**. Every `yarn build:prod` output is
Inferrix-branded; there is no special build configuration and no white-labeling runtime setup involved.

| | |
|---|---|
| Primary colour | `#2b388f` (blue) |
| Accent colour | `#ec1c24` (red) |
| Secondary | `#4553a8` |
| Logos | Inferrix mark and slogan logo, white variants for dark chrome |
| Favicon | Inferrix |
| Browser title | `Inferrix Cortex` |
| Dashboard footer | `Powered by Inferrix Cortex v.x` → `inferrix.com` |
| Console banner | `Inferrix Cortex Version:` |
| TOTP issuer | `Inferrix Cortex` |

The sidebar logo is rendered at 52px in a 64px header, and the upstream "Star on GitHub" badge is gone.

**Outgoing mail** is rebranded end to end: all ten templates and all eight subjects name *Inferrix
Cortex*, the sign-off is *The Inferrix Cortex Team*, and the three API-usage templates no longer load
remote images from `media.thingsboard.io` — these were the only external asset loads in platform email.
(The Apache licence headers in those template files keep their upstream attribution; the licence check
enforces that.)

**Fresh-install defaults** use Inferrix domains: seeded accounts are `sysadmin@inferrix.com`,
`tenant@inferrix.com`, `customer@inferrix.com` (and `customerA/B/C@`); the default "Mail From" is
`Inferrix Cortex <sysadmin@localhost.localdomain>`; the JWT issuer defaults to `inferrix.com`.

> **Existing installs are not migrated.** These are install-time defaults written once into
> `admin_settings` and the `tb_user` table. An install that predates them keeps its
> `@thingsboard.org` accounts and its old mail-from/issuer values until you change them via the UI or
> the API.

**Home-page dashboards** drop the upstream Version card, the CE-vs-PE Functions card with its
"Switch to PE" link, and the Connect-mobile-app QR cards, with the surrounding widget geometry adjusted
to fill the gap.

To rebrand at runtime instead — per tenant, per customer, without a rebuild — use
[White Labeling](#5-white-labeling).

### 9.2 Tenant sidebar

The tenant administrator menu is deliberately slimmer than upstream's.

**Removed entirely** — menu entries *and* routes:

- **Edge Management** (Edges, Rule chain templates)
- **OTA updates**, including the legacy `/otaUpdates` URLs

The backend edge and OTA REST APIs are untouched, and the tenant-admin sub-page
`/customers/:customerId/edges` is deliberately kept. Home-page cards for removed sections disappear on
their own. The customer-user Edge instances entry was removed with them, since its target route no
longer exists.

**Grouped:**

| Group | Contains |
|---|---|
| **Utilities** | API usage, Mobile center (Mobile bundles, Mobile apps) |
| **Administration** | Settings (its 6 original tabs), White labeling, Roles, Users, Notification center (its 5 pages) |

Both are toggles — they expand in place rather than navigating. Neither auto-expands from the current
URL, an accepted cosmetic tradeoff.

Sys admin and customer user menus are unchanged apart from the removals noted above.

### 9.3 Inferrix menu entries

| Entry | Path | Authority |
|---|---|---|
| Scheduler | `/features/scheduler` | Tenant admin, customer user |
| Reporting | `/reporting` | Tenant admin |
| White labeling | `/white-labeling` | Sys admin, tenant admin, customer user |
| Roles | `/roles` | Tenant admin |
| Users | `/users` | Tenant admin, customer user |

---

## 10. Configuration reference

Every Inferrix-added configuration key, its environment variable, and its default. Set environment
variables in `/etc/thingsboard/conf/thingsboard.conf`; the YAML paths below refer to
`/etc/thingsboard/conf/thingsboard.yml` — see [§2.2](#22-configuration-file-layout) for why the external
file is the one that counts.

### 10.1 License Control

| YAML path | Env var | Default | Meaning |
|---|---|---|---|
| `license.key` | `INFERRIX_LICENSE_KEY` | *(empty)* | The signed licence key. Empty → exit 13. |
| `license.enforcement.enabled` | `INFERRIX_LICENSE_ENFORCEMENT_ENABLED` | `true` | Master switch. Exists so the platform's own test suite can run without a key. **Setting this false in a real deployment disables licence enforcement entirely and must never be done.** |
| `license.check_interval_ms` | `INFERRIX_LICENSE_CHECK_INTERVAL_MS` | `3600000` (1h) | How often the key is re-verified at runtime. |
| `license.clock_tolerance_ms` | `INFERRIX_LICENSE_CLOCK_TOLERANCE_MS` | `3600000` (1h) | How far the clock may move backwards before it counts as tampering. Absorbs an NTP correction. |
| `license.max_high_water_advance_ms` | `INFERRIX_LICENSE_MAX_HIGH_WATER_ADVANCE_MS` | `86400000` (24h) | How far a single check may advance the clock-rollback high-water mark. See [§3.5](#35-recovering-from-exit-code-18). |

### 10.2 Reporting

| YAML path | Env var | Default | Meaning |
|---|---|---|---|
| `reports.renderer.enabled` | `REPORTS_RENDERER_ENABLED` | `false` | **Master switch.** Off → nothing renders. |
| `reports.renderer.base_url` | `REPORTS_RENDERER_BASE_URL` | `http://localhost:8080` | The UI URL reachable **by the headless browser**. |
| `reports.renderer.browser_path` | `REPORTS_RENDERER_BROWSER_PATH` | *(empty)* | Path to a system Chromium. Empty → Playwright-managed browser. |
| `reports.renderer.max_concurrent` | `REPORTS_RENDERER_MAX_CONCURRENT` | `2` | Concurrent renders. |
| `reports.renderer.dashboard_settle_ms` | `REPORTS_RENDERER_DASHBOARD_SETTLE_MS` | `5000` | After a dashboard reports itself settled, wait up to this long for network quiet before capturing. Covers widgets that fetch their own assets (custom widgets, map tiles) outside the readiness protocol. Best-effort: a continuously-polling dashboard never reaches idle and is captured when this elapses. |
| `reports.generation_timeout_ms` | `REPORTS_GENERATION_TIMEOUT_MS` | `120000` | Per-render timeout. |
| `reports.test_report_pool_size` | `REPORTS_TEST_REPORT_POOL_SIZE` | `12` | Pool for preview/test renders. |

### 10.3 Scheduler

| YAML path | Env var | Default | Meaning |
|---|---|---|---|
| `scheduler.min_interval` | `SCHEDULER_MIN_INTERVAL_IN_SEC` | `60` | Minimum interval for `TIMER` events, in seconds. |
| `audit-log.logging-level.mask.scheduler_event` | `AUDIT_LOG_MASK_SCHEDULER_EVENT` | `W` | Audit-log mask for scheduler event CRUD. |

### 10.4 White Labeling

| YAML path | Env var | Default | Meaning |
|---|---|---|---|
| `cache.specs.whiteLabeling.timeToLiveInMinutes` | `CACHE_SPECS_WHITE_LABELING_TTL` | `1440` | WL parameter cache TTL. |
| `cache.specs.whiteLabeling.maxSize` | `CACHE_SPECS_WHITE_LABELING_MAX_SIZE` | `10000` | `0` disables the cache. |

### 10.5 Branding

| YAML path | Env var | Default | Meaning |
|---|---|---|---|
| `security.jwt.tokenIssuer` | `JWT_TOKEN_ISSUER` | `inferrix.com` | JWT issuer, shown under Security → General. Changed from upstream's `thingsboard.io`. |

---

## 11. REST API reference

Inferrix-added endpoints. All are under `/api` and require a bearer token except where marked
`noauth`. Stock ThingsBoard endpoints are unchanged and documented upstream.

### 11.1 License

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/license/info` | Capacity, expiry. Customer name and instance ID **omitted** for non-sys-admin callers. |

### 11.2 Roles

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/role/{roleId}` | |
| `POST` | `/api/role` | Create or update. |
| `DELETE` | `/api/role/{roleId}` | |
| `GET` | `/api/roles?pageSize&page` | Seeds the two default roles on first call. |
| `GET` | `/api/user/{userId}/roles` | |
| `POST` | `/api/user/{userId}/roles` | Replaces the user's whole role set. Refused for non-customer users and for your own account. |

### 11.3 White labeling

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/whiteLabel/whiteLabelParams` | Merged, inherited parameters for the caller. |
| `GET` | `/api/whiteLabel/currentWhiteLabelParams` | This level's own overrides only. |
| `POST` | `/api/whiteLabel/whiteLabelParams` | Save. |
| `DELETE` | `/api/whiteLabel/currentWhiteLabelParams` | Reset to inherited. |
| `GET` | `/api/noauth/whiteLabel/loginWhiteLabelParams` | **noauth** — what `/login` reads. |
| `GET` | `/api/whiteLabel/currentLoginWhiteLabelParams` | |
| `POST` | `/api/whiteLabel/loginWhiteLabelParams` | Save. |
| `DELETE` | `/api/whiteLabel/currentLoginWhiteLabelParams` | Reset. |
| `POST` | `/api/whiteLabel/previewWhiteLabelParams` | Merge without saving. |
| `GET` | `/api/whiteLabel/isWhiteLabelingAllowed` | |
| `GET` | `/api/whiteLabel/isCustomerWhiteLabelingAllowed` | |
| `GET` | `/api/whiteLabel/privacyPolicy` · `/api/noauth/whiteLabel/privacyPolicy` | |
| `POST` | `/api/whiteLabel/privacyPolicy` | |
| `GET` | `/api/whiteLabel/termsOfUse` · `/api/noauth/whiteLabel/termsOfUse` | |
| `POST` | `/api/whiteLabel/termsOfUse` | |
| `GET` | `/api/whiteLabel/mailTemplates` | Sys admin. |
| `POST` | `/api/whiteLabel/mailTemplates` | Sys admin. |

### 11.4 Scheduler

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/schedulerEvent/{schedulerEventId}` | Full event including configuration. |
| `GET` | `/api/schedulerEvent/info/{schedulerEventId}` | Projection without configuration. |
| `POST` | `/api/schedulerEvent` | Create or update. **The only supported way to change a live event** — re-arms the engine. |
| `PUT` | `/api/schedulerEvent/{schedulerEventId}/enabled/{enabledValue}` | Enable/disable. |
| `DELETE` | `/api/schedulerEvent/{schedulerEventId}` | |
| `GET` | `/api/schedulerEvents` | All for the caller's scope. |
| `GET` | `/api/schedulerEvents?pageSize&page` | Paged. |
| `GET` | `/api/schedulerEvents?startTime&endTime` | Calendar window. |
| `GET` | `/api/schedulerEvents?schedulerEventIds=` | By id list. |

### 11.5 Reporting

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/reportTemplate/{reportTemplateId}` | |
| `GET` | `/api/reportTemplate/info/{reportTemplateId}` | |
| `POST` | `/api/reportTemplate` | Create or update. |
| `DELETE` | `/api/reportTemplate/{reportTemplateId}` | |
| `GET` | `/api/reportTemplateInfos/all?pageSize&page` | |
| `GET` | `/api/reportTemplates?reportTemplateIds=` | |
| `POST` | `/api/v2/report` | Generate from a saved template. |
| `GET` | `/api/v2/report/{reportId}` | |
| `GET` | `/api/v2/report/{reportId}/download` | The rendered file. |
| `DELETE` | `/api/v2/report/{reportId}` | |
| `GET` | `/api/v2/reportInfos?pageSize&page` | History. Accepts `reportTemplateId`, `userId`, `includeCustomers`. |
| `GET` | `/api/v2/reportInfos/all?pageSize&page` | |
| `POST` | `/api/v2/report/test` | Render an unsaved template configuration. Renderer-gated; tenant admin; renders as the caller. This is what the designer's Preview calls. |
| `POST` | `/api/v2/report/request` | Enqueue a render job. Renderer-gated. |
| `POST` | `/api/report/{dashboardId}/download` | On-demand dashboard render → `application/pdf`, `image/png`, `image/jpeg`. |
| `POST` | `/api/report/test` | On-demand dashboard render of an unsaved dashboard-report config. Distinct from `/api/v2/report/test` above. |

> The report *history/generation* endpoints live under **`/api/v2`**; the report *template* and
> *dashboard-report* endpoints live under **`/api`**. `POST /api/report/test` and
> `POST /api/v2/report/test` are two different endpoints.

---

## 12. Troubleshooting

| Symptom | Most likely cause |
|---|---|
| Platform exits immediately, code 13–18 | A licence problem. See [§3.4](#34-exit-codes). Under Docker Compose the container will restart-loop; stop it rather than waiting. |
| Exit 18 but this node's clock is correct | Another node's fast clock poisoned the high-water mark. See [§3.5](#35-recovering-from-exit-code-18) — confirm which value is wrong before running the recovery SQL. |
| Licence block set but nothing changed | It was set in the jar's `thingsboard.yml`, not the external one. See [§2.2](#22-configuration-file-layout). |
| A customer user's dashboards show *"Problem loading widget configuration"* | Their role is missing `Widget type` → Read. See [§4.2](#42-authoring-a-role). |
| A customer user sees no telemetry despite a `Read` grant | Operations match exactly; `Read` does not imply `Read telemetry`. |
| Roles seem to have no effect at all | The `role` / `user_role` tables are missing — the schema overlay was not applied. Look for `RBAC tables are missing` in the log. Roles fail **open**. |
| A control widget on a public dashboard does nothing | Intentional. Public viewers are refused `RPC_CALL`. See [§4.6](#46-the-anonymous-public-dashboard-viewer). |
| A deleted seeded role keeps coming back | Seeding re-creates the two default roles on each visit to the Roles page. Edit one to empty instead of deleting it. |
| White-labeling change does not appear | Check the scope you edited (system / tenant / customer) and that the field is actually blank at the level below. An emptied field must be saved as blank so inheritance resumes. |
| Branding missing on `/login` | The image field must hold a public link (`/api/images/public/{key}`). Re-pick it through the gallery picker. |
| Scheduler event never fires | Check Status — a recurring event with a past `endsOn` reads as Expired. |
| Scheduler event fires but nothing happens downstream | Its `msgType` fell through to the raw event type and the Message Type Switch logged it. Re-save the event through the API. |
| Scheduler change made in SQL has no effect | The engine re-arms from `POST /api/schedulerEvent` only. |
| Rule node gets *"Unexpected entity type"* for a scheduler event | Set an explicit device/asset originator. See [§6.3](#63-originator). |
| Report preview returns a placeholder | `REPORTS_RENDERER_ENABLED` is false. |
| Report renders locally but the emailed one is empty | Render identity. See [§7.9](#79-why-a-scheduled-report-arrives-empty). |
| Scheduled report never arrives | Walk [§7.10](#710-delivery-checklist) in order. |
| Dashboard in a report is captured half-loaded | Raise `REPORTS_RENDERER_DASHBOARD_SETTLE_MS`. |
| Widget export button missing | The widget is not `timeseries`/`latest`/`alarm`, or the dashboard is in edit mode. Turn it on per widget under Advanced → Card buttons. |
| Exported values differ from what the widget displays | Export writes raw subscribed values, not cell-content function output. |
| Frontend build fails | Node 18 is not supported; use Node 22. |

---

## 13. Maintaining the fork

### 13.1 Where Inferrix code lives

Two kinds of change:

**Additive, Inferrix-owned paths.** New files under new directories. These never conflict on an upstream
merge and are not tracked in the ledger:

```
common/data/.../{scheduler,report,dashboardreport,license,role}/**
dao/.../{scheduler,report,wl,license,role}/**
dao/src/main/resources/sql/schema-inferrix.sql
application/.../service/{scheduler,report}/**
application/.../controller/{SchedulerEvent,Report,ReportTemplate,DashboardReport,License,Role,WhiteLabeling}Controller.java
rule-engine/rule-engine-components/.../rule/engine/report/**
ui-ngx/src/app/modules/home/pages/{scheduler,report,white-labeling,role}/**
ui-ngx/src/app/core/http/{scheduler-event,report,license,white-labeling}.service.ts
ui-ngx/src/app/shared/models/{scheduler-event,report,report-configuration,white-labeling,license}.models.ts
```

**Modifications to upstream files.** Every one of these is recorded in `INFERRIX-PATCHES.md` with what
was changed, why, and how to recover it. There are roughly 150 such rows across nine features.

### 13.2 Upstream merges

Upstream periodically **rebuilds** `release-4.3`'s history, so a sync is a re-merge against an old merge
base rather than a fast-forward. Expect roughly 68 conflicts, including spurious rename/rename
conflicts.

```bash
git fetch upstream
git merge upstream/release-4.3
```

Then walk `INFERRIX-PATCHES.md` row by row. Most Inferrix patches are **not compiler-enforced** — a
dropped one compiles cleanly and fails silently at runtime, so the ledger's per-row recovery greps are
the check, not the build.

The highest-risk files, in order:

1. **`ui-ngx/src/app/core/services/menu.models.ts`** — carries White labeling, Scheduler, Reporting and
   the menu restructure. Four features, many insertion points, and upstream edits these same maps every
   release.
2. **`ui-ngx/src/assets/locale/locale.constant-en_US.json`** — a 10,000-line file upstream churns
   constantly, in which Inferrix content is a handful of scattered blocks. Splice as raw text on
   recovery; **never** run it through a JSON formatter.
3. **`application/.../service/mail/DefaultMailService.java`** — losing the Freemarker
   `ALLOWS_NOTHING_RESOLVER` line leaves the feature working while reopening a remote-code-execution
   path.
4. **`ui-ngx/src/app/modules/home/components/widget/widget-container.component.html`** — losing the
   export block silently removes the button with everything else intact.

Version bumps follow one rule: bump only if upstream ships a database upgrade at a version we already
occupy. A shared version label on its own is not a conflict.

### 13.3 Adding new database objects

Append idempotent DDL to `dao/src/main/resources/sql/schema-inferrix.sql`. Nothing else changes: no
upstream schema file, no new seam, no new ledger row. See [§2.1](#21-the-database-schema-overlay).

### 13.4 Conventions

- New Inferrix-authored frontend files carry a `The Inferrix Authors` licence header, not
  `The Thingsboard Authors`. There is no build-time enforcement of this on the frontend; it is
  convention.
- In Angular templates, use the `translate` **pipe**, not the `translate` attribute. As an attribute on
  Material components (`mat-option`, `mat-checkbox`) it silently ships the raw i18n key.
- Reuse ThingsBoard's own services directly. The Inferrix modules depend on all upstream modules;
  parallel implementations of something the platform already provides are the wrong shape here.
