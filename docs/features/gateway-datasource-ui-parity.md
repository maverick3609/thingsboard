# Gateway data sources: matching the stack's own configuration UI

**Status:** planned, not started. Phase G7.
**Measured against:** stack 5.1.0 on `192.168.221.7:8443`, gateway `Inferrix Gateway 155`
(`86d5e330-b735-11f1-b695-2b2fc11a4c69`), live schema document read 2026-09-24.

Cortex builds every gateway form from the schema the gateway publishes. That is the right
foundation and the plan does not change it — it is what makes a form exist at all for a type
nobody transcribed, and it is the security boundary the whole feature rests on. What the schema
does not carry is presentation: which fields matter, what order they go in, what they are called,
and when they apply. The stack's own webapp carries that in 50 hand-written Angular components.

This document is the measurement, the approach, and the phases.

## What is actually different

Numbers below are counted, not estimated. The script is `docs/features/scripts/ds-ui-gap.py`.

| | count |
|---|---|
| data source types the gateway declares | 63 |
| of those, with a hand-written form in the stack webapp | **50** |
| ...with **no** stack form at all | **13** |
| stack dispatch entries for types this gateway does not declare | 3 |
| point locator types declared | 61 |
| configurable properties across the 50 types with a stack form | 277 |
| of those, shown by the stack's own forms | **171** |
| ...never shown by the stack | **106** |
| distinct property names across data sources and locators | 179 |

"Configurable" means after the fields Cortex's form already drops — identity, the four in
`GATEWAY_DATA_SOURCE_HIDDEN_FIELDS`, and the gateway-reported description fields. So the
comparison is like for like: on these types Cortex renders roughly **1.6x** the fields the stack
does, and the excess is concentrated, not scattered.

The 13 with no stack form are `ASSET_TRACKING_BAND`, `LED_ASSET_TAG`, `LIGHT_DI_CONTROLLER`,
`MESH_EXTENDER_MESH_NODE`, `MESH_SWITCH`, `MOKO_BAND`, `POE_LIGHTING_MESH_NODE`, `SENSOR_TAG_IAQ`,
`SENSOR_TAG_LUX`, `SENSOR_TAG_STROKE_COUNT`, `SENSOR_TAG_TH_OLD`, `SENSOR_TAG_TH_SHT21`,
`STUDENT_ASSET_TAG_MESH_NODE`. For these Cortex's schema-driven form is the only form that exists
anywhere — the stack's own UI cannot configure them.

### The 50 forms are not 50 layouts

Around 20 of them delegate their entire Properties tab to one shared component,
`app-sensor-datasource-form` (`components/common/mesh-nodes-datasource-form/`), which is seven
fields: name, xid, edit permission, address, anchor node, location, zone. The work is not 50
layouts. It is:

- **1** shared mesh-node/sensor layout, covering ~20 types
- **~10** rich protocol layouts. Laid out as whole forms — identity fields included, which is
  what the work actually is — they run `MODBUS_SERIAL` 25 fields, `MODBUS_IP` 24, `SNMP` 23,
  `MQTT` 15, `SCRIPTING` 13, `OPC` 11, `POE_LIGHTING` 10, `HTTP_JSON_RETRIEVER` 9, `BACNET_IP` 8,
  `BACNET_MSTP` 8.
- **~20** small layouts of four to eight fields

### The gap is narrower than "our form looks nothing like theirs"

Taking `MODBUS_IP.DS`, the worst case, side by side:

```
stack                          Cortex (schema order)
  name, editPermission, xid      alarmLevels          <- stack never shows
  timePeriod + type              quantize             <- stack never shows
  maxConcurrentConnections       timePeriod           (as a nested fieldset)
  timeout                        timeout
  retries                        retries
  lingerTime                     multipleWritesOnly
  scaleFactor                    contiguousBatches
  maxBackOffPeriod + type        createSlaveMonitorPoints
  multipleWritesOnly             maxReadBitCount
  contiguousBatches              maxReadRegisterCount
  createSlaveMonitorPoints       maxWriteRegisterCount
  maxReadBitCount                discardDataDelay
  maxReadRegisterCount           logIO
  maxWriteRegisterCount          ioLogFileSizeMBytes  <- stack never shows
  discardDataDelay               maxHistoricalIOLogs  <- stack never shows
  logIO                          transportType
  transportType                  host                 } always shown, whatever
  host                           port                 } the transport is
  port                           encapsulated         }
  encapsulated                   lingerTime
                                 scaleFactor
                                 maxBackOffPeriod     (as a nested fieldset)
                                 maxConcurrentConnections
```

