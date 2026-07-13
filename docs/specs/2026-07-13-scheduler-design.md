# Scheduler (PE Replication) — Design Specification

**Date:** 2026-07-13
**Status:** Approved
**Source:** ThingsBoard PE 4.2.0 (decompiled from `docs/jars/`, javap + CFR)
**Target:** Inferrix CE 4.3.1.3, branch `inferrix-release-4.3`
**Approach:** In-core PE-mirror — classes at exact PE paths, PE-identical REST API and data models, core-file modifications tracked in `INFERRIX-PATCHES.md`
**Scope decisions:** No edge sync. No EDQS/dashboard-alias integration. No version-control export/import. `generateReport` type ships behind a no-op seam until inferrix-reporting is forward-ported.

---

## 1. Overview

Replicate the ThingsBoard PE Scheduler feature: tenant/customer-scoped **scheduler events** that fire at configured times (one-shot or recurring) and dispatch actions — rule-engine messages, attribute updates, device RPCs, OTA updates, report generation — with cluster-partition-aware execution and failover.

The implementation mirrors PE 4.2.0 exactly where CE has the required substrate, and documents every deviation (§11). Same pattern as the White Labeling port: new files are additive at PE-identical paths; each modified TB-core file gets a ledger row.

### Included (v1)
- `SCHEDULER_EVENT` entity type, `scheduler_event` table, full DAO layer
- 7 repeat types: `DAILY, EVERY_N_DAYS, WEEKLY, EVERY_N_WEEKS, MONTHLY, YEARLY, TIMER` (+ one-shot)
- Partition-aware scheduler engine on `AbstractPartitionBasedService<TenantId>` with queue-based CRUD propagation and repartition failover
- Event types: `updateAttributes`, `sendRpcRequest`, `updateFirmware`, `updateSoftware` (DEVICE + DEVICE_PROFILE originators), `generateDashboardReport`, `generateReport` (seam-stubbed), plus arbitrary custom types
- PE-identical REST API (CRUD, enable/disable, by-type / paged / time-window listings)
- Permissions: `Resource.SCHEDULER_EVENT` for TENANT_ADMIN (tenant scope) and CUSTOMER_USER (customer scope); audit logging on all mutations
- UI: `/features/scheduler` — table + calendar view (FullCalendar), event dialog with schedule editor and per-type config forms; all 126 PE i18n keys

### Excluded (future phases — §14)
- Edge sync (proto, fetcher, processor, assign/unassign endpoints)
- EDQS fields/query-processor, `EntityFilterType.SCHEDULER_EVENT`, dashboard alias filter
- Version-control export/import (`SchedulerEventExportData`, export/import services)
- `/scheduledReports` endpoint + `ScheduledReportInfo` (PE-reports coupling)
- PE solution-templates installer references
- Real report generation (arrives when inferrix-reporting lands on this branch)

---

## 2. Data Model

All new files in `common/data/src/main/java/org/thingsboard/server/common/data/`, package-identical to PE.

| Class | Role |
|---|---|
| `scheduler/SchedulerEventInfo` | Base entity. `extends BaseDataWithAdditionalInfo<SchedulerEventId>`, implements `HasName, TenantEntity, HasCustomerId, HasVersion, ExportableEntity<SchedulerEventId>`. Fields: `tenantId, customerId, originatorId (EntityId), name, type (String), schedule (JsonNode), enabled (boolean), externalId, version`. `toDescriptor()` parses schedule JSON → `SchedulerEventDescriptor` (parse failure → ERROR log, returns null) |
| `scheduler/SchedulerEvent` | `extends SchedulerEventInfo` + `configuration (JsonNode)`, lazily (de)serialized via `configurationBytes` |
| `scheduler/SchedulerEventWithCustomerInfo` | `extends SchedulerEventInfo` + `customerTitle, customerIsPublic, timestamps (List<Long>)` — upcoming fire times for the calendar view |
| `scheduler/SchedulerEventDescriptor` | `(long startTime, String timezone, SchedulerRepeat repeat)`; `passedAway(ts)`, `getNextEventTime(ts)` |
| `scheduler/SchedulerRepeat` | Polymorphic interface (`@JsonTypeInfo(property = "type")`): `getEndsOn() / getType() / getNext(startTime, ts, timezone)` |
| `scheduler/SchedulerRepeatType` | enum: `DAILY, EVERY_N_DAYS, WEEKLY, EVERY_N_WEEKS, MONTHLY, YEARLY, TIMER` |
| `scheduler/{Daily,EveryNDays,Weekly,EveryNWeeks,Monthly,Yearly,Timer}Repeat` | Implementations. `Monthly`/`Yearly` extend `SchedulerDate` (Calendar-field stepping). `WeeklyRepeat.repeatOn: List<Integer>`, 0=Sunday…6=Saturday (`Calendar.DAY_OF_WEEK - 1`). `EveryNDaysRepeat.days: int`. `EveryNWeeksRepeat.weeks: int`. `TimerRepeat.repeatInterval + timeUnit (java TimeUnit) + endsOn` |
| `scheduler/SchedulerDate` | Abstract helper: `getNext(…, long endsOn, int calendarField)` |
| `scheduler/SchedulerUtils` | `getCalendarWithTimeZone(tz)` — unknown timezone silently falls back to UTC (PE behavior) |
| `scheduler/SchedulerEventFilter` (+ builder) | DAO filter: `customerId, type` (PE also has `edgeId` — dropped in v1) |
| `scheduler/SchedulerEventTimeFilter` (+ builder) | extends filter, adds `startTime, endTime` |
| `id/SchedulerEventId` | `UUIDBased implements EntityId` |

