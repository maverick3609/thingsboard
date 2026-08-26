# White-Labeling PE Architecture Analysis

> Reverse-engineered from `docs/jars/thingsboard.jar` (PE v4.2.0)
> Source jars: `data-4.2.0PE.jar`, `dao-4.2.0PE.jar`, `edge-api-4.2.0PE.jar`

---

## Table of Contents

1. [Complete File Inventory](#1-complete-file-inventory)
2. [Data Models (common/data)](#2-data-models)
3. [Persistence Layer (dao)](#3-persistence-layer)
4. [Service Layer (dao)](#4-service-layer)
5. [Controller Layer (application)](#5-controller-layer)
6. [Permission System](#6-permission-system)
7. [Edge Sync](#7-edge-sync)
8. [Cross-Cutting References](#8-cross-cutting-references)
9. [Database Schema](#9-database-schema)
10. [Cache Configuration](#10-cache-configuration)
11. [Key Architectural Decisions](#11-key-architectural-decisions)
12. [Differences: Our Implementation vs PE](#12-differences-our-implementation-vs-pe)

---

## 1. Complete File Inventory

### 1.1 Files CREATED for White Labeling

| # | Module | Package/Path | Class | Type |
|---|--------|-------------|-------|------|
| 1 | `data` | `common.data.wl` | `WhiteLabelingType` | enum (6 values) |
| 2 | `data` | `common.data.wl` | `Favicon` | data model |
| 3 | `data` | `common.data.wl` | `Palette` | data model |
| 4 | `data` | `common.data.wl` | `PaletteSettings` | data model |
| 5 | `data` | `common.data.wl` | `WhiteLabelingParams` | DTO (base) |
| 6 | `data` | `common.data.wl` | `LoginWhiteLabelingParams` | DTO (extends #5) |
| 7 | `data` | `common.data.wl` | `WhiteLabeling` | persistence entity |
| 8 | `dao` | `dao.wl` | `WhiteLabelingService` | interface (36 methods) |
| 9 | `dao` | `dao.wl` | `BaseWhiteLabelingService` | @Service implementation |
| 10 | `dao` | `dao.wl` | `WhiteLabelingDao` | interface (extends TenantEntityDao) |
| 11 | `dao` | `dao.wl` | `WhiteLabelingCacheKey` | cache key class |
| 12 | `dao` | `dao.wl` | `WhiteLabelingEvictEvent` | cache eviction event |
| 13 | `dao` | `dao.wl` | `WhiteLabelingCaffeineCache` | Caffeine cache impl |
| 14 | `dao` | `dao.wl` | `WhiteLabelingRedisCache` | Redis cache impl |
| 15 | `dao` | `dao.model.sql` | `WhiteLabelingCompositeKey` | JPA @IdClass |
| 16 | `dao` | `dao.model.sql` | `WhiteLabelingEntity` | JPA @Entity |
| 17 | `dao` | `dao.sql.whitelabeling` | `JpaWhiteLabelingDao` | @Component JPA DAO |
| 18 | `dao` | `dao.sql.whitelabeling` | `WhiteLabelingRepository` | JpaRepository |
| 19 | `application` | `controller` | `WhiteLabelingController` | @RestController |
| 20 | `application` | `service.edge.rpc.processor.wl` | `WhiteLabelingEdgeProcessor` | edge processor |
| 21 | `application` | `service.edge.rpc.fetch` | `WhiteLabelingEdgeEventFetcher` | edge event fetcher |
| 22 | `edge-api` | `gen.edge.v1` | `WhiteLabelingProto` | protobuf message |
| 23 | `edge-api` | `gen.edge.v1` | `WhiteLabelingProtoOrBuilder` | protobuf interface |

**Total: 23 dedicated files**

### 1.2 Files MODIFIED for White Labeling

| # | Module | Class | What Changed |
|---|--------|-------|-------------|
| 1 | `data` | `CacheConstants` | Added `WHITE_LABELING_CACHE = "whiteLabeling"` |
| 2 | `data` | `Resource` (enum) | Added `WHITE_LABELING` constant (in `common.data.permission`) |
| 3 | `dao` | `schema-entities.sql` | Added `white_labeling` table DDL |
| 4 | `application` | `BaseController` | Added `whiteLabelingService` field |
| 5 | `application` | `thingsboard.yml` | Added `whiteLabeling` cache spec |
| 6 | `application` | `TenantAdminPermissions` | Added `whiteLabelingService` field + `tenantWhiteLabelingPermissionChecker` |
| 7 | `application` | `CustomerUserPermissions` | Added `whiteLabelingService` field + `customerWhiteLabelingPermissionChecker` |
| 8 | `application` | `ImageController` | Injected `whiteLabelingService`, added `downloadLoginLogo/Favicon` endpoints |
| 9 | `application` | `DashboardController` | Injected `whiteLabelingService`, added `checkWhiteLabelingPermissions()` |
| 10 | `application` | `CustomMenuController` | Added `checkWhiteLabelingPermissions()` |
| 11 | `application` | `CustomTranslationController` | Added `checkWhiteLabelingPermissions()` |
| 12 | `application` | `TranslationController` | Injected `whiteLabelingService` |
| 13 | `application` | `QrCodeSettingsController` | Injected `whiteLabelingService` |
| 14 | `application` | `SignUpController` | Uses WL for self-registration lookup |
| 15 | `application` | `SystemInfoController` | Injected `whiteLabelingService` |
| 16 | `application` | `SelfRegistrationController` | Uses `whiteLabelingService` for self-reg, privacy, terms |
| 17 | `application` | `DefaultMailService` | Calls `getMergedTenantMailTemplates()` for every email |
| 18 | `application` | `DefaultSystemSecurityService` | Uses WL `LoginWhiteLabelingParams` for `getBaseUrl()` |
| 19 | `application` | `DefaultTranslationService` | Uses WL for login translation |
| 20 | `application` | `InstallScripts` | Saves WL mail templates at install time |
| 21 | `application` | `DefaultDataUpdateService` | Migrates WL mail templates |
| 22 | `application` | `ResourcesUpdater` | `updateWhiteLabelingImages()` via `whiteLabelingDao` |
| 23 | `application` | `DefaultSolutionService` | `allowedWhiteLabelingSolutionTemplateLevelsMap` |
| 24 | `application` | `EdgeContextComponent` | Added `whiteLabelingProcessor` field |
| 25 | `application` | `EdgeMsgConstructorUtils` | Added `constructWhiteLabeling()` static method |
| 26 | `application` | `EdgeSyncCursor` | Creates `WhiteLabelingEdgeEventFetcher` |

**Total: 26 modified files**

### 1.3 NOT Modified (Critical Negative Finding)

| Class | Note |
|-------|------|
| `EntityType` (enum) | **NO** `WHITE_LABELING` constant. WL is NOT an entity type |
| `EntityIdFactory` | Not touched -- no WL entity ID creation |
| `SysAdminPermissions` | No WL-specific checker (SYS_ADMIN uses generic `systemEntityPermissionChecker`) |

---

## 2. Data Models

### 2.1 WhiteLabelingType (enum)

**Package**: `org.thingsboard.server.common.data.wl`

```java
public enum WhiteLabelingType {
    LOGIN,           // Login page branding
    GENERAL,         // Main app branding
    MAIL_TEMPLATES,  // Email template customization
    SELF_REGISTRATION, // Self-registration settings
    TERMS_OF_USE,    // Terms of use content
    PRIVACY_POLICY   // Privacy policy content
}
```

> Our implementation has only 2: `GENERAL`, `LOGIN`. The other 4 are PE-specific features.

### 2.2 WhiteLabelingParams (base DTO)

```java
public class WhiteLabelingParams {
    protected String logoImageUrl;
    protected Integer logoImageHeight;
    protected String appTitle;
    protected Favicon favicon;
    protected PaletteSettings paletteSettings;
    protected String helpLinkBaseUrl;
    protected String uiHelpBaseUrl;
    protected Boolean enableHelpLinks;
    protected boolean whiteLabelingEnabled;     // default true
    protected Boolean showNameVersion;
    protected String platformName;
    protected String platformVersion;
    protected String customCss;
    protected Boolean hideConnectivityDialog;

    // Merge semantics: child overrides parent (null = inherit)
    // customCss is CONCATENATED (parent + child), not overridden
    public WhiteLabelingParams merge(WhiteLabelingParams parent);
}
```

### 2.3 LoginWhiteLabelingParams (extends WhiteLabelingParams)

```java
public class LoginWhiteLabelingParams extends WhiteLabelingParams {
    private String pageBackgroundColor;
    private boolean darkForeground;
    private DomainId domainId;
    private String baseUrl;
    private boolean prohibitDifferentUrl;   // PE only
    private String adminSettingsId;          // PE only
    private Boolean showNameBottom;

    public LoginWhiteLabelingParams merge(LoginWhiteLabelingParams parent);
}
```

> Our implementation is missing `prohibitDifferentUrl` and `adminSettingsId`.

### 2.4 WhiteLabeling (persistence entity)

```java
@Builder
public class WhiteLabeling implements Serializable {
    private TenantId tenantId;
    private CustomerId customerId;
    private WhiteLabelingType type;
    private transient JsonNode settings;  // transient -- serialized via settingsBytes
    private DomainId domainId;
    private byte[] settingsBytes;         // lazy serialization cache

    // getSettings() deserializes from settingsBytes on first call
    // setSettings() clears settingsBytes to force re-serialization
}
```

### 2.5 Favicon, Palette, PaletteSettings

```java
public class Favicon {
    private String url;
}

public class Palette {
    private String type;            // e.g. "mat-palette" or custom
    private String extendsPalette;  // parent palette name
    private Map<String, String> colors;  // color key -> hex value
}

public class PaletteSettings {
    private Palette primaryPalette;
    private Palette accentPalette;

    // Merge: if child palette type is empty, inherit parent palette
    public PaletteSettings merge(PaletteSettings parent);
}
```

---

## 3. Persistence Layer

### 3.1 WhiteLabelingCompositeKey

```java
@Data
public class WhiteLabelingCompositeKey implements Serializable {
    private UUID tenantId;
    private UUID customerId;
    private WhiteLabelingType type;

    // Constructors:
    WhiteLabelingCompositeKey(TenantId, WhiteLabelingType)
    // -> sets customerId = EntityId.NULL_UUID (system-level tenant key)

    WhiteLabelingCompositeKey(TenantId, CustomerId, WhiteLabelingType)
    // -> full composite key

    WhiteLabelingCompositeKey(UUID, UUID, WhiteLabelingType)
    // -> raw UUID constructor
}
```

### 3.2 WhiteLabelingEntity

```java
@Entity
@Table(name = "white_labeling")
@IdClass(WhiteLabelingCompositeKey.class)
@Data
public class WhiteLabelingEntity implements ToData<WhiteLabeling>, Serializable {
    @Id @Column(name = "tenant_id", columnDefinition = "uuid")
    private UUID tenantId;

    @Id @Column(name = "customer_id", columnDefinition = "uuid")
    private UUID customerId;

    @Id @Enumerated(EnumType.STRING) @Column(name = "type")
    private WhiteLabelingType type;

    @Convert(converter = JsonConverter.class) @Column(name = "settings")
    private JsonNode settings;

    @Column(name = "domain_id")
    private UUID domainId;

    public WhiteLabelingEntity(WhiteLabeling wl) { /* from domain */ }
    public WhiteLabeling toData() { /* to domain */ }
}
```

### 3.3 WhiteLabelingRepository

```java
public interface WhiteLabelingRepository extends
    JpaRepository<WhiteLabelingEntity, WhiteLabelingCompositeKey> {

    @Query("SELECT wl FROM WhiteLabelingEntity wl " +
           "LEFT JOIN DomainEntity d ON wl.domainId = d.id " +
           "WHERE d.name = :domain AND wl.type = :type")
    WhiteLabelingEntity findByDomainAndType(String domain, WhiteLabelingType type);

    @Query(nativeQuery = true,
           value = "SELECT * FROM white_labeling wl " +
                   "WHERE wl.tenant_id = :tenantId " +
                   "AND wl.settings ILIKE CONCAT('%\"', :imageLink, '\"%') " +
                   "LIMIT :lmt")
    List<WhiteLabelingEntity> findByTenantAndImageLink(UUID tenantId, String imageLink, int lmt);

    @Query(nativeQuery = true,
           value = "SELECT * FROM white_labeling wl " +
                   "WHERE wl.settings ILIKE CONCAT('%\"', :imageLink, '\"%') " +
                   "LIMIT :lmt")
    List<WhiteLabelingEntity> findByImageLink(String imageLink, int lmt);

    Page<WhiteLabelingEntity> findAllByTypeIn(Set<WhiteLabelingType> types, Pageable pageable);
    Page<WhiteLabelingEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
```

### 3.4 WhiteLabelingDao

```java
public interface WhiteLabelingDao extends TenantEntityDao<WhiteLabeling> {
    WhiteLabeling save(TenantId tenantId, WhiteLabeling wl);
    WhiteLabeling findById(TenantId tenantId, WhiteLabelingCompositeKey key);
    WhiteLabeling findByDomainAndType(TenantId tenantId, String domain, WhiteLabelingType type);
    void removeById(TenantId tenantId, WhiteLabelingCompositeKey key);
    List<WhiteLabeling> findByTenantAndImageLink(TenantId tenantId, String imageLink, int limit);
    List<WhiteLabeling> findByImageLink(String imageLink, int limit);
    PageData<WhiteLabeling> findAllByType(PageLink pageLink, Set<WhiteLabelingType> types);
}
```

### 3.5 JpaWhiteLabelingDao

```java
@Component
@SqlDao
public class JpaWhiteLabelingDao
    extends JpaAbstractDaoListeningExecutorService   // NOT JpaAbstractDao
    implements WhiteLabelingDao {

    @Autowired
    private WhiteLabelingRepository whiteLabelingRepository;

    // Implements all WhiteLabelingDao + TenantEntityDao methods
    // findAllByTenantId() delegates to repository.findByTenantId()
}
```

> **Key**: Extends `JpaAbstractDaoListeningExecutorService`, NOT `JpaAbstractDao<WhiteLabelingEntity>`.
> This is because WhiteLabeling uses a composite key, not a UUID primary key.

---

## 4. Service Layer

### 4.1 WhiteLabelingService (interface -- 36 methods)

```java
public interface WhiteLabelingService {
    // --- Raw getters (no merge) ---
    WhiteLabelingParams getSystemWhiteLabelingParams();
    LoginWhiteLabelingParams getSystemLoginWhiteLabelingParams();
    WhiteLabelingParams getTenantWhiteLabelingParams(TenantId);
    WhiteLabelingParams getCustomerWhiteLabelingParams(TenantId, CustomerId);
    LoginWhiteLabelingParams getTenantLoginWhiteLabelingParams(TenantId);
    LoginWhiteLabelingParams getCustomerLoginWhiteLabelingParams(TenantId, CustomerId);

    // --- Merged getters (3-tier cascade) ---
    WhiteLabelingParams getMergedTenantWhiteLabelingParams(TenantId);
    WhiteLabelingParams getMergedCustomerWhiteLabelingParams(TenantId, CustomerId);
    LoginWhiteLabelingParams getMergedLoginWhiteLabelingParams(String domainName);

    // --- Image key for login page caching ---
    ImageCacheKey getLoginImageKey(String domainName, boolean isFavicon);

    // --- Save ---
    WhiteLabelingParams saveSystemWhiteLabelingParams(WhiteLabelingParams);
    WhiteLabelingParams saveTenantWhiteLabelingParams(TenantId, WhiteLabelingParams);
    WhiteLabelingParams saveCustomerWhiteLabelingParams(TenantId, CustomerId, WhiteLabelingParams);
    LoginWhiteLabelingParams saveSystemLoginWhiteLabelingParams(LoginWhiteLabelingParams);
    LoginWhiteLabelingParams saveTenantLoginWhiteLabelingParams(TenantId, LoginWhiteLabelingParams);
    LoginWhiteLabelingParams saveCustomerLoginWhiteLabelingParams(TenantId, CustomerId, LoginWhiteLabelingParams);

    // --- Preview (merge without saving) ---
    WhiteLabelingParams mergeSystemWhiteLabelingParams(WhiteLabelingParams);
    WhiteLabelingParams mergeTenantWhiteLabelingParams(WhiteLabelingParams);
    WhiteLabelingParams mergeCustomerWhiteLabelingParams(TenantId, CustomerId, WhiteLabelingParams);

    // --- Delete ---
    void deleteAllTenantWhiteLabeling(TenantId);
    void deleteWhiteLabeling(TenantId, CustomerId, WhiteLabelingType);

    // --- Permission checks ---
    boolean isWhiteLabelingAllowed(TenantId, CustomerId);
    boolean isCustomerWhiteLabelingAllowed(TenantId);
    boolean isWhiteLabelingConfigured(TenantId);

    // --- Mail templates (PE feature) ---
    JsonNode saveMailTemplates(TenantId, JsonNode);
    JsonNode getCurrentTenantMailTemplates(TenantId, boolean includeDefaults);
    JsonNode findMailTemplatesByTenantId(TenantId tenantId, TenantId requestorTenantId);
    JsonNode getMergedTenantMailTemplates(TenantId);

    // --- Entity lookup ---
    WhiteLabeling findByEntityId(TenantId, CustomerId, WhiteLabelingType);
    WhiteLabeling findWhiteLabelingByDomainAndType(String domainName, WhiteLabelingType);

    // --- Self-registration (PE feature) ---
    WebSelfRegistrationParams saveTenantSelfRegistrationParams(TenantId, WebSelfRegistrationParams);
    WebSelfRegistrationParams getTenantSelfRegistrationParams(TenantId);
    WebSelfRegistrationParams getWebSelfRegistrationParams(String domainName);

    // --- Legal pages (PE feature) ---
    JsonNode getWebPrivacyPolicy(String domainName);
    JsonNode getTenantPrivacyPolicy(TenantId);
    JsonNode getWebTermsOfUse(String domainName);
    JsonNode getTenantTermsOfUse(TenantId);
}
```

### 4.2 BaseWhiteLabelingService (implementation)

```java
@Service
public class BaseWhiteLabelingService
    extends AbstractCachedService<WhiteLabelingCacheKey, WhiteLabeling, WhiteLabelingEvictEvent>
    implements WhiteLabelingService {

    // Dependencies injected via constructor
    private final AdminSettingsService adminSettingsService;
    private final DomainService domainService;
    private final WhiteLabelingDao whiteLabelingDao;
    private final TenantService tenantService;
    private final CustomerService customerService;
    private final ImageService imageService;
    private final ApplicationEventPublisher eventPublisher;

    // Static constants
    private static final String ALLOW_WHITE_LABELING = "allowWhiteLabeling";
    private static final String ALLOW_CUSTOMER_WHITE_LABELING = "allowCustomerWhiteLabeling";

    // --- Key private methods ---

    // Deserialize JsonNode -> WhiteLabelingParams
    private WhiteLabelingParams constructWlParams(JsonNode settings, boolean isLoginType);

    // Read raw params for any entity (tenant_id, customer_id)
    private WhiteLabelingParams getEntityWhiteLabelParams(TenantId, CustomerId);
    private LoginWhiteLabelingParams getEntityLoginWhiteLabelParams(TenantId, CustomerId);

    // Persist: serialize params -> JsonNode -> WhiteLabeling -> DAO
    private void saveWhiteLabelParams(TenantId, CustomerId, WhiteLabelingParams);
    private void saveLoginWhiteLabelParams(TenantId, CustomerId, LoginWhiteLabelingParams);
    private WhiteLabeling doSaveWhiteLabelingSettings(TenantId, WhiteLabeling);

    // Login save has extra domain/baseUrl validation
    private void saveEntityLoginWhiteLabelingParams(TenantId, CustomerId, LoginWhiteLabelingParams);
    private boolean isUsedOnSystemLevel(String baseUrl);
    private boolean isBaseUrlMatchesDomain(String baseUrl, String domainName);
    private String domainNameFromBaseUrl(String baseUrl);

    // Customer hierarchy traversal (PE has customer groups)
    private WhiteLabelingParams getMergedCustomerHierarchyWhileLabelingParams(TenantId, CustomerId, WhiteLabelingParams);
    private LoginWhiteLabelingParams getCustomerHierarchyLoginWhileLabelingParams(TenantId, CustomerId, LoginWhiteLabelingParams);

    // Cache event handling
    private static EntityId getEntityIdForEvent(TenantId, CustomerId);
    public void handleEvictEvent(WhiteLabelingEvictEvent event);
}
```

### 4.3 Cache Infrastructure

```java
// Cache key -- supports two lookup patterns
public class WhiteLabelingCacheKey implements Serializable {
    private WhiteLabelingCompositeKey key;      // for entity lookup
    private WhiteLabelingType type;             // for domain lookup
    private String domainName;                  // for domain lookup

    static WhiteLabelingCacheKey forKey(WhiteLabelingCompositeKey key);
    static WhiteLabelingCacheKey forTypeAndDomain(WhiteLabelingType type, String domain);
}

// Cache eviction event
public class WhiteLabelingEvictEvent {
    private final WhiteLabelingCacheKey key;
}

// Caffeine implementation
public class WhiteLabelingCaffeineCache
    extends CaffeineTbTransactionalCache<WhiteLabelingCacheKey, WhiteLabeling> {
    public WhiteLabelingCaffeineCache(CacheManager cacheManager);
}

// Redis implementation (for clustered deployments)
public class WhiteLabelingRedisCache
    extends RedisTbTransactionalCache<WhiteLabelingCacheKey, WhiteLabeling> {
    public WhiteLabelingRedisCache(TBRedisCacheConfiguration, CacheSpecsMap, RedisConnectionFactory);
}
```

> **Our implementation uses simple `@Cacheable`/`@CacheEvict` annotations.**
> PE uses a transactional cache pattern (`AbstractCachedService`) with separate
> Caffeine/Redis implementations and event-driven eviction.

---

## 5. Controller Layer

### 5.1 WhiteLabelingController

**Path prefix**: `/api`

| # | HTTP | Path | Auth | Method |
|---|------|------|------|--------|
| 1 | GET | `/whiteLabel/whiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `getWhiteLabelParams()` |
| 2 | GET | `/noauth/whiteLabel/loginWhiteLabelParams` | none (public) | `getLoginWhiteLabelParams(HttpServletRequest)` |
| 3 | GET | `/whiteLabel/currentWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `getCurrentWhiteLabelParams(String customerId)` |
| 4 | GET | `/whiteLabel/currentLoginWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `getCurrentLoginWhiteLabelParams(String customerId)` |
| 5 | POST | `/whiteLabel/whiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `saveWhiteLabelParams(WLParams, String customerId)` |
| 6 | POST | `/whiteLabel/loginWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `saveLoginWhiteLabelParams(LoginWLParams, String customerId)` |
| 7 | POST | `/whiteLabel/previewWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `previewWhiteLabelParams(WLParams)` |
| 8 | GET | `/whiteLabel/isWhiteLabelingAllowed` | TENANT_ADMIN, CUSTOMER_USER | `isWhiteLabelingAllowed()` |
| 9 | GET | `/whiteLabel/isCustomerWhiteLabelingAllowed` | TENANT_ADMIN | `isCustomerWhiteLabelingAllowed()` |
| 10 | POST | `/whiteLabel/mailTemplates` | SYS_ADMIN, TENANT_ADMIN | `saveMailTemplates(JsonNode)` |
| 11 | GET | `/whiteLabel/mailTemplates` | SYS_ADMIN, TENANT_ADMIN | `getMailTemplates(boolean useSystem)` |
| 12 | DELETE | `/whiteLabel/currentWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `deleteCurrentWhiteLabelParams(String customerId)` |
| 13 | DELETE | `/whiteLabel/currentLoginWhiteLabelParams` | SYS_ADMIN, TENANT_ADMIN, CUSTOMER_USER | `deleteCurrentLoginWhiteLabelParams(String customerId)` |

**Private helper**:
```java
private void checkWhiteLabelingPermissions(Operation operation) throws ThingsboardException {
    // In PE: calls subscriptionService to check if WL is enabled for the tenant
    // Then calls accessControlService.checkPermission(user, Resource.WHITE_LABELING, operation)
}
```

### 5.2 BaseController (whiteLabelingService injection)

```java
// In BaseController:
@Autowired
protected WhiteLabelingService whiteLabelingService;
```

This is the field our `WhiteLabelingController` (and others) use to access the service.

---

## 6. Permission System

### 6.1 Resource Enum (PE version)

**CRITICAL**: In PE, `Resource` is in `org.thingsboard.server.common.data.permission.Resource` (data module).
In CE, `Resource` is in `org.thingsboard.server.service.security.permission.Resource` (application module).

PE's Resource enum includes:
```java
public static final Resource WHITE_LABELING;  // Used for WL permission checks
public static final Resource CUSTOM_MENU;     // Also gates through WL permissions
```

### 6.2 EntityType -- NO WHITE_LABELING

PE's `EntityType` enum does **NOT** contain `WHITE_LABELING`. The list:
```
TENANT, CUSTOMER, USER, DASHBOARD, ASSET, DEVICE, ALARM,
ENTITY_GROUP, CONVERTER, INTEGRATION, RULE_CHAIN, RULE_NODE,
SCHEDULER_EVENT, BLOB_ENTITY, REPORT_TEMPLATE, REPORT,
ENTITY_VIEW, WIDGETS_BUNDLE, WIDGET_TYPE, ROLE, GROUP_PERMISSION,
TENANT_PROFILE, DEVICE_PROFILE, ASSET_PROFILE, API_USAGE_STATE,
TB_RESOURCE, OTA_PACKAGE, EDGE, RPC, QUEUE, NOTIFICATION_TARGET,
NOTIFICATION_TEMPLATE, NOTIFICATION_REQUEST, NOTIFICATION,
NOTIFICATION_RULE, QUEUE_STATS, OAUTH2_CLIENT, DOMAIN, MOBILE_APP,
MOBILE_APP_BUNDLE, CALCULATED_FIELD, CALCULATED_FIELD_LINK,
JOB, SECRET, ADMIN_SETTINGS, AI_MODEL
```

> This confirms: **WhiteLabeling is NOT an entity type**. It's a settings table with
> a composite primary key, not a UUID-based entity. This is why we removed
> WHITE_LABELING from EntityType in our implementation.

### 6.3 Permission Checkers

**TenantAdminPermissions** (PE):
```java
// Has these WL-related fields:
private WhiteLabelingService whiteLabelingService;
private final PermissionChecker tenantWhiteLabelingPermissionChecker;

// Constructor registers:
put(Resource.WHITE_LABELING, tenantWhiteLabelingPermissionChecker);

// The checker calls:
// whiteLabelingService.isWhiteLabelingAllowed(tenantId, customerId)
// to gate access based on subscription
```

**CustomerUserPermissions** (PE):
```java
// Has these WL-related fields:
private WhiteLabelingService whiteLabelingService;
private final PermissionChecker customerWhiteLabelingPermissionChecker;

// Constructor registers:
put(Resource.WHITE_LABELING, customerWhiteLabelingPermissionChecker);
```

**SysAdminPermissions** (PE):
- Does **NOT** have a WL-specific checker
- SYS_ADMIN uses generic `systemEntityPermissionChecker` or `allowAllPermissionChecker`

### 6.4 Our Approach (CE -- always enabled)

Since we don't have subscription gating:
- **No** `WHITE_LABELING` in `Resource` enum
- **No** WL permission checkers in permission classes
- Controller's `checkWhiteLabelingPermissions()` is a **no-op**
- Role access controlled purely by `@PreAuthorize` annotations

---

## 7. Edge Sync

### 7.1 WhiteLabelingEdgeProcessor

```java
@Component
public class WhiteLabelingEdgeProcessor {
    private WhiteLabelingService whiteLabelingService;
    private DomainService domainService;

    // Converts edge events to downlink messages
    DownlinkMsg convertEdgeEventToDownlink(EdgeEvent edgeEvent) {
        switch (edgeEvent.getAction()) {
            case ADDED:
            case UPDATED:
                // Resolves entity -> fetches WhiteLabeling -> builds WhiteLabelingProto
                WhiteLabeling wl = whiteLabelingService.findByEntityId(tenantId, customerId, type);
                WhiteLabelingProto proto = constructWhiteLabelingProto(wl);
                return DownlinkMsg.newBuilder().addWhiteLabeling(proto).build();
            case DELETED:
                // Sends delete message
        }
    }

    // Maps EdgeEventType -> WhiteLabelingType
    WhiteLabelingType getWhiteLabelingType(EdgeEventType eet) {
        WHITE_LABELING -> GENERAL
        MAIL_TEMPLATES -> MAIL_TEMPLATES
        LOGIN_WHITE_LABELING -> LOGIN
    }

    // Handles notifications to push updates to edges
    void processEntityNotification(TenantId, EdgeEvent) {
        // For TENANT: iterates all tenants, pushes to edges
        // For CUSTOMER: uses CustomerHierarchy to find edges
    }
}
```

### 7.2 WhiteLabelingEdgeEventFetcher

```java
public class WhiteLabelingEdgeEventFetcher {
    // Fetches 3 WL types for sync:
    // WHITE_LABELING, LOGIN_WHITE_LABELING, MAIL_TEMPLATES
    //
    // For each: creates events for system, tenant, and customer
    // (with parent customer hierarchy recursion)
}
```

### 7.3 WhiteLabelingProto (protobuf)

```protobuf
message WhiteLabelingProto {
    int32 msgType = 1;    // ADD/UPDATE/DELETE
    string entity = 2;    // JSON-serialized WhiteLabeling
}
```

---

## 8. Cross-Cutting References

### 8.1 DefaultMailService

Every email-sending method calls:
```java
JsonNode templates = whiteLabelingService.getMergedTenantMailTemplates(tenantId);
```
This provides tenant-customized email templates (logo, colors, footer text).

### 8.2 DefaultSystemSecurityService.getBaseUrl()

Resolves the platform base URL by checking WL login params in order:
1. Customer `LoginWhiteLabelingParams.baseUrl`
2. Tenant `LoginWhiteLabelingParams.baseUrl`
3. System `LoginWhiteLabelingParams.baseUrl`
4. Fallback to `adminSettings("general").baseUrl`

### 8.3 InstallScripts

At system install time:
```java
whiteLabelingService.saveMailTemplates(SYS_TENANT_ID, defaultMailTemplates);
```

### 8.4 DefaultDataUpdateService

During data migration:
```java
whiteLabelingService.findMailTemplatesByTenantId(SYS_TENANT_ID, SYS_TENANT_ID);
// Updates/migrates mail template format
```

### 8.5 ResourcesUpdater.updateWhiteLabelingImages()

Migrates base64-encoded images in WL settings to image URLs:
```java
// Iterates all GENERAL + LOGIN WhiteLabeling entities
// Calls imageService.replaceBase64WithImageUrl() on settings JSON
// Saves updated entities via whiteLabelingDao.save()
```

### 8.6 ImageController (login image endpoints)

PE adds login-specific image endpoints that resolve images through WL:
```java
downloadLoginLogo(HttpServletRequest request, ...)
downloadLoginFavicon(HttpServletRequest request, ...)
// Uses whiteLabelingService.getLoginImageKey(domainName, isFavicon)
```

### 8.7 Controllers with checkWhiteLabelingPermissions()

These controllers gate certain operations through WL permissions:
- `WhiteLabelingController` -- all WL-specific endpoints
- `CustomMenuController` -- custom menu CRUD
- `CustomTranslationController` -- custom translation CRUD
- `DashboardController` -- home dashboard info operations
- `TranslationController` -- translation operations

### 8.8 SystemInfoController

Uses `whiteLabelingService.isWhiteLabelingConfigured(tenantId)` to include
in the system params response, so the UI knows whether to show WL features.

---

## 9. Database Schema

### 9.1 PE Schema (from `dao-4.2.0PE.jar`)

```sql
CREATE TABLE IF NOT EXISTS white_labeling (
    tenant_id   UUID NOT NULL,
    customer_id UUID NOT NULL DEFAULT '13814000-1dd2-11b2-8080-808080808080',
    type        VARCHAR(30),
    settings    VARCHAR(10000000),
    domain_id   UUID,
    CONSTRAINT white_labeling_pkey PRIMARY KEY (tenant_id, customer_id, type),
    CONSTRAINT white_labeling_domain_id_type_key UNIQUE (type, domain_id),
    CONSTRAINT fk_white_labeling_domain_id FOREIGN KEY (domain_id) REFERENCES domain(id)
);
```

### 9.2 Our Schema

```sql
CREATE TABLE IF NOT EXISTS white_labeling (
    tenant_id   uuid NOT NULL,
    customer_id uuid NOT NULL,
    type        varchar(32) NOT NULL,
    settings    jsonb,
    domain_id   uuid,
    CONSTRAINT white_labeling_pkey PRIMARY KEY (tenant_id, customer_id, type)
);
```

### 9.3 Key Differences

| Aspect | PE | Ours | Notes |
|--------|----|------|-------|
| `customer_id` default | `'13814000-1dd2-11b2-8080-808080808080'` | none | PE defaults to NULL_UUID |
| `type` | `VARCHAR(30)`, nullable | `varchar(32) NOT NULL` | Minor |
| `settings` | `VARCHAR(10000000)` | `jsonb` | We use native JSON; PE uses huge varchar |
| `UNIQUE(type, domain_id)` | Yes | No | PE prevents duplicate domain bindings |
| `FK domain(id)` | Yes | No | We don't have a domain table |

### 9.4 NULL_UUID Value

The constant `'13814000-1dd2-11b2-8080-808080808080'` is `EntityId.NULL_UUID` in ThingsBoard.
It represents "no customer" (system or tenant-level entries).

### 9.5 Composite Key Semantics

| tenant_id | customer_id | type | Meaning |
|-----------|-------------|------|---------|
| NULL_UUID | NULL_UUID | GENERAL | System-level general branding |
| NULL_UUID | NULL_UUID | LOGIN | System-level login branding |
| {tenant} | NULL_UUID | GENERAL | Tenant-level general branding |
| {tenant} | NULL_UUID | LOGIN | Tenant-level login branding |
| {tenant} | {customer} | GENERAL | Customer-level general branding |
| {tenant} | {customer} | LOGIN | Customer-level login branding |

---

## 10. Cache Configuration

### 10.1 PE Configuration

```yaml
# thingsboard.yml
cache:
  specs:
    whiteLabeling:   # <-- cache name
      timeToLiveInMinutes: "${CACHE_SPECS_WHITE_LABELING_TTL:1440}"  # 24 hours
      maxSize: "${CACHE_SPECS_WHITE_LABELING_MAX_SIZE:10000}"
```

```java
// CacheConstants.java
public static final String WHITE_LABELING_CACHE = "whiteLabeling";
```

### 10.2 Our Configuration

```yaml
cache:
  specs:
    whiteLabelingCache:   # <-- different name
      timeToLiveInMinutes: "${CACHE_SPECS_WHITE_LABELING_TTL:1440}"
      maxSize: "${CACHE_SPECS_WHITE_LABELING_MAX_SIZE:10000}"
```

```java
public static final String WHITE_LABELING_CACHE = "whiteLabelingCache";
```

> **Mismatch**: PE uses `"whiteLabeling"`, we use `"whiteLabelingCache"`.
> Both are internally consistent (YAML key matches constant), but differ from PE naming.

---

## 11. Key Architectural Decisions

### 11.1 Not an Entity

White labeling is **NOT** modeled as an entity with a UUID primary key. It uses a
**composite primary key** `(tenant_id, customer_id, type)`. This means:
- No entry in `EntityType` enum
- No `EntityIdFactory` case
- No `WhiteLabelingId` class
- DAO extends `TenantEntityDao`, not `Dao<T>`
- JPA DAO extends `JpaAbstractDaoListeningExecutorService`, not `JpaAbstractDao`

### 11.2 Service in `dao` Module (not `dao-api`)

In PE, `WhiteLabelingService` is in the `dao` module, not `common/dao-api`.
This is because it's tightly coupled to the DAO implementation.

However, `BaseController` in the `application` module depends on it, which works
because `application` depends on `dao`.

### 11.3 Merge Semantics

The 3-tier merge works as follows:
```
System defaults (WhiteLabelingParams.defaultParams())
    └── System-level settings (from DB, GENERAL/LOGIN type with NULL_UUID tenant/customer)
        └── Tenant-level settings (override/inherit per field)
            └── Customer-level settings (override/inherit per field)
```

**Merge rules**:
- null/empty fields in child -> inherit from parent
- Non-null fields in child -> override parent
- `customCss` is **concatenated** (parent + "\n" + child), not overridden
- `PaletteSettings.merge()`: empty palette type -> inherit entire parent palette

### 11.4 Cache Pattern

PE uses **transactional caching** via `AbstractCachedService`:
- Two cache implementations: `WhiteLabelingCaffeineCache` (local) and `WhiteLabelingRedisCache` (cluster)
- Cache eviction via Spring `ApplicationEventPublisher` + `WhiteLabelingEvictEvent`
- `WhiteLabelingCacheKey` supports two access patterns:
  - By composite key: `forKey(WhiteLabelingCompositeKey)`
  - By domain: `forTypeAndDomain(WhiteLabelingType, String)`

Our simplified approach uses `@Cacheable`/`@CacheEvict` annotations directly.

### 11.5 Permission Gating (PE vs CE)

In PE:
```java
// WhiteLabelingController.checkWhiteLabelingPermissions():
1. subscriptionService.checkSubscription(tenantId, Feature.WHITE_LABELING)
2. accessControlService.checkPermission(user, Resource.WHITE_LABELING, operation)
```

In our CE implementation:
```java
// checkWhiteLabelingPermissions() is a no-op
// Role access via @PreAuthorize annotations only
```

---

## 12. Differences: Our Implementation vs PE

### 12.1 What We Have That PE Has

| Area | Status |
|------|--------|
| Data models (WhiteLabelingParams, LoginWhiteLabelingParams, Favicon, Palette, PaletteSettings) | Matching |
| WhiteLabeling persistence entity with composite key | Matching |
| WhiteLabelingType enum (GENERAL, LOGIN) | Subset (PE has 6) |
| WhiteLabelingEntity + CompositeKey + Repository | Matching core |
| WhiteLabelingDao extending TenantEntityDao | Matching pattern |
| JpaWhiteLabelingDao extending JpaAbstractDaoListeningExecutorService | Matching pattern |
| WhiteLabelingService interface (core methods) | Subset |
| WhiteLabelingController (11 endpoints) | Subset (PE has 13) |
| BaseController.whiteLabelingService injection | Matching |
| 3-tier merge semantics | Matching |
| Caffeine cache configuration | Matching (different key name) |
| SQL table with composite PK | Matching (minor column differences) |

### 12.2 What We're Missing vs PE

| Area | PE Has | Our Status | Priority |
|------|--------|------------|----------|
| `WhiteLabelingType` values: MAIL_TEMPLATES, SELF_REGISTRATION, TERMS_OF_USE, PRIVACY_POLICY | Yes | Missing | Low (PE-only features) |
| Mail templates integration (DefaultMailService) | Yes | Missing | Medium |
| Self-registration integration | Yes | Missing | Low |
| Privacy policy / Terms of use | Yes | Missing | Low |
| `WhiteLabelingCacheKey` class | Yes | Using simple @Cacheable | Low |
| `WhiteLabelingEvictEvent` class | Yes | Using @CacheEvict | Low |
| `WhiteLabelingCaffeineCache` / `WhiteLabelingRedisCache` | Yes | Using @Cacheable | Low |
| `Resource.WHITE_LABELING` enum constant | Yes (in data.permission.Resource) | Omitted (no subscription gating) | N/A |
| WL permission checkers in TenantAdmin/CustomerUser | Yes | Omitted (always enabled) | N/A |
| Edge sync (WhiteLabelingEdgeProcessor, WhiteLabelingEdgeEventFetcher) | Yes | Missing | Low |
| WhiteLabelingProto (protobuf) | Yes | Missing | Low |
| `LoginWhiteLabelingParams.prohibitDifferentUrl` | Yes | Missing | Low |
| `LoginWhiteLabelingParams.adminSettingsId` | Yes | Missing | Low |
| `WhiteLabelingDao.findByDomainAndType()` | Yes | Missing | Medium |
| `WhiteLabelingDao.findByTenantAndImageLink()` | Yes | Missing | Low |
| `WhiteLabelingDao.findByImageLink()` | Yes | Missing | Low |
| `WhiteLabelingDao.findAllByType()` | Yes | Missing | Low |
| Domain table FK constraint | Yes | Missing | Medium |
| `UNIQUE(type, domain_id)` constraint | Yes | Missing | Medium |
| `ImageController.downloadLoginLogo/Favicon` | Yes | Missing | Medium |
| `ResourcesUpdater.updateWhiteLabelingImages()` | Yes | Missing | Low |
| `InstallScripts` WL mail template initialization | Yes | Missing | Low |
| `DefaultSystemSecurityService.getBaseUrl()` WL integration | Yes | Missing | Medium |
| `SystemInfoController.isWhiteLabelingConfigured()` | Yes | Missing | Low |
| Repository native queries (ILIKE image search) | Yes | Missing | Low |

### 12.3 Schema Differences to Address

1. **customer_id default**: PE has `DEFAULT '13814000-...'`, we don't
2. **settings column**: PE uses `VARCHAR(10000000)`, we use `jsonb` (our choice is better)
3. **Missing constraints**: `UNIQUE(type, domain_id)` and `FK domain(id)`

### 12.4 Cache Name Mismatch

- PE: `whiteLabeling` (CacheConstants + thingsboard.yml)
- Ours: `whiteLabelingCache` (CacheConstants + thingsboard.yml)
- Functionally equivalent but worth noting for consistency

---

## Appendix: Call Graph

```
WhiteLabelingController
    ├── getWhiteLabelParams()
    │   └── whiteLabelingService.getMergedTenantWhiteLabelingParams()
    │       ├── getSystemWhiteLabelingParams()
    │       │   └── whiteLabelingDao.findById(NULL_UUID, NULL_UUID, GENERAL)
    │       └── getTenantWhiteLabelingParams()
    │           └── whiteLabelingDao.findById(tenantId, NULL_UUID, GENERAL)
    │               └── WhiteLabelingParams.merge(systemParams)
    │
    ├── getLoginWhiteLabelParams(HttpServletRequest)  [noauth]
    │   └── whiteLabelingService.getMergedLoginWhiteLabelingParams(domainName)
    │       ├── findByDomainAndType(domain, LOGIN) -> find owner tenant
    │       ├── getSystemLoginWhiteLabelingParams()
    │       ├── getTenantLoginWhiteLabelingParams()
    │       └── getCustomerLoginWhiteLabelingParams()
    │           └── LoginWhiteLabelingParams.merge(cascade)
    │
    ├── saveWhiteLabelParams()
    │   └── checkWhiteLabelingPermissions(WRITE)
    │       └── [PE: subscriptionService + accessControlService]
    │       └── [CE: no-op]
    │
    └── deleteCurrentWhiteLabelParams()
        └── whiteLabelingService.deleteWhiteLabeling(tenantId, customerId, GENERAL)
            └── whiteLabelingDao.removeById(compositeKey)

DefaultMailService.sendActivationEmail()
    └── whiteLabelingService.getMergedTenantMailTemplates(tenantId)
        └── [looks up MAIL_TEMPLATES type WL entries]

DefaultSystemSecurityService.getBaseUrl(tenantId, customerId)
    ├── whiteLabelingService.getCustomerLoginWhiteLabelingParams() -> baseUrl
    ├── whiteLabelingService.getTenantLoginWhiteLabelingParams() -> baseUrl
    └── whiteLabelingService.getSystemLoginWhiteLabelingParams() -> baseUrl
```
