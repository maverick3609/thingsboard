# White Labeling — Design Specification

**Date:** 2026-04-13  
**Status:** Approved  
**Scope:** Core White Labeling (visual branding) — no Custom Menu, Custom Translation, or Mail Templates  
**Approach:** PE-identical composite key table, 3-tier merge (System → Tenant → Customer), always enabled (no license gating)

---

## 1. Overview

Implement white-labeling in the ThingsBoard open-source edition so that SYS_ADMIN, TENANT_ADMIN, and CUSTOMER_USER can customize platform branding (logo, app title, favicon, color palette, custom CSS, login page styling, help links) without any license or subscription requirement.

The implementation mirrors the ThingsBoard PE architecture: same REST API paths, same data models, same 3-tier merge semantics — but with subscription/license checks removed so the feature is always available.

### What's Included
- Logo, logo height, app title, favicon
- Primary and accent color palettes (Angular Material theme override)
- Custom CSS injection
- Help link base URL and toggle
- Platform name/version display toggle
- Login page branding (background color, dark foreground, show name at bottom)
- 3-tier hierarchy: System → Tenant → Customer with merge/inheritance
- Preview (merge without save)
- Frontend settings UI with inherited value indicators
- Runtime branding application (dynamic logo, title, favicon, palette, CSS)

### What's Excluded (future phases)
- Mail template customization
- Custom menu configuration
- Custom translation overrides
- Domain-based login routing
- Self-registration, Terms of Use, Privacy Policy pages

---

## 2. Data Models

All in `common/data/src/main/java/org/thingsboard/server/common/data/wl/`

### WhiteLabelingParams.java

Core DTO for general branding settings. Uses Lombok `@Data`.

| Field | Type | Description |
|-------|------|-------------|
| `logoImageUrl` | `String` | Base64 data URL or image path |
| `logoImageHeight` | `Integer` | Logo height in pixels |
| `appTitle` | `String` | Browser tab title |
| `favicon` | `Favicon` | Favicon configuration |
| `paletteSettings` | `PaletteSettings` | Primary + accent color palettes |
| `helpLinkBaseUrl` | `String` | Base URL for help links |
| `uiHelpBaseUrl` | `String` | Base URL for in-app help |
| `enableHelpLinks` | `Boolean` | Show/hide help links |
| `whiteLabelingEnabled` | `boolean` | Flag returned to frontend (always `true`) |
| `showNameVersion` | `Boolean` | Show platform name/version in UI |
| `platformName` | `String` | Custom platform name |
| `platformVersion` | `String` | Custom platform version |
| `customCss` | `String` | Arbitrary CSS injected into the app |
| `hideConnectivityDialog` | `Boolean` | Hide the connectivity wizard |

**Key method:** `merge(WhiteLabelingParams parent)` — for each field, if `this.field` is null, copy from `parent.field`. This implements inheritance.

### LoginWhiteLabelingParams.java (extends WhiteLabelingParams)

| Field | Type | Description |
|-------|------|-------------|
| `pageBackgroundColor` | `String` | Login page background color |
| `darkForeground` | `boolean` | Use dark text on light backgrounds |
| `showNameBottom` | `Boolean` | Show platform name at bottom of login |
| `domainId` | `DomainId` | For PE compatibility (not actively used) |
| `baseUrl` | `String` | For PE compatibility (not actively used) |

**Key method:** `merge(LoginWhiteLabelingParams parent)` — calls `super.merge(parent)` then merges login-specific fields.

### WhiteLabeling.java

The persistence/transport entity. Uses Lombok `@Data`, `@Builder`.

| Field | Type | Description |
|-------|------|-------------|
| `tenantId` | `TenantId` | Owning tenant (NULL_UUID for system) |
| `customerId` | `CustomerId` | Owning customer (NULL_UUID for tenant/system) |
| `type` | `WhiteLabelingType` | GENERAL or LOGIN |
| `settings` | `JsonNode` (transient) | Deserialized from settingsBytes |
| `settingsBytes` | `byte[]` | Raw JSON bytes |
| `domainId` | `DomainId` | For PE compatibility |