Exact field/method signatures come from the decompiled PE classes in `docs/jars/BOOT-INF/lib/data-4.2.0PE.jar`.

**Deviation:** PE's `SchedulerEventInfo` also implements `HasOwnerId` — dropped (CE has no owner model).

### Schedule JSON

```json
{
  "timezone": "Europe/Kyiv",
  "startTime": 1750000000000,
  "repeat": { "type": "WEEKLY", "endsOn": 1760000000000, "repeatOn": [1, 3, 5] }
}
```

- `repeat` optional → one-shot at `startTime`
- Variant fields: `EVERY_N_DAYS` + `days`; `WEEKLY` + `repeatOn[0..6]`; `EVERY_N_WEEKS` + `weeks`; `TIMER` + `repeatInterval` + `timeUnit ("MINUTES"…)`; `DAILY/MONTHLY/YEARLY` need only `endsOn`
- All `getNext` walk from `startTime` in the event's timezone; return `0` when exhausted
- `passedAway(ts)` = (no repeat && startTime < ts) || (repeat.endsOn < ts)

### Configuration JSON

```json
{
  "originatorId": { "entityType": "DEVICE", "id": "…" },
  "msgType": "updateAttributes",
  "msgBody": { "targetTemperature": 22 },
  "metadata": { "scope": "SERVER_SCOPE" }
}
```

Effective originator = `configuration.originatorId ?: event.id`.

### Enums / IDs (core-file touches)

- `EntityType.SCHEDULER_EVENT` — proto-number **103** (matches PE numbering)
- `EntityIdFactory` — two switch cases → `new SchedulerEventId(uuid)`
- `TbMsgType.generateReport("Generate Report")` — appended (used by `generateDashboardReport` firing)

---

## 3. Database Schema

Verbatim from PE `dao-4.2.0PE.jar!sql/schema-entities.sql`:

```sql
CREATE TABLE IF NOT EXISTS scheduler_event (
    id uuid NOT NULL CONSTRAINT scheduler_event_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    additional_info varchar,
    customer_id uuid,
    originator_id uuid,
    originator_type varchar(255),
    name varchar(255),
    tenant_id uuid,
    type varchar(255),
    schedule varchar,
    configuration varchar(10000000),
    enabled boolean,
    external_id uuid,
    version BIGINT DEFAULT 1,
    CONSTRAINT scheduler_event_external_id_unq_key UNIQUE (tenant_id, external_id)
);

CREATE INDEX IF NOT EXISTS idx_scheduler_event_originator_id
    ON scheduler_event(tenant_id, originator_id);
```

No FKs. `originator_id`/`originator_type` reconstruct the `EntityId`.

Placement:
- `dao/src/main/resources/sql/schema-entities.sql` — appended after the White Labeling block (fresh installs)
- `dao/src/main/resources/sql/schema-entities-idx.sql` — index appended
- `application/src/main/data/upgrade/basic/schema_update.sql` — same idempotent block appended, wrapped in `-- SCHEDULER EVENT TABLE START/END` markers (existing installs; mirrors WL row 1)