The middle of both lists is already the same, because the schema's property order is the Java
class's declaration order and that is what the stack's author was reading too. Five real
differences fall out:

1. **Fields the stack hides.** Four on `MODBUS_IP`, 106 across all types — and they are far from
   evenly spread. `alarmLevels` is on **all 63** data source types and the stack shows it on
   none; `quantize` is on 18 and likewise never shown. Both sort to the top, so every form in
   Cortex opens on one or two fields the stack's operator has never seen. On the locator side
   `dataType`, `settable` and `relinquishable` are on 61 of 61.
2. **Nested objects.** `timePeriod` is `{timePeriod, timePeriodType}` and renders as a fieldset
   titled "Time period". The stack renders it as two inline fields, "Polling interval" and
   "Polling interval type". Same for `maxBackOffPeriod`. This is the most visible difference on
   every type, because almost every data source has a polling period.
3. **Conditional visibility.** `host`/`port`/`encapsulated` apply to one `transportType`;
   `ioLogFileSizeMBytes`/`maxHistoricalIOLogs` apply only when `logIO` is on. All are always shown.
4. **Grouping.** The stack's connection-tuning fields sit together; ours are split by the
   declaration order.
5. **Labels — 16 of them.** See below; this one is much smaller than expected.

### Labels are nearly right already

The gateway serves its own UI label dictionary at `GET /rest/v2/dictionary/ui/{module}`,
unauthenticated, ~1,228 distinct keys across 26 modules. Comparing every schema property name
against it:

| | count |
|---|---|
| Cortex's `humanise()` already produces the stack's exact label | **101** |
| differs | **16** |
| property has no dictionary entry at all | 62 |

The 16 that differ are the ones worth having, because most carry information the property name
does not:

```
timeout                  -> Timeout (ms)
offset                   -> Offset (0-based)
discardDataDelay         -> Discard data delay (ms)
registerCount            -> Number of registers
charset                  -> Character encoding
createSlaveMonitorPoints -> Create device monitor points
multipleWritesOnly       -> Use multiple write commands only
contiguousBatches        -> Contiguous batches only
maxRequestVars           -> Maximum vars per request
privPassphrase           -> Privacy passphrase
privProtocol             -> Privacy protocol
brokerUri                -> Broker Url
logIO                    -> Log I/O
maxHistoricalIOLogs      -> Max Historical IO Logs
binary0Value             -> Binary 0 Value
enabled                  -> Profile Push Enabled
```

A naive last-segment lookup against the dictionary resolves 65% of property names but is
**ambiguous and sometimes wrong** — `multiplier` resolves to a BACnet key on a Modbus point. The
dictionary is a label source, not a mapping. The mapping has to come from the stack's templates,
which pair each `[(ngModel)]="model.<prop>"` with the exact `UIDICTIONARY.get('<key>')` in the
same form field.

### The point form is worse than the data source form

This was measured last and should have been measured first. Opening one data point of a
`VIRTUAL.DS` source on the live gateway renders **about thirty controls**. The stack's own form
for the same point is **thirteen**, and they are not a subset — the stack leads with what the
point is (name, xid, data type) and then the eight fields specific to a virtual point.

`DataPointModel` carries ten configurable fields beyond identity: `enabled`, `deviceName`,
`purgeOverride`, `purgePeriod`, `textRenderer`, `loggingPropertiesModel`, `readPermission`,
`setPermission`, `settable`, `extendedName`. The stack's virtual point form shows **none** of
them. Two — `textRenderer` and `loggingPropertiesModel` — are nested objects that expand into
panels of their own, which is how ten fields become thirty controls: "Logging properties model"
appears as a panel heading, which is a Java class name, above Tolerance, Discard extreme values,
Discard low limit, Discard high limit and Cache size.

One is a defect rather than noise. **`settable` is declared on both `DataPointModel` and on the
locator**, so the form renders two switches with the same label writing two different fields, and
the operator has no way to tell which one the gateway honours. Across all 100 points on the live
gateway the two **always agree** — 56 false/false, 44 true/true, no mismatch — so the gateway
derives one from the other and has never produced the disagreeing state the form allows an
operator to save. The stack's own point form binds the locator's, so that is the one to keep and
the model's is the one to drop.

Locator sizes themselves are modest: `MODBUS.PL` 19 properties, `META.PL` 17, `VIRTUAL.PL` 15,
median across all 61 is **6**. The point form's bulk is the shared model, not the protocol.

## Approach

**One renderer plus a layout descriptor per model type. Do not write 50 components, and do not
extend `tb-dynamic-form`.**