### WhiteLabelingType.java (enum)

```java
GENERAL, LOGIN
```

### PaletteSettings.java

| Field | Type |
|-------|------|
| `primaryPalette` | `Palette` |
| `accentPalette` | `Palette` |

Has `merge(PaletteSettings parent)`.

### Palette.java

| Field | Type | Description |
|-------|------|-------------|
| `type` | `String` | e.g., "custom" |
| `extendsPalette` | `String` | Material palette to extend (e.g., "indigo") |
| `colors` | `Map<String, String>` | Shade overrides (e.g., `{"500": "#305680"}`) |

### Favicon.java

| Field | Type |
|-------|------|
| `url` | `String` |

---

## 3. Database Schema

### New Table

```sql
CREATE TABLE IF NOT EXISTS white_labeling (
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    type varchar(32) NOT NULL,
    settings varchar,
    domain_id uuid,
    CONSTRAINT white_labeling_pkey PRIMARY KEY (tenant_id, customer_id, type)
);
```

**Key conventions:**
- System-level: `tenant_id = NULL_UUID`, `customer_id = NULL_UUID`
- Tenant-level: `tenant_id = <real>`, `customer_id = NULL_UUID`
- Customer-level: `tenant_id = <real>`, `customer_id = <real>`
- `settings` column stores serialized JSON (WhiteLabelingParams or LoginWhiteLabelingParams)
- `domain_id` included for PE compatibility, nullable, not actively used

### Migration

Add to upgrade SQL scripts for the current version. The table creation is idempotent (`CREATE TABLE IF NOT EXISTS`).

---

## 4. JPA Entity & Composite Key

### WhiteLabelingCompositeKey.java

In `dao/src/main/java/org/thingsboard/server/dao/model/sql/`

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class WhiteLabelingCompositeKey implements Serializable {
    UUID tenantId;
    UUID customerId;
    WhiteLabelingType type;
}
```

Static factories:
- `forSystem(WhiteLabelingType type)` — NULL_UUID, NULL_UUID
- `forTenant(TenantId tenantId, WhiteLabelingType type)` — tenantId, NULL_UUID
- `forCustomer(TenantId tenantId, CustomerId customerId, WhiteLabelingType type)` — tenantId, customerId

### WhiteLabelingEntity.java

```java
@Data @Entity @Table(name = "white_labeling")
@IdClass(WhiteLabelingCompositeKey.class)
public class WhiteLabelingEntity {
    @Id @Column(name = "tenant_id") UUID tenantId;
    @Id @Column(name = "customer_id") UUID customerId;
    @Id @Column(name = "type") @Enumerated(EnumType.STRING) WhiteLabelingType type;
    @Convert(converter = JsonConverter.class) @Column(name = "settings") JsonNode settings;
    @Column(name = "domain_id") UUID domainId;

