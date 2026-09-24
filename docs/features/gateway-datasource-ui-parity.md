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

**Add a presentation layer over the existing schema-driven form. Do not write 50 components.**

Fifty hand-written components is what the stack did, and it is why 13 of its own types have no
form and 3 of its dispatch entries point at types that no longer exist. Transcribing that into
Cortex buys a copy of a maintenance problem: every field the stack adds is a field our copy
silently lacks, with nothing to detect it.

What the schema already gives us and must keep giving us: types, ranges, enums, `readOnly`,
`writeOnly`, nesting, and the guarantee that a field Cortex renders is a field the gateway
declared. That is the security boundary — `inferrix-gateway-schema.models.ts` documents it as
such — and a hand-written form bypasses it.

So: a **layout descriptor** per model type, holding only presentation, merged with the schema at
render time. Per type it carries an ordered field list, section grouping, a hidden set, a label
key per field, and conditional-visibility rules. A type with no descriptor renders exactly as it
does today, which is what keeps the 13 orphan types working.

The descriptor is **extracted, not transcribed**. A script reads the stack webapp source
(`~/Office/Product/inferrixstack-webapp/src/app/datasource/`), walks each component template in
document order, and emits a generated TypeScript file. Re-run it per stack release and diff the
output — that is how a new stack field gets noticed instead of silently missing.

The stack webapp is read-only to us, same as the stack itself. Nothing here changes stack code.
The one Cortex-side backend change is adding `/v2/dictionary/ui/*` to the proxy allowlist in
`InferrixGatewayController` — a read-only route, and ours to add.

## Phases

Each phase ends with a working, deployable UI. Field order and hidden sets are verified against
the stack's own form for the same type, on the live gateway, before the phase closes.

- **G7.1 — the rules that apply to every type** No descriptor and no extractor yet. Hide
  `alarmLevels` and `quantize`; render `timePeriod` and `maxBackOffPeriod` as paired inline
  fields labelled from their parent ("Polling interval", "Polling interval type") rather than as
  a fieldset called "Time period". Three rules, a few lines each, and they change the first rows
  of all 63 data source forms and all 61 locator forms. Do this before building any machinery:
  it is most of the visible difference, and it tells us how much of the rest is really left.
- **G7.2 — pipeline** Extractor + descriptor format + renderer merge. Scope: the shared mesh-node
  layout and the four types actually running on the bench gateway (`VIRTUAL`, `VIRTUAL_MESH_NODE`,
  `INTERNAL`, `MESH_CONTROLLER`). Proves the whole path end to end against something real.
- **G7.2b — conditional fields** The `*ngIf` rules the templates carry: `host`/`port`/
  `encapsulated` against `transportType`, `ioLogFileSizeMBytes`/`maxHistoricalIOLogs` against
  `logIO`.
- **G7.3 — the ten rich protocol layouts** Modbus IP and serial, SNMP, MQTT, BACnet IP and MSTP,
  OPC, scripting, HTTP JSON retriever, PoE lighting.
- **G7.4 — the remaining small layouts** ~20 types, mostly four to eight fields.
- **G7.5 — the point form** Raised in priority: it is further from the stack than any data source
  form. Resolve the duplicate `settable` first, then group the shared `DataPointModel` machinery
  (purge, text renderer, logging properties) the way G7.1 groups `alarmLevels`, so a point opens
  on what it is rather than on how it is logged. The 61 locator layouts follow, same descriptor
  and same extractor as the data source side.
- **G7.6 — labels** Proxy allowlist for the dictionary route; per-field label key from the
  extractor; fall back to `humanise()` when the gateway has no entry. Labels then track the
  gateway's own build and language rather than a snapshot.

## Open decisions

Two calls that change the work and cannot be read out of the code.

1. **The 106 fields the stack hides.** Hide them too — exact parity, and Cortex can no longer
   configure things the stack's UI cannot reach — or keep them behind an "Advanced" expander,
   where the common case is clean but nothing is lost? *Recommendation: expander.* Several of
   those fields are real (`alarmLevels`, `ioLogFileSizeMBytes`), and a gateway is not always
   reachable through its own UI.
2. **The 13 types with no stack form.** Leave them on today's generic schema form, or hide them
   from the type picker for parity? *Recommendation: leave them.* Removing a working form to
   match a UI that never had one is a loss.