---

## 4. DAO Layer

New files under `dao/src/main/java/org/thingsboard/server/dao/`:

- `scheduler/SchedulerEventService` (interface, `extends EntityDaoService`) — PE has 22 methods; v1 keeps 14: find by id / info / withCustomerInfo (sync + async + by-ids), `findSchedulerEventsByTenantId[AndType]`, `findSchedulerEventsByTenantIdAndEnabled`, `findSchedulerEventsByTenantIdAndFilter` (paged), `findSchedulerEventsByTimeFilter`, `saveSchedulerEvent`, `deleteSchedulerEvent`, `deleteSchedulerEventsByTenantId`, `deleteSchedulerEventsByTenantIdAndCustomerId`. **Dropped:** `assignSchedulerEventToEdge`, `unassignSchedulerEventFromEdge`, 3 edge finders (edge out of scope); `findScheduledReportEvents` ×2 + `countScheduledReportEventsByTemplateId` (PE reports)
- `scheduler/BaseSchedulerEventService` — `extends AbstractEntityService`. Deps: `SchedulerEventDao`, `SchedulerEventInfoDao`, `DataValidator<SchedulerEvent>`, `EntityCountService` (PE's `EdgeService` dep dropped). Save publishes `SaveEntityEvent` + count-cache evict; delete publishes `DeleteEntityEvent`. Constraint hook: `scheduler_event_external_id_unq_key`. Static `getOriginatorId(event)` = `configuration.originatorId ?: event.id`
- `scheduler/SchedulerEventDao`, `scheduler/SchedulerEventInfoDao` (interfaces); `sql/scheduler/JpaSchedulerEventDao`, `sql/scheduler/JpaSchedulerEventInfoDao` (impls); `sql/scheduler/SchedulerEventRepository`, `sql/scheduler/SchedulerEventInfoRepository` (Spring Data)
- `model/sql/AbstractSchedulerEventInfoEntity<T>` (`extends BaseVersionedEntity<T>`), `model/sql/SchedulerEventInfoEntity`, `model/sql/SchedulerEventEntity` (`@Table("scheduler_event")`), `model/sql/SchedulerEventWithCustomerInfoEntity` (+ `customerTitle`, `customerIsPublic` via LEFT JOIN on customer; public flag parsed from customer `additional_info`)
- `service/validator/SchedulerEventDataValidator`
- `dao/model/ModelConstants.java` — scheduler-event column-name constants block (core-file touch, mirrors WL row 9)

Notable semantics:
- The **time-filter query** loads candidate events, then computes each event's fire `timestamps` in Java by walking `toDescriptor().getNextEventTime()` over `[startTime, endTime]`, dropping events with no occurrences — powers the calendar view
- Filter query: tenant + optional customer + optional type + `ILIKE` text search over name/type/customer title
- Validator rules: `type`, `name`, `schedule`, `configuration`, `tenantId` mandatory; customer must belong to tenant (else reset to NULL_UUID); `TIMER` interval ≥ `scheduler.min_interval` (default 60 s); `updateFirmware`/`updateSoftware` validate OTA package type + device-profile match; per-tenant entity-count limit via `validateNumberOfEntitiesPerTenant`

---

## 5. Cluster & Queue Integration

New message in `common/proto/src/main/proto/queue.proto`:

```protobuf
message SchedulerServiceMsgProto {
  int64 tenantIdMSB = 1;
  int64 tenantIdLSB = 2;
  int64 eventIdMSB = 3;
  int64 eventIdLSB = 4;
  bool added = 5;
  bool updated = 6;
  bool deleted = 7;
}
```

Carried as `ToCoreMsg.schedulerServiceMsg = 11` — **not** PE's field 5, which is occupied (`edgeNotificationMsg`) in CE. Field 11 verified free at analysis time; re-verify against the working tree when editing the proto. Internal-only divergence: nothing external speaks this protocol.

Flow: DAO save/delete → `SaveEntityEvent`/`DeleteEntityEvent` → `EntityStateSourcingListener` (new `SCHEDULER_EVENT` branches, `Optional<SchedulerService>` dependency) → `SchedulerService.onSchedulerEventAdded/Updated/Deleted` → proto → `TbClusterService.pushMsgToCore(tenantId, tenantId, msg)` → routed to the core node owning the tenant's partition → `DefaultTbCoreConsumerService` (new `hasSchedulerServiceMsg()` branch) → `SchedulerService.onQueueMsg`.

---

## 6. Scheduler Engine

`application/…/service/scheduler/`:

- `SchedulerService` (interface): `onSchedulerEventAdded/Updated/Deleted(SchedulerEventInfo)`, `onQueueMsg(SchedulerServiceMsgProto, TbCallback)`
- `DefaultSchedulerService` — `@TbCoreComponent @Service`, `extends AbstractPartitionBasedService<TenantId>` (CE base class verified hook-identical to PE's). State: `tenantEvents: Map<TenantId, List<SchedulerEventId>>`, `eventsMetaData: Map<SchedulerEventId, SchedulerEventMetaData>`. Deps (v1): `TenantService, TbClusterService, PartitionService, SchedulerEventService, OtaPackageStateService, DeviceService, DeviceProfileService, OtaPackageService, TbServiceInfoProvider, SchedulerReportExecutor`. **Dropped PE deps:** `EntityGroupService`, `DeviceGroupOtaPackageService` (entity groups), `JobManager` (PE reports)
- `SchedulerEventMetaData` — descriptor + volatile `ListenableScheduledFuture nextTaskFuture`
- `report/SchedulerReportExecutor` (interface, **Inferrix seam — not in PE**): `void executeReport(TenantId tenantId, SchedulerEvent event)`
- `report/NoOpSchedulerReportExecutor` — `@ConditionalOnMissingBean(SchedulerReportExecutor)`: logs WARN ("report generation not available until Reporting module is installed") and returns. When inferrix-reporting is forward-ported it contributes the real bean; no scheduler change needed

### Lifecycle

- **Partition assigned** (`onAddedPartitions`): iterate all tenants (`PageDataIterable`, batch 1024); for tenants resolving to owned `TB_CORE` partitions, load `findSchedulerEventsByTenantIdAndEnabled(true)`, skip `passedAway` events (WARN log — upgraded from PE's DEBUG), schedule the rest on the single-thread scheduled executor
- **Partition removed** (`cleanupEntityOnPartitionRemoval`): cancel futures + evict that tenant's state
- **Failover:** node death → repartition event → new owner reloads from DB. One-shot events whose time passed while unowned are dropped by design (PE parity; no catch-up window)
- **Queue msg:** `deleted` → cancel + evict; `added`/`updated` → reload `SchedulerEventInfo` from DB, cancel old future, re-schedule

### Firing (`processEvent`)

Reload full `SchedulerEvent` from DB (stale-delete guard: gone → evict, stop). Dispatch by `event.type`:

| Type | Action |
|---|---|
| `updateFirmware` / `updateSoftware` | OTA package id from `msgBody`; originator `DEVICE` → set fw/sw id + `saveDevice`; `DEVICE_PROFILE` → set id + `saveDeviceProfile` + `OtaPackageStateService.update`. PE's `ENTITY_GROUP` branch dropped. **After** the OTA assignment, the default rule-engine message is also pushed (PE behavior: every type except `generateReport` falls through to the TbMsg push) |
| `generateReport` | `schedulerReportExecutor.executeReport(tenantId, event)` (no-op until Reporting lands); the only type that does **not** push a rule-engine message |
| **default** — `updateAttributes`, `sendRpcRequest`, `generateDashboardReport`, custom types | Build `TbMsg`: `type = configuration.msgType ?: event.type` (`generateDashboardReport` maps to `TbMsgType.generateReport`); `originator = configuration.originatorId ?: event.id`; `metadata = configuration.metadata` verbatim, else defaults `{customerId, eventName, additionalInfo}`; `data = msgBody` (JSON). `sendRpcRequest` additionally sets `originServiceId` (this node) and `expirationTime = now + max(rpcMinTimeout, metadata.timeout)`. Push via `TbClusterService.pushMsgToRuleEngine(tenantId, originatorId, tbMsg, null)` |

Always: `scheduleNextEvent(now, …)` re-arms the next occurrence. Firing errors are caught, logged at ERROR with tenant/event/type, and never break the chain. Each fire is logged at INFO with tenant/event/type/originator (upgraded from PE's DEBUG — operational visibility).

**Rule-chain contract (document for users):** `updateAttributes` fires a rule-engine message of type `"updateAttributes"` — *not* `POST_ATTRIBUTES_REQUEST`. The tenant root rule chain must route these types (message-type switch → save attributes / RPC node), exactly as in PE.

### Configuration (`thingsboard.yml`, core-file touch)

```yaml
scheduler:
  # Minimum interval between subsequent scheduler events (TIMER repeat type)
  min_interval: "${SCHEDULER_MIN_INTERVAL_IN_SEC:60}"
```

(PE nests this as `reports.scheduler.min_interval` — relocated because reporting doesn't own our scheduler.) RPC timeout floor reuses CE's existing `server.rest.server_side_rpc.min_timeout`. Audit-log mask gains `"scheduler_event": "${AUDIT_LOG_MASK_SCHEDULER_EVENT:W}"`.

---

## 7. REST API

`application/…/controller/SchedulerEventController.java`, base `/api`, all endpoints `@PreAuthorize("hasAnyAuthority('TENANT_ADMIN','CUSTOMER_USER')")`, per-entity checks via `checkSchedulerEvent(Info)Id(id, Operation)`:

| Method + path | Params | Returns |
|---|---|---|
| GET `/schedulerEvent/info/{id}` | — | `SchedulerEventWithCustomerInfo` |
| GET `/schedulerEvent/{id}` | — | `SchedulerEvent` (full configuration) |
| POST `/schedulerEvent` | body | `SchedulerEvent` — forces tenantId; CUSTOMER_USER forces own customerId |
| PUT `/schedulerEvent/{id}/enabled/{bool}` | — | `SchedulerEvent` |
| DELETE `/schedulerEvent/{id}` | — | 200 |
| GET `/schedulerEvents` | `type?` | `List<SchedulerEventWithCustomerInfo>` (customer users: own customer only) |
| GET `/schedulerEvents?pageSize&page` | `textSearch?, sortProperty?, sortOrder?, type?` | `PageData<SchedulerEventWithCustomerInfo>` |
| GET `/schedulerEvents?startTime&endTime` | `type?, textSearch?` | `List<SchedulerEventWithCustomerInfo>` incl. `timestamps` (calendar) |
| GET `/schedulerEvents?schedulerEventIds=a,b` | — | `List<SchedulerEventInfo>` (per-entity post-filter) |

**Dropped from PE:** `GET /scheduledReports` (reports coupling); `POST/DELETE /edge/{edgeId}/schedulerEvent/{id}`, `GET /edge/{edgeId}/(all)schedulerEvents` (edge).

Audit wrapper: `service/entitiy/scheduler/TbSchedulerService` + `DefaultTbSchedulerService` (`extends AbstractTbEntityService`) — save/delete + `logEntityAction` (ADDED/UPDATED/DELETED), so mutations hit the audit log like every other TB entity.

`BaseController` (core-file touch): `schedulerEventService` field, `checkSchedulerEventId`, `checkSchedulerEventInfoId`, `case SCHEDULER_EVENT` in `checkEntityId`.

---

## 8. Security

CE permission model (WL-precedent adaptation of PE's RBAC):

- `Resource.SCHEDULER_EVENT(EntityType.SCHEDULER_EVENT)` in `application/…/security/permission/Resource.java`
- `TenantAdminPermissions`: standard tenant-entity checker (full access to tenant's events)
- `CustomerUserPermissions`: standard customer-entity checker (access only to events with the user's `customerId`)
- SYS_ADMIN: no entry → no access (PE parity; scheduler is tenant-level)
- UI hides the `generateReport` event type from CUSTOMER_USER (PE parity)

---

## 9. UI

New module `ui-ngx/src/app/modules/home/pages/scheduler/` + `ui-ngx/src/app/shared/models/scheduler-event.models.ts` + `ui-ngx/src/app/core/http/scheduler-event.service.ts` (all additive):

- **Route:** `/features/scheduler` (PE parity), auth `TENANT_ADMIN, CUSTOMER_USER`, registered via the module's own routing + `home-pages.module.ts` export (reporting/WL precedent)
- **Table view:** CE `entities-table` component — columns: created time, name, type, customer, enabled (inline toggle); row actions: edit, delete
- **Calendar view:** FullCalendar (`@fullcalendar/angular` + daygrid/timegrid/interaction packages — new `package.json` deps) rendering occurrences from the time-window endpoint; month/week/day; click-through to the event dialog
- **Event dialog:** name / type (from the config-type registry) / enabled / customer assignment → schedule editor (timezone select, start date-time, repeat editor covering all 7 repeat types + endsOn) → per-type configuration form:
  - `updateAttributes`: originator picker, scope (`SERVER_SCOPE`/`SHARED_SCOPE`), attributes key-value editor
  - `sendRpcRequest`: device picker, method (required, no whitespace), params JSON, oneway/timeout/persistent
  - `updateFirmware`/`updateSoftware`: device or device-profile picker + compatible OTA package selector
  - `generateReport`/`generateDashboardReport`: raw-JSON config editor with an info banner — "Report generation activates when the Reporting module is installed." Selectable + persisted now; native forms arrive with the Reporting forward-port. `generateReport` removed from the type map for CUSTOMER_USER
  - custom type: free msgType + msgBody + metadata editors
- **i18n:** the full 126-key `scheduler` section from the PE bundle copied into `locale.constant-en_US.json`
- **Core-file touches:** `menu.models.ts` — MenuId `scheduler` + section entry (name `scheduler.scheduler`, path `/features/scheduler`, icon `schedule`) + two menu placements replicated from the PE bundle: TENANT_ADMIN inside the `features` ("Advanced features") section immediately after `version_control`; CUSTOMER_USER as a top-level entry after `notifications_center`. Locale JSON, `entity-type.models.ts` (SCHEDULER_EVENT translations/resources), `home-pages.module.ts`, `package.json` (+ `yarn.lock` regen). `entity.service.ts` is touched only if entity-name resolution requires it during implementation — if so, it gets its own ledger row

---

## 10. Error Handling

- Engine: per-event try/catch; failures logged (ERROR, with ids) and re-armed — a failing event never stalls the scheduler or other events
- `passedAway` skip at partition load → WARN
- Corrupt `schedule` JSON → ERROR at `toDescriptor()`; event stays dormant instead of crashing the partition load
- Unknown timezone → silent UTC fallback (PE parity)
- Validator (§4) rejects malformed events at save time with standard `DataValidationException` → HTTP 400
- REST: CE's standard `handleException` path; permission failures → 403

---

## 11. Deviations from PE (complete list)

| # | PE | Ours | Why |
|---|---|---|---|
| 1 | `ToCoreMsg.schedulerServiceMsg = 5` | field **11** | 5 occupied in CE; internal-only |
| 2 | `SchedulerEventInfo implements HasOwnerId` | dropped | CE has no owner model |
| 3 | OTA `ENTITY_GROUP` originator branch (+ `EntityGroupService`, `DeviceGroupOtaPackageService`) | dropped | CE has no entity groups |
| 4 | `generateReport` → `JobManager.submitJob(Job.newReportJob(...))` | `SchedulerReportExecutor` seam, no-op impl | CE `JobType` lacks REPORT; inferrix-reporting will provide the bean |
| 5 | `/scheduledReports` endpoint, `ScheduledReportInfo`, 3 report DAO methods | dropped | PE-reports coupling |
| 6 | Edge sync (proto field 31, fetcher, processor, 4 endpoints, 5 DAO methods, `EdgeEventType.SCHEDULER_EVENT`) | dropped | Scope decision; see §14 for the phase-2 path |
| 7 | EDQS fields/processor, `query.SchedulerEventFilter`, VC export/import | dropped | Scope decision |
| 8 | `reports.scheduler.min_interval` | `scheduler.min_interval` | Reporting doesn't own our scheduler |
| 9 | PE RBAC (`common/data` Resource + group permissions) | CE `application` Resource + tenant/customer checkers | CE permission architecture |
| 10 | Sparse logging (engine trace/debug only; DAO and controller silent) | Fire at INFO / skip at WARN, plus DEBUG flow logging in every ported class: DAO finder entry/exit-count + save/delete INFO, validator warn-before-reject, controller endpoint-entry with user/tenant, queue-wiring traces, UI console.error handlers on every side-effect subscribe. Pure repeat-math classes stay log-free (called in tight loops); their flow is visible from callers | Debuggability requirement: every flow must be traceable and errors catchable from logs alone |
| 11 | Solution-templates installer wiring | dropped | No solutions service in CE |

Everything else — models, schedule semantics, DDL, DAO queries, engine lifecycle, TbMsg construction, REST paths/payloads, UI structure and i18n — is PE-identical.

---

## 12. TB-Core Files Modified (ledger preview)

Backend (15): `EntityType.java`, `EntityIdFactory.java`, `TbMsgType.java`, `queue.proto`, `schema-entities.sql`, `schema-entities-idx.sql`, `schema_update.sql`, `ModelConstants.java`, `Resource.java`, `TenantAdminPermissions.java`, `CustomerUserPermissions.java`, `BaseController.java`, `EntityStateSourcingListener.java`, `DefaultTbCoreConsumerService.java`, `thingsboard.yml`.
Frontend (5–6): `menu.models.ts`, `locale.constant-en_US.json`, `entity-type.models.ts`, `home-pages.module.ts`, `package.json` + `yarn.lock` (+ conditionally `entity.service.ts`).

Each gets a row in a new `## Feature: Scheduler` section of `INFERRIX-PATCHES.md` with insertion anchors + merge-recovery notes, added in the same commit that touches the file. The Bucket-B merge-guard grep gains `scheduler_event|SchedulerEvent|MenuId.scheduler` (not bare `scheduler` — upstream uses it heavily). `menu.models.ts` and `locale.constant-en_US.json` become triple-touched — flag them as the top-2 merge-risk files.

New additive files carry the ThingsBoard Apache-2.0 license header (they live in TB-core paths and are checked by `license-maven-plugin`).

---

## 13. Testing Strategy (TDD)

Backend-first, red-green within each work package. Tests are written from the PE-observed semantics in this spec *before* porting each class.

1. **Repeat math** (pure unit, `common/data`): every repeat type's `getNext` against fixed timestamps and timezones — DST spring-forward/fall-back, month-end stepping (Jan 31 start), weekly day-mask wraparound, `EVERY_N_*` stride, TIMER unit conversion, `endsOn` exhaustion → 0, `passedAway` boundaries, one-shot semantics, descriptor JSON round-trip
2. **Validator** (unit, mocked deps): every reject path in §4
3. **DAO** (CE JPA test harness): CRUD round-trip incl. 10 MB configuration, unique-constraint mapping, filter/paged/text-search queries, customer-info join + public flag, time-filter timestamp computation, deleteByTenant/Customer cascades
4. **Engine** (unit, mocked cluster/DAO/OTA): partition add → load/skip/schedule; partition remove → cancel/evict; queue-msg add/update/delete; per-type dispatch asserting exact TbMsg (type, originator, metadata incl. defaults, data, RPC expiration/originServiceId); OTA device + device-profile paths; report-seam invocation; stale-delete guard; re-arm after firing error
5. **REST** (CE `AbstractControllerTest` harness): CRUD, enable/disable, all listing shapes, permission matrix (tenant admin full / customer user scoped + forced customerId / sys admin 403), validation errors → 400, audit-log entries on mutations
6. **UI:** `yarn build` type-check (Node 22) + runtime walkthrough on the dev server (create each event type, verify table/calendar/dialog, verify fired messages in rule-chain debug)

**Definition of done:** all new tests green; `mvn install` green on the touched modules (common data + proto, `dao`, `application`) incl. the license-header check; `yarn build` green; end-to-end: a TIMER event firing `updateAttributes` visibly routed in the tenant root rule chain, and enable/disable/delete take effect without restart.

---

## 14. Future Phases

1. **Report wiring:** forward-port inferrix-reporting → implement `SchedulerReportExecutor` there (parse PE `ReportConfig` from `configuration`) + native report-config UI forms; consider a rule node consuming `generateReport`-typed messages for `generateDashboardReport`
2. **Edge sync:** `SchedulerEventUpdateMsg` in `edge.proto` at `DownlinkMsg` field **38+** (PE's 31 is occupied in CE), `EdgeEventType.SCHEDULER_EVENT`, edge processor/fetcher/constructor utils, the 4 edge REST endpoints + 5 DAO methods — plus matching edge-binary work, without which cloud-side sync is inert
3. **EDQS / dashboard aliases:** `SchedulerEventFields`, EDQS query processor, `EntityFilterType.SCHEDULER_EVENT`, `query.SchedulerEventFilter`, repository CASE branches
4. **Version control:** `SchedulerEventExportData` + export/import services (originatorId externalization)
5. **Possible Inferrix extension (not PE parity):** CRON repeat type — PE 4.2 lacks it; the polymorphic `SchedulerRepeat` design accepts it cleanly if ever needed