Three things were settled by measurement before any code was written.

**Fifty hand-written components is what the stack did**, and it is why 13 of its own types have
no form and 3 of its dispatch entries point at types that no longer exist. Transcribing that into
Cortex buys a copy of a maintenance problem: every field the stack adds is a field our copy
silently lacks, with nothing to detect it.

**The schema is not the problem — presentation is.** It already carries types, ranges, enums,
`readOnly`, `writeOnly`, nesting, and the guarantee that a field Cortex renders is a field the
gateway declared. That is the security boundary; `inferrix-gateway-schema.models.ts` documents it
as such and a hand-written form bypasses it. A layout may therefore only *hide* a field, *narrow*
a free string to a fixed option list, or *move* a field down the page. It never invents one.

**`tb-dynamic-form` cannot produce a ThingsBoard entity form**, which is the second half of what
this pass is for. Its `toPropertyGroups` packs two fields into one row only when they share a
label, and the result is the label-left row of a *widget settings panel*. A ThingsBoard entity
form — `oauth2/clients/client.component.html` is the canonical one — pairs two independently
labelled fields inside `tb-form-row tb-standard-fields` and puts its optional settings behind a
`configuration-panel`. So: a new `tb-gateway-form`, which lays out the scalar types itself in that
markup and hands arrays, nested objects, dates and images back to `tb-dynamic-form` so there is
one implementation of each of those editors.

**Where the two UIs disagree:** fields, order and labels come from the stack; components and
layout come from ThingsBoard.

The stack webapp is read-only to us, same as the stack itself. Defects found in it while matching
a form are written up as open items in `inferrixstack-webapp/docs/` and handed over — see
**Handed over** below. The one Cortex-side backend change still outstanding is adding
`/v2/dictionary/ui/*` to the proxy allowlist in `InferrixGatewayController`, a read-only route and
ours to add.

### What a layout can say

`GatewayFormLayout` in `shared/models/inferrix-gateway-layout.models.ts`:

| Key | Meaning |
|---|---|
| `hidden` | Never rendered — edited elsewhere, or has no effect |
| `advanced` | Moved into the collapsed **Advanced** panel |
| `options` | A fixed option list for a property the schema declares as a bare string |
| `gatedOptions` | An option list chosen by another control's value, with a fallback type |
| `visibleWhen` | Rendered only while another control holds one of these values |
| `rows` | Explicit pairing, placed where its first field would have fallen anyway |

Everything else falls out of one default: **scalars pair two to a row in schema order, and
anything else takes a row of its own.** `VIRTUAL.DS` needed no keys at all because of it.

**A model type with no layout renders exactly as it did before**, through `tb-dynamic-form`. That
is what makes this sequence safe to do one type at a time — and it is why a type that needs no
overrides still gets an empty `{}` entry, to mark it as worked through.

## Sequence

One data source at a time. A type is done when its **data source form and its data point form**
both match the stack's fields, order and labels, are laid out the ThingsBoard way, and have been
opened against the live gateway and read.

Before a type is started, its rules are read out of the gateway's **Java**, not out of the stack
webapp — the webapp is a second opinion, and W11/W13 below are what happens when only it is
consulted.

| # | Type | Locator | Status |
|---|---|---|---|
| 1 | `VIRTUAL.DS` | `VIRTUAL.PL` | **done** — 2026-09-25 |
| 2 | `VIRTUAL_MESH_NODE.DS` | `VirtualMeshNodePointLocatorModel` | next; 3 live on the bench gateway |
| 3 | `INTERNAL.DS` | `INTERNAL.PL` | 1 live |
| 4 | `MESH_CONTROLLER.DS` | — | 1 live |
| 5 | `MODBUS_IP.DS` | `MODBUS.PL` | largest locator (19 fields); needs an instance created on the gateway first |
| 6 | `MODBUS_SERIAL.DS` | `MODBUS.PL` | locator shared with 5 |
| 7 | `BACNET_IP.DS` | `BACNET_IP.PL` | needs an instance first |
| 8 | `BACNET_MSTP.DS` | `BACNET_MSTP.PL` | `pointLocatorType` is null on 5.1.0 — falls back to a sibling point |
| 9 | `SNMP.DS` | `SNMP.PL` | |
| 10 | `MQTT.DS` | `MQTT.PL` | `writeOnly` secrets; empty must mean "unchanged" |
| … | the remaining ~40 | | mostly four to eight fields |
| last | the 13 types with no stack form | | left on the generic schema form — see Open decisions |

### 1 — `VIRTUAL.DS` / `VIRTUAL.PL` (done, 2026-09-25)

