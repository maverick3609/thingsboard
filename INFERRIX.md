# Inferrix — feature documentation

Narrative documentation for Inferrix-specific features on this fork. For the TB-core merge-survival
ledger (which upstream files were changed, and how to recover a patch after `git merge
upstream/release-4.3`), see `INFERRIX-PATCHES.md` instead.

This file is the feature documentation for `inferrix-release-4.3`. `master` separately carries an
unrelated, older `INFERRIX.md` (commit `f2e6ce9aa9`) documenting the superseded "Inferrix Synapse"
`src/inferrix/` branding overlay — the two branches are divergent, these are not versions of the same
document, and they should not be merged or reconciled as if they were.

## Roles and Permissions

PE-style role-based access control, ported as *generic roles assigned directly to users*. A role is a set
of `resource -> [operations]` grants; a user may hold several and gets the union.

### Who a role applies to

**Customer users only.** A tenant administrator has full rights over their own tenant and cannot be
restricted by a role — assigning one is refused (`403 Roles may only be assigned to customer users!`), and
the *Manage roles* action is disabled on non-customer rows. A sys admin is never restricted either.

A customer user with **no** role keeps the plain authority-based access ThingsBoard has always given them,
so the feature is opt-in per user and needs no migration or backfill.

> ThingsBoard PE differs here. PE routes tenant admins through the same role machinery and merely seeds
> them a generic `ALL: [ALL]` role, so PE *can* restrict a tenant admin. This fork deliberately cannot.
> The behaviour out of the box is identical; the capability is narrower.

### Authoring a role

**Administration → Roles → Add role.** Each row grants a set of operations on one resource; `All` is a
wildcard on either side.

Three things regularly surprise people:

- **Operations match exactly.** `Read` does **not** imply `Read telemetry`, `Read attributes` or
  `Read credentials`. A role that must serve a dashboard needs those listed explicitly.
- **Dashboards need `Widget type` Read.** Every dashboard — including the built-in Home page — resolves
  each of its widgets through `GET /api/widgetType?fqn=`, which is permission-checked. Without the grant
  the page renders as a column of *"Problem loading widget configuration. Probably associated widget type
  was removed."* That is ThingsBoard's generic message; nothing in it says "permission". Browsing the
  widget library additionally needs `Widgets bundle` Read.
- **A user needs nothing extra to be themselves.** Reading your own user record, editing your own
  profile, and — for a customer user — reading your own customer record are never role-gated. Every
  session bootstraps with `GET /api/user/{self}`, `checkCustomerId` fronts every customer-scoped list,
  and editing your own name or language is something the platform gives every user, so gating any of
  them would lock the account down rather than restrict it. The exemption stops there: **deleting**
  your own account is never exempt, and the write exemption is your *user record* only — a customer
  user still has no write on their own Customer.

### Assigning

**Administration → Users** lists every user in the tenant for a tenant admin (read-only — creating and
deleting a user stays on the page that owns its authority, the tenant's or the customer's own users page).
The *Manage roles* action replaces a user's whole set; an empty selection removes all roles and restores
default access. The same page shown to a *customer* user lists only their own customer's users, and is
read-only unless they hold the customer-administrator grant described below.

Nobody may change their **own** roles, in the UI or over the API — clearing them would restore
unrestricted access to the person doing the clearing.

### The two seeded roles

The first time a tenant opens **Administration → Roles**, two roles are created if they are absent
(PE parity with `findOrCreateCustomerAdminRole` / `findOrCreateCustomerUserRole`):

| Role | Grants | For |
|------|--------|-----|
| `Customer Administrator` | `All: [All]` | a customer user who manages their own customer's users |
| `Customer User` | `All: [Read, Read credentials, Read attributes, Read telemetry, RPC call]` | plain read-only access |

They are ordinary roles — edit them, or ignore them and author your own. Note they are re-created on the
next visit to the Roles page if deleted, so removing one is not permanent; edit it to empty instead.
Seeding is idempotent and never touches a role that already exists under that name.

Note the seeded `Customer User` role does grant `Read credentials` and `RPC call` — PE's read-only set
includes both. That is a deliberate contrast with the anonymous viewer further down, who is refused both:
a named customer user is someone the tenant chose to create and can revoke, while a public dashboard link
is held by whoever happens to have the URL.

PE seeds five roles per tenant; we seed these two. The other three do not port:

| PE role | Why not |
|---------|---------|
| `Tenant Administrator` | PE reaches full tenant access by *seeding* an `ALL: [ALL]` role. Here a tenant admin has full rights inherently, so the role would grant nothing that is not already true. |
| `Tenant User` | Its whole purpose is to restrict a tenant admin to read-only. Roles restrict customer users only here, so no tenant admin could ever hold it. |
| `Public User` | Ported, but **not as a role** — see below. CE does have public users, but they are synthetic (no database row), so there is nothing to assign a role to. |

The cost of leaving the tenant pair out is a **read-only junior tenant administrator**: someone who can see
everything in the tenant but change nothing. That role is not expressible here, and will not be while roles
stay customer-scoped. Reversing it means dropping three guards — the authority checks at the top of
`DefaultUserPermissionsService.getMergedPermissions` and in `UserPermissionsUtil.granted`, and the
"customer users only" check in `RoleController.updateUserRoles` — after which an explicitly assigned role
would restrict a tenant admin too. Nobody's access would change until a role is actually assigned, since a
role-less user keeps full authority-based access either way.

### The customer administrator

Give a customer user a role granting `User` **Write** — the seeded *Customer Administrator* does, via its
`All: [All]` — and they may manage the other users of **their own customer**:

- **Administration → Users** becomes read-write for them, and lists only their own customer's users.
- Create, edit and delete a colleague, and copy an activation link so the colleague can set a password.
- Add and Delete additionally require `User` **Create** / **Delete**; `All: [All]` covers both.

Scope is pinned on the server, never taken from the request body: a new user's tenant, customer and
authority all come from the caller. An attempt to create a `TENANT_ADMIN`, or a user inside another
customer, is silently downgraded to a customer user of the caller's own customer rather than refused.

Still refused for a customer administrator, and the buttons are hidden accordingly: impersonation
(*Login as user*), assigning roles (a tenant admin only), disabling an account, and resending activation
mail. The activation **link** is available — that is the intended way to hand off a new account.

The capability is **explicit**: it needs a role that names `User` Write. A customer user with no role sees
the page exactly as read-only as before, so shipping this widened nobody's access by default.

### The anonymous public-dashboard viewer

Publishing a dashboard makes it readable by anyone holding the link. That viewer authenticates with
nothing but the public id and arrives as a **customer user of the public customer** — synthetic, with no
database row, so no role can be assigned to it. Its permissions are therefore fixed in code
(`DefaultUserPermissionsService.PUBLIC_USER_PERMISSIONS`) rather than seeded as a role, which is the one
place this fork *has* to diverge from PE's shape while matching its effect.

The set is PE's two public roles unioned, minus RPC — `{ALL: [READ, READ_ATTRIBUTES, READ_TELEMETRY]}` —
unioned because we have no entity groups to carry the second half. `CustomerUserPermissions` still confines the
viewer to the public customer's own entities, which is what group membership did in PE.

Stock ThingsBoard grants a public viewer far more than reading. Measured against a published dashboard,
before and after:

| An anonymous link holder could… | before | now |
|---|---|---|
| read the device's **access token** (`/credentials`) | ✅ 200 | ⛔ 403 |
| write shared attributes | ✅ 200 | ⛔ 403 |
| write timeseries | ✅ 200 | ⛔ 403 |
| rename or otherwise modify the device | ✅ 200 | ⛔ 403 |
| send RPC to the device (actuate it) | ✅ 200 | ⛔ 403 |
| read the dashboard, device, attributes, telemetry, alarms | ✅ 200 | ✅ 200 |
| delete the device, list tenant devices | ⛔ 403 | ⛔ 403 |

The credentials read is the one worth dwelling on: the access token does not expire, so anyone who opened
a public dashboard could publish data as that device indefinitely, from anywhere, long after the link was
withdrawn.

> **Stricter than PE in one place, on purpose.** PE's public entity-group permissions include
> `RPC_CALL`, so a PE public dashboard can carry working control widgets. We drop it: anyone holding a
> dashboard link could otherwise actuate the devices on it. The consequence is that a **control widget on
> a public dashboard is inert** — it renders, and the command is refused with 403. If a public dashboard
> ever legitimately needs to actuate, that is the line to revisit (`PUBLIC_USER_PERMISSIONS` in
> `DefaultUserPermissionsService`), not a per-widget workaround.

### How it is enforced

- Roles are AND-ed in after the normal authority check, in `DefaultAccessControlService`. A role can only
  ever **restrict** what the authority already allows, never extend it.