    public WhiteLabelingEntity(WhiteLabeling wl) { /* map from domain */ }
    public WhiteLabeling toData() { /* map to domain */ }
}
```

### ModelConstants additions

```java
public static final String WHITE_LABELING_TABLE_NAME = "white_labeling";
```

---

## 5. DAO Layer

### WhiteLabelingDao.java (interface)

In `common/dao-api/src/main/java/org/thingsboard/server/dao/wl/`

```java
public interface WhiteLabelingDao extends TenantEntityDao<WhiteLabeling> {
    WhiteLabeling save(WhiteLabeling whiteLabeling);
    WhiteLabeling findByCompositeKey(WhiteLabelingCompositeKey key);
    boolean removeByCompositeKey(WhiteLabelingCompositeKey key);
    void deleteByTenantId(TenantId tenantId);
}
```

Does NOT extend `Dao<T>` (which assumes UUID keys). Extends `TenantEntityDao<WhiteLabeling>` matching PE.

### WhiteLabelingRepository.java

In `dao/src/main/java/org/thingsboard/server/dao/sql/wl/`

```java
public interface WhiteLabelingRepository
    extends JpaRepository<WhiteLabelingEntity, WhiteLabelingCompositeKey> {
    List<WhiteLabelingEntity> findByTenantId(UUID tenantId);
    void deleteByTenantId(UUID tenantId);
}
```

### JpaWhiteLabelingDao.java

```java
@Component @SqlDao @RequiredArgsConstructor
public class JpaWhiteLabelingDao extends JpaAbstractDaoListeningExecutorService
    implements WhiteLabelingDao {
    private final WhiteLabelingRepository repository;
    // Implements all methods, converting between entity and domain
}
```

Extends `JpaAbstractDaoListeningExecutorService` (not `JpaAbstractDao`), matching PE.

---

## 6. Service Layer

### WhiteLabelingService.java (interface)

In `common/dao-api/src/main/java/org/thingsboard/server/dao/wl/`

**Get merged (runtime consumption):**
- `getMergedTenantWhiteLabelingParams(TenantId)` → `WhiteLabelingParams`
- `getMergedCustomerWhiteLabelingParams(TenantId, CustomerId)` → `WhiteLabelingParams`
- `getMergedLoginWhiteLabelingParams(String serverName)` → `LoginWhiteLabelingParams`

**Get raw (editing UI):**
- `getSystemWhiteLabelingParams()` → `WhiteLabelingParams`
- `getTenantWhiteLabelingParams(TenantId)` → `WhiteLabelingParams`
- `getCustomerWhiteLabelingParams(TenantId, CustomerId)` → `WhiteLabelingParams`
- `getSystemLoginWhiteLabelingParams()` → `LoginWhiteLabelingParams`
- `getTenantLoginWhiteLabelingParams(TenantId)` → `LoginWhiteLabelingParams`
- `getCustomerLoginWhiteLabelingParams(TenantId, CustomerId)` → `LoginWhiteLabelingParams`

**Save:**
- `saveSystemWhiteLabelingParams(WhiteLabelingParams)` → `WhiteLabelingParams`
- `saveTenantWhiteLabelingParams(TenantId, WhiteLabelingParams)` → `WhiteLabelingParams`
- `saveCustomerWhiteLabelingParams(TenantId, CustomerId, WhiteLabelingParams)` → `WhiteLabelingParams`
- `saveSystemLoginWhiteLabelingParams(LoginWhiteLabelingParams)` → `LoginWhiteLabelingParams`
- `saveTenantLoginWhiteLabelingParams(TenantId, LoginWhiteLabelingParams)` → `LoginWhiteLabelingParams`
- `saveCustomerLoginWhiteLabelingParams(TenantId, CustomerId, LoginWhiteLabelingParams)` → `LoginWhiteLabelingParams`

**Preview (merge without save):**
- `mergeSystemWhiteLabelingParams(WhiteLabelingParams)` → `WhiteLabelingParams`
- `mergeTenantWhiteLabelingParams(TenantId, WhiteLabelingParams)` → `WhiteLabelingParams`
- `mergeCustomerWhiteLabelingParams(TenantId, CustomerId, WhiteLabelingParams)` → `WhiteLabelingParams`

**Delete:**
- `deleteWhiteLabeling(TenantId, CustomerId, WhiteLabelingType)`
- `deleteAllTenantWhiteLabeling(TenantId)`

**Permission helpers (always true):**
- `isWhiteLabelingAllowed(TenantId, CustomerId)` → `boolean`
- `isCustomerWhiteLabelingAllowed(TenantId)` → `boolean`

### DefaultWhiteLabelingService.java (implementation)

In `dao/src/main/java/org/thingsboard/server/dao/wl/`

```java
@Service @Slf4j @RequiredArgsConstructor
public class DefaultWhiteLabelingService implements WhiteLabelingService {
    private final WhiteLabelingDao whiteLabelingDao;
    private final CacheManager cacheManager;
}
```

**Merge algorithm:**

```
getMergedCustomerWhiteLabelingParams(tenantId, customerId):
  1. system = getSystemWhiteLabelingParams()          // defaults if none
  2. tenant = getTenantWhiteLabelingParams(tenantId)   // may be null
  3. customer = getCustomerWhiteLabelingParams(tenantId, customerId) // may be null
  4. result = new WhiteLabelingParams(system)           // copy system defaults
  5. if tenant != null: tenant.merge(result) → result = tenant with system fallbacks
  6. if customer != null: customer.merge(result) → result = customer with tenant/system fallbacks
  7. return result