**Data source.** Already at field parity; only the layout was wrong. No layout keys needed.
Cortex shows one field the stack does not (`xid`, as identity) and keeps `alarmLevels` and
`quantize` behind Advanced, where the stack shows neither.

**Data point.** The large one: ~30 controls before, 5 after.

- `changeType` is `{"type": "string"}` in the schema, so it was a free text box. It is now a
  select whose ten values and four per-`dataType` lists come from
  `ChangeTypeVO.getChangeTypes(int)`.
- Each change-specific field is tied to the change types whose `*ChangeVO` declares it, so a
  brownian point shows `min`/`max`/`maxChange` and an attractor shows
  `maxChange`/`volatility`/`attractionPointXid`.
- `startValue` is a True/False list for a `BINARY` point and free text otherwise, matching
  `VirtualPointLocatorVO.getStartValue()`.
- The nine `DataPointModel` fields the stack's own form never shows moved to **Advanced**, except
  four that are hidden: `enabled` (the table toggle owns it), `readPermission`/`setPermission`
  (gateway-local permission strings), and `settable` — **`DataPointDao:184` writes
  `vo.getPointLocator().isSettable()` into the column and every runtime check reads the locator,
  so the model-level flag is dead** and showing both was two controls with one effect.
- `relinquishable` and `configurationDescription` are hidden on the locator: a virtual point is
  generated on the gateway, so there is nothing to relinquish to, and the description is a
  translation key the gateway will not take back.

**Verified.** Every `dataType`/`changeType` pair opened against `Inferrix Gateway 155` and its
visible fields compared to the VO. A save round trip created a throwaway point
(`NUMERIC`/`BROWNIAN`, min 0, max 100, maxChange 5, start 50), the gateway answered **201** and
echoed `dsEdit.virtual.changeType.brownian`, and the point was deleted again. Editing an existing
point through the form and changing nothing leaves its locator byte-identical, including the
hidden fields.

**Left for later, deliberately.** `attractionPointXid` is still a text box. The stack fills it
from `getDatapointsByTypeId(3)` — every numeric point on the gateway, not just this data source's
— and Cortex has no such call yet. It affects one field of one change type.

## Handed over

Defects found in the gateway's own webapp while matching its forms. Written up in
`inferrixstack-webapp/docs/2026-09-25-webapp-open-items.md`; nothing in that repository was
changed.

- **W11 (P1)** — a multistate virtual point cannot be configured at all. The template switches on
  `'MULTISTATE'`, a case its own dropdown can never emit (it emits `INCREMENT_MULTISTATE`), so the
  value list and roll flag never render and the point saves with an empty value set. Cortex uses
  the value the device defines, so this combination works here.
- **W12 (P3)** — `app-datapoint-properties` is referenced by no template; every point's retention,
  logging and text-renderer settings are unreachable from any data source form.
- **W13 (P3)** — the attractor form labels `maxChange` "Minimum Change" while the brownian form
  labels the same field "Maximum Change". `AnalogAttractorChangeRT:52` clamps to ±`maxChange`, so
  it is a ceiling and the brownian wording is right.

## Open decisions

Two calls that change the work and cannot be read out of the code. Both have been taken the way
the recommendation says, and both are reversible.

1. **The 106 fields the stack hides.** Hide them too — exact parity, and Cortex can no longer
   configure things the stack's UI cannot reach — or keep them behind an **Advanced** expander,
   where the common case is clean but nothing is lost? *Taken: expander.* Several of those fields
   are real (`alarmLevels`, `ioLogFileSizeMBytes`), and a gateway is not always reachable through
   its own UI.
2. **The 13 types with no stack form.** Leave them on today's generic schema form, or hide them
   from the type picker for parity? *Taken: leave them.* Removing a working form to match a UI
   that never had one is a loss.

## Superseded

The original phase list (G7.2 pipeline, G7.2b conditional fields, G7.3–G7.4 layouts by protocol
family, G7.5 the point form, G7.6 labels) cut the work by theme across all 63 types at once. It is
replaced by the per-type **Sequence** above, which finishes one data source and its points before
starting the next. Two pieces of it survive as cross-cutting work with no natural home in a single
type: the dictionary proxy allowlist (was G7.6) and an extractor that diffs the stack webapp per
release to catch fields it has gained (was G7.2). Neither is started.

**G7.1 shipped as planned** (`da58696668`): `alarmLevels` and `quantize` grouped under Advanced,
`timePeriod` labelled "Polling interval" with "Period"/"Unit" children, and 16 label overrides.