- Collection endpoints that read entity data without a per-entity check are covered by a read gate keyed
  on the URL pattern (115 endpoints; it logs `RBAC read gate active on 115 endpoints` at boot, and logs an
  error naming any pattern that no longer resolves, so an upstream rename cannot silently open a hole).
- `/api/entitiesQuery/*` and the WS v2 commands are gated on the entity type the query resolves to.
- The UI hides menu entries, Add/Delete buttons and details-panel actions the role denies. That is
  **cosmetic** — the server is the enforcer. A few entity-specific buttons (Unassign from customer, Manage
  credentials) are still drawn; the server refuses them. The **Users** page is fully covered: a customer
  user never sees Disable/Enable Account or Resend activation (tenant-only endpoints), and sees Display
  activation link only with the customer-administrator grant.

### Operational notes

- **Permissions are never in the JWT.** They are cached per user and re-read each request, so a role edit
  applies on the **next request** — no logout needed. The menu follows on the next page load.
- **Cache eviction is cluster-wide.** A role change broadcasts one message to every tb-core node carrying
  the affected user ids, so a revocation cannot linger on another node. This matters because
  `cache.type` defaults to node-local caffeine.
- **The `role` and `user_role` tables must exist.** They ship in the Inferrix schema overlay
  (`dao/src/main/resources/sql/schema-inferrix.sql`). If the jar is swapped without applying it, the
  platform logs `RBAC tables are missing - roles are not enforced until the database upgrade is run.` once
  and falls back to legacy access for every customer user rather than failing their requests — a
  deliberate fail-open, chosen so a missed migration cannot lock a whole tenant out. Apply the overlay.

## License Control

Offline, install-wide licence enforcement. A signed key caps the number of devices and assets a
deployment may create and expires on a schedule; there is no per-tenant licensing and no phone-home.

### The key

Inferrix issues each deployment an Ed25519-signed licence key: a customer name, an expiry date, and a
device/asset cap, bound to that one deployment's instance UUID (see below) so a key cannot be copied to a
second install. The key is a single string.

It goes in `thingsboard.conf` as an environment variable:

```
export INFERRIX_LICENSE_KEY="..."
```

`thingsboard.yml` reads it via `license.key: "${INFERRIX_LICENSE_KEY:}"`. Four more tunables live in the
same `license:` block: `license.enforcement.enabled` (the on/off switch — must stay `true`, i.e. absent
or explicit `true`, in every real deployment; see `INFERRIX-PATCHES.md`'s License Control section for
why a `false` default would be a critical misconfiguration), `license.check_interval_ms` (how often the
key is re-verified at runtime), `license.clock_tolerance_ms` (how far the system clock is allowed to move
backwards before that counts as tampering), and `license.max_high_water_advance_ms` (how far one licence
check may advance the clock-rollback high-water mark; see "Recovering from exit code 18" below).

### First boot

On its first boot, the platform generates a random instance UUID and stores it in a single-row table
(`inferrix_license_state`) — this is the deployment's permanent identity for licensing purposes.

If `INFERRIX_LICENSE_KEY` is not set, the platform logs that instance UUID and exits (see exit codes
below). The operator sends that UUID to Inferrix, receives a key issued specifically for it, sets
`INFERRIX_LICENSE_KEY`, and restarts. From then on the platform re-verifies the key at boot and on a
timer (`license.check_interval_ms`, default 1 hour) for as long as the process runs.

### What each role sees

`GET /api/license/info` backs a card on the `/home` fallback grid, visible to SYS_ADMIN and TENANT_ADMIN
alike. Both roles see the capacity numbers — devices and assets used against the cap, plus days remaining
— because that is the point of the card: a tenant admin needs to see capacity pressure coming. The
licensing customer name and the instance ID are SYS_ADMIN-only: the API omits both fields (rather than
blanking them client-side) for every other caller, so a TENANT_ADMIN never receives them. On a shared
multi-tenant install those two values identify the paying customer and the deployment itself to every
tenant on it, which would be a cross-tenant information leak.

### Exit codes

A licence problem is treated as fatal: the platform logs the reason and terminates. Each failure mode
has its own exit code so the cause is visible without reading logs (e.g. from a process supervisor or an
orchestrator's restart history):

| Code | Meaning |
|------|---------|
| 13 | No licence key is configured |
| 14 | The licence key is malformed (not a valid key at all) |
| 15 | The licence key's signature does not verify (payload was altered, or it's not a genuine Inferrix key) |
| 16 | The key was issued for a different deployment's instance UUID |
| 17 | The licence has expired |
| 18 | The system clock moved backwards past the point the platform last recorded — beyond the configured tolerance, which is treated as an attempt to defeat the expiry check |