```

The `merge()` method on `WhiteLabelingParams` fills null fields from the parent argument. Child values always win; parent fills gaps.

**System defaults** (when no system-level config exists):
```java
helpLinkBaseUrl = "https://thingsboard.io"
enableHelpLinks = true
whiteLabelingEnabled = true
```

**Serialization:**
- Save: `WhiteLabelingParams` → `ObjectMapper.writeValueAsBytes()` → `WhiteLabeling.settingsBytes` → DAO
- Load: DAO → `WhiteLabeling.settingsBytes` → `ObjectMapper.readValue()` → `WhiteLabelingParams`

### Caching

Cache name: `"whiteLabelingCache"` registered in `CacheSpecsMap`.

Cache key: `WhiteLabelingCacheKey` with fields `(tenantId, customerId, type)`.

- `@Cacheable` on all `get*` methods
- `@CacheEvict` on all `save*` and `delete*` methods
- When tenant config changes: also evict merged customer caches for that tenant (best-effort; customers re-fetch on next request)

---

## 7. REST Controller

### WhiteLabelingController.java

In `application/src/main/java/org/thingsboard/server/controller/`

```java
@RestController @TbCoreComponent @RequestMapping("/api") @Slf4j
public class WhiteLabelingController extends BaseController
```

### Endpoints

| # | Method | Path | Auth | Description |
|---|--------|------|------|-------------|
| 1 | GET | `/api/whiteLabel/whiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Merged WL params for current user |
| 2 | GET | `/api/noauth/whiteLabel/loginWhiteLabelParams` | None | Merged login WL params by hostname |
| 3 | GET | `/api/whiteLabel/currentWhiteLabelParams?customerId=` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Raw WL params for editing |
| 4 | GET | `/api/whiteLabel/currentLoginWhiteLabelParams?customerId=` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Raw login WL params for editing |
| 5 | POST | `/api/whiteLabel/whiteLabelParams?customerId=` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Save WL params |
| 6 | POST | `/api/whiteLabel/loginWhiteLabelParams?customerId=` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Save login WL params |
| 7 | POST | `/api/whiteLabel/previewWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Preview merged result |
| 8 | GET | `/api/whiteLabel/isWhiteLabelingAllowed` | TENANT_ADMIN, CUSTOMER_USER | Always returns `true` |
| 9 | GET | `/api/whiteLabel/isCustomerWhiteLabelingAllowed` | TENANT_ADMIN | Always returns `true` |
| 10 | DELETE | `/api/whiteLabel/currentWhiteLabelParams?customerId=` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Delete WL config |
| 11 | DELETE | `/api/whiteLabel/currentLoginWhiteLabelParams?customerId=` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | Delete login WL config |

### Routing Logic

Endpoints 1, 3-7, 10-11 branch by authority:

- **SYS_ADMIN** → system-level operations
- **TENANT_ADMIN** without `customerId` param → tenant-level operations
- **TENANT_ADMIN** with `customerId` param → customer-level operations (after validating the customer belongs to tenant)
- **CUSTOMER_USER** → customer-level operations (using their own customerId)

### No Subscription Gating

PE uses `subscriptionService.whiteLabelingEnabled()` / `whiteLabelingAllowed()` to gate access. We skip these calls entirely. The feature is always enabled. `isWhiteLabelingAllowed` endpoints return `true`.

---

## 8. Permission System Integration

### EntityType.java addition

```java
WHITE_LABELING(200)  // high number to avoid conflicts with future CE/PE additions
```

### Resource.java addition

```java
WHITE_LABELING(EntityType.WHITE_LABELING)
```

### Default permission maps

All three roles (SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER) get READ + WRITE on `Resource.WHITE_LABELING`.

### Controller permission check

```java
private void checkWhiteLabelingPermissions(Operation operation) {
    accessControlService.checkPermission(getCurrentUser(), Resource.WHITE_LABELING, operation);
}
```

Called at the start of each authenticated endpoint.

---

## 9. Frontend — Services & Runtime Branding

### TypeScript Models

`ui-ngx/src/app/shared/models/white-labeling.models.ts`

Interfaces mirroring the Java DTOs: `Favicon`, `Palette`, `PaletteSettings`, `WhiteLabelingParams`, `LoginWhiteLabelingParams`.

### API Service

`ui-ngx/src/app/core/http/white-labeling.service.ts`

`@Injectable({providedIn: 'root'})` wrapping all 11 REST endpoints.

### Runtime Branding Service

`ui-ngx/src/app/core/services/white-labeling-runtime.service.ts`

Applies WL params to the running application:

| Concern | Mechanism |
|---------|-----------|
| Logo | Observable `logo$` subscribed by sidebar, dashboard, logo component |
| App title | Overrides `TitleService` to use WL `appTitle` |
| Favicon | Dynamic `<link rel="icon">` replacement in document head |
| Color palette | Generates CSS custom properties from `paletteSettings`, injected via `<style>` tag |
| Custom CSS | Injected via `<style id="wl-custom-css">` in document head |
| Help links | Exposes `helpLinkBaseUrl$` and `enableHelpLinks$` observables |
| Platform name/version | Exposes observables for footer display |

### Initialization Flow

```
App bootstrap
  → Login page: GET /api/noauth/whiteLabel/loginWhiteLabelParams
  → Apply login branding (logo, background, colors)
  → User authenticates
  → Post-login: GET /api/whiteLabel/whiteLabelParams
  → Apply full branding (logo, title, favicon, palette, CSS, help links)
```

### Component Integration Points

| Component | Current (hardcoded) | After WL |
|-----------|-------------------|----------|
| `home.component.ts` | `logo = 'assets/logo_title_white.svg'` | Subscribe to `whiteLabelingRuntime.logo$` |
| `login.component.html` | `<tb-logo link="https://thingsboard.io">` | Bind to login WL params |
| `logo.component.ts` | Default `src = 'assets/...'` | Accept runtime override |
| `title.service.ts` | `environment.appTitle` | Read from WL service, fallback to env |
| `dashboard-page.component.ts` | `defaultDashboardLogo = 'assets/...'` | Subscribe to `whiteLabelingRuntime.logo$` |
| `footer.component.html` | Hardcoded copyright | Show `platformName` if `showNameVersion` |
| `constants.ts` | `helpBaseUrl = 'https://thingsboard.io'` | Read from WL service |
| `index.html` | `<link rel="icon" href="thingsboard.ico">` | Dynamic replacement |
| `theme.scss` | Static SCSS palette | Runtime CSS custom properties override |

---

## 10. Frontend — Settings UI

### Route & Menu

- Path: `/settings/whiteLabeling`
- Menu entry for SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER under Home section
- Icon: `format_paint`
- Lazy-loaded module

### Module Structure

```
ui-ngx/src/app/modules/home/pages/white-labeling/
  ├── white-labeling.module.ts
  ├── white-labeling-routing.module.ts
  ├── white-labeling.component.ts/html/scss      — tab container
  ├── general-wl-settings.component.ts/html       — General tab
  ├── login-wl-settings.component.ts/html          — Login Page tab
  ├── palette-settings.component.ts/html           — palette picker (reused)
  └── image-input.component.ts/html                — logo/favicon upload