Reaching a device/asset cap does **not** exit the process — it only blocks further creates (existing
devices/assets keep operating normally); only the six conditions above terminate the platform.

The systemd unit template sets `RestartPreventExitStatus=13 14 15 16 17 18` so a licence exit stops the
service instead of restarting it. Docker Compose's `restart:` directive has no equivalent exit-code
predicate, so under `restart: always` a licence exit **will** loop the container every restart delay
instead of stopping. If a container is exiting on a licence code, stop it (`docker compose stop`) rather
than waiting on it, and fix the licence before starting it again.

#### Recovering from exit code 18

Exit 18 does not always mean *this* node's clock is wrong. `checkClock` compares the current clock
against `high_water_ts`, a mark in `inferrix_license_state` that only ever moves forward. The usual cause
is a **different** node whose clock once ran ahead of real time: that node still passed `checkExpiry`
(its fast clock hadn't reached the licence's expiry yet) and `checkClock` (nothing had recorded a higher
mark yet), so it persisted its own future timestamp as the new high-water mark. Because the mark cannot
move backward, every node afterwards — including the offending one, once its clock is corrected — now
reads a real "now" behind that stale future mark, and fails `now < highWater - clock_tolerance_ms` for as
long as the gap lasts.

**Confirm it before touching anything.** The exit-18 log line prints both values it compared:

```
System clock moved backwards past the recorded high-water mark: clock reads <now> but the recorded high-water mark is <highWater>
```

Convert both to readable times and check them against a clock you trust (NTP, another host). If
`highWater` is the one in the future, this is the scenario above, not a live rollback attempt — proceed
to the recovery below. If instead `now` is the one that looks wrong, the check is doing its job: fix
*this* node's clock and let it re-verify normally. Do not run the recovery in that case — it would
recreate the same stuck mark for whoever checks in next.

**Blast radius:** `license.max_high_water_advance_ms` (default 24h) caps how far one check can push the
mark ahead, not how many checks can push it. A **transient** bad reading (a one-off NTP jump that
self-corrects, or a node that stops checking in) costs at most one clamp period of lockout, then the
install heals itself. A **persistent** skew (dead RTC battery, wrong timezone) instead walks the mark
forward by one clamp period per check until it reaches that node's own reading, then tracks it exactly —
the lockout then lasts as long as that node keeps running and checking in, not just one clamp period. The
real fix there is correcting or stopping the offending node's clock; the recovery below buys time, but a
still-running bad node will push the mark straight back up, so it may need repeating until the clock is
actually fixed. A WARN in the log (`DefaultLicenseService`, "high-water mark clamped") fires every time a
check gets clamped, so this is visible well before the lockout lands rather than only once it's too late.

**Recovery**, only once the clocks involved are confirmed good:

```sql
-- Only after confirming no clock is actually wound back. Sets the mark to the current true time.
UPDATE inferrix_license_state SET high_water_ts = <current epoch millis> WHERE singleton = TRUE;
```

This is a deliberate override of an anti-tamper control, applied directly against the database, bypassing
the platform entirely. Run it only once the clocks are known good — used on a node whose clock is
genuinely wound back, it is exactly the tampering this mechanism exists to catch, defeated silently by an
operator's own hand.

### No internet access required

Verification is entirely offline: the public key used to verify signatures is compiled into the
platform, and the only persisted state is the single `inferrix_license_state` row. The platform never
calls out to Inferrix or anywhere else to check a licence.

### Install and upgrade are unlicensed

The installer and the upgrade tool run under Spring's `install` profile, which selects a separate
no-op licence bean that never checks a key and never exits. Licence enforcement only activates once the
platform starts in its normal (non-install) profile. This means a fresh install or a version upgrade
always completes regardless of whether a key is configured yet — the key is only needed to start serving
traffic afterward.

### Issuing keys

Keys are produced by a separate tool, not by this repository: `/Users/maverick/Office/Product/inferrix-license-keygen/`.

The generator's private signing key lives **only in the password manager** — it is not checked into
either repository. Both repositories share a test-only "golden vector" fixture (a throwaway keypair used
purely to pin the key format in tests); that fixture's private key is likewise confined to the generator
repo's copy and is deliberately absent from this repo's copy.