```

### Two Tabs

**General tab:** logo, logo height, app title, favicon, primary palette, accent palette, custom CSS, help link base URL, enable help links, show name/version, platform name, platform version, hide connectivity dialog.

**Login Page tab:** inherits General fields (read-only) + page background color, dark foreground toggle, show name at bottom.

### Scope Selector

- SYS_ADMIN: "System" (default)
- TENANT_ADMIN: "Tenant" (default) / customer dropdown
- CUSTOMER_USER: their own scope (no selector)

### Inherited Value Indicators

Fields that are null (inheriting from parent) show:
- Grayed-out placeholder with the inherited value
- Label: "Inherited from System" or "Inherited from Tenant"
- On edit: field becomes "overridden" with a "Reset to inherited" button

### Buttons

- **Preview** — calls preview endpoint, shows merged result
- **Save** — saves current form state
- **Delete** — removes config at current level (reverts to inherited)

---

## 11. File Inventory

### Backend — New Files

| Module | Path | Description |
|--------|------|-------------|
| `common/data` | `src/.../common/data/wl/WhiteLabelingParams.java` | General WL DTO |
| `common/data` | `src/.../common/data/wl/LoginWhiteLabelingParams.java` | Login WL DTO |
| `common/data` | `src/.../common/data/wl/WhiteLabeling.java` | Persistence entity |
| `common/data` | `src/.../common/data/wl/WhiteLabelingType.java` | Enum: GENERAL, LOGIN |
| `common/data` | `src/.../common/data/wl/PaletteSettings.java` | Palette pair |
| `common/data` | `src/.../common/data/wl/Palette.java` | Single palette |
| `common/data` | `src/.../common/data/wl/Favicon.java` | Favicon URL wrapper |
| `common/dao-api` | `src/.../dao/wl/WhiteLabelingDao.java` | DAO interface |
| `common/dao-api` | `src/.../dao/wl/WhiteLabelingService.java` | Service interface |
| `dao` | `src/.../dao/model/sql/WhiteLabelingEntity.java` | JPA entity |
| `dao` | `src/.../dao/model/sql/WhiteLabelingCompositeKey.java` | JPA composite key |
| `dao` | `src/.../dao/sql/wl/WhiteLabelingRepository.java` | Spring Data repo |
| `dao` | `src/.../dao/sql/wl/JpaWhiteLabelingDao.java` | DAO implementation |
| `dao` | `src/.../dao/wl/DefaultWhiteLabelingService.java` | Service implementation |
| `application` | `src/.../controller/WhiteLabelingController.java` | REST controller |

### Backend — Modified Files

| File | Change |
|------|--------|
| `EntityType.java` | Add `WHITE_LABELING(200)` |
| `Resource.java` | Add `WHITE_LABELING(EntityType.WHITE_LABELING)` |
| `ModelConstants.java` | Add table name constant |
| `schema-entities.sql` | Add `white_labeling` table |
| Upgrade SQL script | Add `CREATE TABLE IF NOT EXISTS white_labeling` |
| Default permission maps | Add WHITE_LABELING READ/WRITE for all 3 roles |
| `CacheSpecsMap` or cache config | Add `whiteLabelingCache` entry |

### Frontend — New Files

| Path | Description |
|------|-------------|
| `shared/models/white-labeling.models.ts` | TypeScript interfaces |
| `core/http/white-labeling.service.ts` | API service |
| `core/services/white-labeling-runtime.service.ts` | Runtime branding service |
| `modules/home/pages/white-labeling/white-labeling.module.ts` | Feature module |
| `modules/home/pages/white-labeling/white-labeling-routing.module.ts` | Routes |
| `modules/home/pages/white-labeling/white-labeling.component.ts/html/scss` | Main page |
| `modules/home/pages/white-labeling/general-wl-settings.component.ts/html` | General tab |
| `modules/home/pages/white-labeling/login-wl-settings.component.ts/html` | Login tab |
| `modules/home/pages/white-labeling/palette-settings.component.ts/html` | Palette picker |
| `modules/home/pages/white-labeling/image-input.component.ts/html` | Image upload |

### Frontend — Modified Files

| File | Change |
|------|--------|
| `menu.models.ts` | Add White Labeling menu entry for all 3 roles |
| `home-routing.module.ts` | Add lazy route to WL module |
| `home.component.ts` | Subscribe to WL logo observable |
| `login.component.ts/html` | Apply login WL params |
| `logo.component.ts` | Accept WL runtime override |
| `title.service.ts` | Read appTitle from WL service |
| `dashboard-page.component.ts` | Subscribe to WL logo |
| `footer.component.html` | Show platform name if configured |
| `constants.ts` | Read helpBaseUrl from WL service |
