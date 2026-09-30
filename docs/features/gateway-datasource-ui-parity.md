# Gateway data sources: matching the stack's own configuration UI

**Status:** the type-by-type pass is **complete** — all 24 rows of the sequence below are done
as of 2026-09-30. What is still owed is the on-screen pass over rows 6-24, which no browser
tooling has been available for. The dialog save-path question is **decided and implemented** — see
"Saving a point that has nothing to save" below. Phase G7.
**Measured against:** stack 5.1.0, gateway `Inferrix Gateway 155`
(`86d5e330-b735-11f1-b695-2b2fc11a4c69`), live schema document read 2026-09-24. Later rows were
measured against 5.1.3.

> **`192.168.221.7:8443` and `localhost:8080` are the same instance**, established 2026-09-30 by
> comparing data source xids — all 12 UUIDs identical, which cannot coincide — and confirmed by
> `/v2/utilities/gw/serial-ports` answering with this Mac's device names
> (`/dev/cu.Bluetooth-Incoming-Port`, `/dev/cu.debug-console`, `/dev/ttyp0`). So every "live" and
> "measured" claim in this document was made against **a stack running on the development machine**,
> reachable both ways. That is the right thing to have measured against — it is a real 5.1.x gateway
> answering real REST — but it is not a production edge server, and nothing here should be read as
> having been exercised on one.

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
| `options` | A fixed option list, for a bare string or to relabel an enum the schema already declares |
| `gatedOptions` | An option list chosen by another control's value, with a fallback type |
| `visibleWhen` | Rendered only while another control holds one of these values |
| `rows` | Explicit pairing, placed where its first field would have fallen anyway |
| `readonly` | Shown but not editable, for a field the gateway's own provisioning owns |
| `provisionedPoints` | On a data source: the gateway creates its points, so no **Add** button |

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
| 2 | `VIRTUAL_MESH_NODE.DS` | `VIRTUAL_MESH_NODE.PL` | **done** — 2026-09-25 |
| 3 | `MODBUS_IP.DS` | `MODBUS.PL` | **done** — 2026-09-25 |
| 4 | `MODBUS_SERIAL.DS` | `MODBUS.PL` | **done** — 2026-09-25; data source only, locator shared with 3 |
| 5 | `BACNET_IP.DS` | `BACNET_IP.PL` | **done** — 2026-09-26; needs a local device on the gateway first |
| 6 | `BACNET_MSTP.DS` | `BACNET_MSTP.PL` | **done** — 2026-09-26; **D57 fixed and A22 answered yes** 2026-09-27, so points work; re-verify after the gateway upgrade |
| 7 | `META.DS` | `META.PL` | **done** — 2026-09-27; data type narrowed away from IMAGE 2026-09-28 (row 11's review); parked 2026-09-26 pending A23, answered and laid out the same day; needs an on-screen pass |
| 8 | `SNMP.DS` | `SNMP.PL` | **done** — 2026-09-26; data type narrowed away from IMAGE 2026-09-28 (row 11's review); **D60-D64 fixed** 2026-09-27, and verifying that turned up **D68-D70** and two fixes on our side; point form still needs an on-screen pass |
| 9 | `MQTT.DS` | `MQTT.PL` | **done** — 2026-09-28; **D71-D73** filed, and D73 forced a correction to the 2026-09-27 verb change; needs an on-screen pass |
| 10 | `HTTP_RECEIVER.DS` | `HTTP_RECEIVER.PL` | **done** — 2026-09-28; **D74-D75** filed; needs an on-screen pass |
| 11 | `HTTP_JSON_RETRIEVER.DS` | `HTTP_JSON_RETRIEVER.PL` | **done** — 2026-09-28; **D76-D81** filed, one of them general to all 18 polling types; needs an on-screen pass |
| 12 | `INTERNAL.DS` | `INTERNAL.PL` | **done** — 2026-09-28; **D83-D87** filed, one of them general to every create; needs an on-screen pass |
| 13 | `MESH_CONTROLLER.DS` | `MESH_CONTROLLER.PL` | **done** — 2026-09-29; **D90-D92** filed, D90 general to 34 mesh locator types and D92 to every type the Add menu offers; needs an on-screen pass |
| 14 | `PING.DS` | `PING.PL` | **done** — 2026-09-29; **D93-D95** filed, all three small; needs an on-screen pass |
| 15 | `POE_LIGHTING.DS` | `POE_LIGHTING.PL` | **done** — 2026-09-29; **D96-D100** filed, the first a credential readable two ways; needs an on-screen pass |
| 16 | `SCRIPTING.DS` | `SCRIPTING.PL` | **done** — 2026-09-29; **D101-D103** filed, the first a 500 on reading a row and writing it back; needs an on-screen pass |
| 17 | `SYSTEM_ATTRIBUTES.DS` | `SYSTEM_ATTRIBUTES.PL` | **done** — 2026-09-29; **D105-D107** filed, the last a pairing rule only the gateway's two front ends know; needs an on-screen pass |
| — | `OPC.DS` | `OPC.PL` | **addable and not done** — deferred by the user on 2026-09-29; the one type on the Add menu without a layout |
| 18 | `THERMOSTAT.DS` | `THERMOSTAT.PL` | **done** — 2026-09-29; **D108-D113** filed, D108 a P1 general to all 34 mesh locator types that supersedes D90; needs an on-screen pass |
| 19 | the 24 remaining mesh device types | their locators | **done** — 2026-09-30; batched at the user's direction; **D115-D117** filed and D109 widened; needs an on-screen pass |
| 19a | `CURRENT_SENSOR` | `CURRENT_SENSOR.PL` | **done** — 2026-09-30; the 27th member of the mesh family, added after the row-19 review; family source, own point form (`phaseId`, `ctId`); needs an on-screen pass |
| 20 | the 9 `*_MESH_NODE` types | their locators | **done** — 2026-09-30; one batch of ten with `VIRTUAL_MESH_NODE`, which was refactored into it; **D126-D127** filed; its attempted reversal of row 12 was withdrawn the same day; needs an on-screen pass |
| 21 | the 4 light controllers | their locators | **done** — 2026-09-30; the mesh-device point form exactly, over a source with a poll period; **D121-D124** filed, two of them P1/P2 on the gateway's own provisioning; needs an on-screen pass |
| 22 | the 3 asset tags | their locators | **done** — 2026-09-30; `address` alone on the source, the mesh device point form; `LED_ASSET_TAG` is the plainest D109 case in the file; needs an on-screen pass |
| 23 | the 2 Modbus slave shapes | their locators | **done** — 2026-09-30; neither is in the Add menu and **neither source can be saved at all** (D131), so the form opens read-only with the reason on it; new `unsavable` layout key; D131-D133 filed |
| 24 | `VIRTUAL_SWITCH.DS` | `VIRTUAL_SWITCH.PL` | **done** — 2026-09-30; a light-commissioning broadcast mirrored as a source; the point form is inert and its one field is hidden; D134-D140 filed |
| last | the 13 types with no stack form | | left on the generic schema form — see Open decisions |

**The "remaining ~40" this table used to carry was the wrong shape.** `/v2/data-source-types` — the
endpoint the Add menu reads — returns **16**, measured on 5.1.3, and the 16 are exactly the
definition classes whose `isEnabled()` returns true. The other 47 of the 63 published types exist
only because something on the gateway creates them: the mesh provisioner, a platform integration, a
light-commissioning run. Row 13 was the first of those, and it needed `provisionedPoints` and no add
path at all. After row 17 the addable list is **15 of the 16**: every type the Add menu offers has a
layout except `OPC.DS`, which the user deferred on 2026-09-29 and which is still on the menu. What
is left besides it is a long tail that each need an edit form only.

> An earlier version of this paragraph, the row-17 cell above and the row-17 commit subject all said
> the Add menu was complete, two lines above the sentence recording that OPC had been skipped.
> Measured: `GET /v2/data-source-types` returns 16 and `OPC.DS` is among them. Corrected 2026-09-29.

Order 3–11 set by the user on 2026-09-25: the protocols a real installation is wired with come
before the two that happen to be live on the bench gateway.

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

**`attractionPointXid` closed 2026-09-25**, by the per-type component described below — it is a
picker over every numeric point on the gateway, which is a lookup rather than a constant and so
could never have been a layout key.

### 2 — `VIRTUAL_MESH_NODE.DS` / `VIRTUAL_MESH_NODE.PL` (done, 2026-09-25)

The opposite shape to 1. Nothing on a mesh node is a setting: the node joins the Wirepas mesh, a
publisher provisions a row for it, and every field on both forms describes what the radio reports.
The work was therefore not choosing fields but *refusing to offer* them.

**Data source.** Two fields survive the existing strips — `controllerAddress` (the mesh address of
the controller the node answers to) and `publisherId` (the publisher that created the row). Both
are now **read-only**, which is what the gateway's own form does; it disables `editPermission`
alongside them, which Cortex drops entirely. Their schema descriptions carry through as hint
icons, so the form says why they cannot be changed.

**Data point.** All four locator fields read-only: `dataType`, `settable`, `attributeId`, `type`.
`relinquishable` and `configurationDescription` hidden, as on `VIRTUAL.PL` — the first because
`VirtualMeshNodePointLocatorModel.toVO` never reads it, so a value typed there is discarded in the
mapper before the gateway sees it.

`settable` is the one an operator acts on, and it is shown although the gateway's own form omits
it: it is what puts the set-value control on a point, and on this locator it is the live copy
(`DataPointDao:184` again). A DO is writable because it is a DO — worth seeing, not worth typing.

**No Add button.** With every locator field read-only there is nothing for an Add form to take,
and `AttributeDataType.valueOf(null)` in the mapper makes an empty locator an exception rather
than a validation error. `provisionedPoints` suppresses it. Edit, toggle and delete stay.

**Two bugs the renderer had, found by this type.** `tb-gateway-form` ignored `FormProperty.disabled`
entirely, so a `readOnly` field from the schema rendered as an editable control. It was
unreachable while `VIRTUAL.PL` was the only layout — its one read-only field is hidden — and would
have become reachable with the second. Fixed at the root: the renderer disables any property
carrying the flag, `form.enable()` puts them back afterwards, and the delegated branch passes it
down to `tb-dynamic-form`. The layout's `readonly` key sets the same flag rather than introducing
a second way of saying it.

**Verified.** Against `8 DDM Card - Slave 1` on `Inferrix Gateway 155`. Data source form: two
fields, both disabled, correct values (`11`, `1`), no Add button. Point form (`nullDO 2 - Status`,
attribute 10): four controls, all disabled, `BINARY` / settable / `10` / `BOOL` — matching what
REST returns for that point. A save was captured at the wire and **blocked before it left the
browser** (a provisioned point on live plant, and the question was whether a disabled control
survives): the payload is byte-identical to the stored point, `settable: true` included, and a
direct REST read afterwards confirms the gateway is untouched. `VIRTUAL.DS` and `VIRTUAL.PL`
re-checked for regression — unchanged, all controls still editable, Add still offered. Console
clean.

### 3 — `MODBUS_IP.DS` / `MODBUS.PL` (done, 2026-09-25)

The first real protocol, and the first locator whose fields depend on each other. Every rule below
came out of `ModbusPointLocatorVO`, `ModbusPointLocatorModel` and modbus4j 3.1.1's own locator
classes; the stack webapp agreed field for field, which made it a second opinion rather than the
source, and disagreed in three places that became W15–W17.

**Data source.** 23 fields: the 21 the stack shows, plus `alarmLevels` and `quantize`, which it
does not — kept behind Advanced under decision 1 below. Eight stay on the form: the poll
(`timePeriod`, `timeout`, `retries`), `createSlaveMonitorPoints`, and the connection
(`transportType`, `host`, `port`, `encapsulated`). The other fifteen move to **Advanced** —
register limits, I/O logging, socket and back-off tuning — of which thirteen are named by the
layout and two arrive already grouped by the mapper. The stack puts all 21 on one page, which is
why finding the host there takes a scroll.

`transportType` is the one enum given an explicit option list, and only to keep its labels: the
schema already declares it, and humanising a Java constant is right for `COIL_STATUS` and wrong
for `TCP`.

**Data point.** Six of the nineteen locator fields are hidden because nothing an operator types
into them reaches the device: `dataType` and `settable` are computed by the VO from the fields
above them, `rangeId` and `modbusDataTypeId` are derived getters no setter reads, and `toVO()`
never touches `relinquishable`. The other thirteen follow the register range:

| Range | Data types | Extra fields |
|---|---|---|
| COIL_STATUS | `BINARY` only | `writeType` |
| INPUT_STATUS | `BINARY` only | — |
| HOLDING_REGISTER | all 32 | `bit` \| `registerCount`+`charset` \| `multiplier`+`additive`+`multistateNumeric`, and `writeType` |
| INPUT_REGISTER | all 32 | the same, without `writeType` |

`writeType` follows `settableRange()` exactly — `range == 1 \|\| range == 3`, a coil or a holding
register, the two a master may write. `bit` is gated on the data type rather than the range, which
is the field that decides it; the cost is that it shows on a coil point, where the range has
already forced the type to `BINARY` and modbus4j ignores it.

**Three lists hardcoded rather than fetched.** The gateway serves its ranges, data types and write
types at `/v2/modbus/attributes/*` with translated names, and that route is **not** in the proxy
allowlist — confirmed live today, 403, the same answer as a route that does not exist. It does not
earn an entry the way `/v2/bacnet/object-types` does: those are per-install rows and these are a
compile-time `ExportCodes` table. So the 32 codes sit in the layout with a unit test asserting
they still match the Java, and `charset` gets `StandardCharsets` names instead of the stack's
"ASCII or RTU" (W15 — `Charset.forName("RTU")` throws). No backend change, no deploy.

**Gating the data type rather than greying it out.** The stack disables the picker on a coil range
but keeps whatever was selected, so a 4-byte float point switched to COIL_STATUS is stored as
NUMERIC and then throws `IllegalDataTypeException` when its locator is built — after the row
exists. Cortex clears it instead: one extra click on a range switch, and no way to save a locator
the device refuses to construct. W16 and D39.

**Two renderer bugs this type found, both fixed at the root.**

*The Advanced panel rendered blank.* TB's `.tb-form-row` carries `height: 100%`, and Material gives
an expanded panel body a definite height to animate to — so every row resolved that percentage to
the whole panel and only the first was on screen. Invisible with two advanced fields, obvious with
thirteen. The fix is the `<section class="tb-form-panel">` that ThingsBoard's own advanced panels
wrap their rows in, which this form had on its main body and not on its panel.

*An add posted `null` for every untouched field.* The form builds a control per schema property, so
an add sent `timeout: null`, and the gateway's defaults are Java field initialisers that Jackson
applies only when the key is **absent** — a null lands on a primitive `int` as 0. The first save
came back `422`: *"Must be greater than zero"* on four fields the operator never saw. Empty is now
dropped on an add and kept on an edit, which is the same rule `writeOnly` secrets already used and
fixes every protocol's add rather than this one's.

**Verified.** A throwaway `ZZ Modbus probe` created through the form on `Inferrix Gateway 155` and
deleted afterwards; the gateway is back at 12 data sources and 100 points. The source came back
with every gateway default applied (`timeout 500`, `retries 2`, `maxReadBitCount 2000`,
`maxReadRegisterCount 125`, `maxWriteRegisterCount 120`, `scaleFactor 1.5`, `lingerTime -1`) and
`enabled: false`, so it never polled. A point saved as HOLDING_REGISTER / FOUR_BYTE_FLOAT / device
3 / offset 40 / multiplier 0.1 / SETTABLE, and the gateway derived `dataType: NUMERIC`,
`settable: true`, `rangeId: 3`, `modbusDataTypeId: 8` — its own `configurationDescription` reads
"Device id 3, offset 40". Editing it wrote `additive: 2.5` and left `bit`, `registerCount` and
`charset` untouched, which is the check that a gate-hidden field still round-trips. All four ranges
and the three data-type families were stepped through on screen and showed exactly the fields in
the table above. `VIRTUAL.DS`/`VIRTUAL.PL` and `VIRTUAL_MESH_NODE.DS`/`VIRTUAL_MESH_NODE.PL`
re-checked for regression — unchanged, read-only still read-only, Add still suppressed. Console
clean.

### 4 — `MODBUS_SERIAL.DS` (done, 2026-09-25)

The same Modbus master over a serial line. Its points are `MODBUS.PL` — `/v2/data-source-types`
answers that for both — so this type is the data source alone, and the locator work done with
type 3 carries over untouched. Against `MODBUS_IP.DS` it drops eight socket fields (`host`,
`port`, `transportType`, `encapsulated`, `lingerTime`, `scaleFactor`, `maxBackOffPeriod`,
`maxConcurrentConnections`) and adds nine line settings.

**The line settings are not a convenience.** Five of them — `flowControlIn`, `flowControlOut`,
`dataBits`, `stopBits`, `parity` — are declared `{"type": "string"}` in the schema, and
`ModbusSerialDataSourceModel.toVO` converts each with `Enum.valueOf`/`fromName` *before* anything
validates. An absent one is therefore a null name, which is a `NullPointerException` on the
gateway rather than the `validate.required` message its own `ModbusSerialDataSourceDefinition`
was written to give. `encoding` is worse: `ModbusSerialDataSourceVO`'s constructor leaves it null
while setting the other five, so it is the one a caller is most likely to omit.

Measured live, posting a source with one field left out at a time:

| omitted | gateway answers |
|---|---|
| `encoding` | **500** Internal Server Error |
| `parity` | **500** Internal Server Error |
| `dataBits` | **500** Internal Server Error |
| `flowControlIn` | **500** Internal Server Error |
| `dataBits: "DATA_BITS_9"` (bogus, not absent) | 400 Bad Request |

So the layout's `defaults` are load-bearing, and they are seeded onto the model rather than shown
by the form — the same rule a point locator already followed, extended to the data source in
`GatewayDataSourcesComponent.add`. Every one is the value the VO's constructor starts on, except
`encoding`, where RTU is the framing a Modbus serial device speaks unless told otherwise.

**What goes where.** On the form: the poll (`timePeriod`, `timeout`, `retries`),
`createSlaveMonitorPoints`, then the line — `commPortId`/`baudRate`, `dataBits`/`stopBits`,
`parity`/`encoding`. Under **Advanced**: the same register limits and I/O logging as type 3, plus
`flowControlIn`/`flowControlOut` and `echo`. Flow control is `NONE` on every RS-485 bus and `echo`
is a property of the adapter rather than of the poll — real settings, but not why anyone opened
the form.

**Option lists.** Six of the seven come from the gateway's own `com.inferrix.serial` enums and
from `ModbusSerialDataSourceVO.EncodingType`; the gateway serves the same six at
`/v2/modbus/attributes/serial/*`, which the proxy does not carry for the reason type 3 gives —
they return `Enum::name` over a compile-time enum, so there is nothing per-install to look up.
What the layout adds is the labels: those routes return the constants raw, which is exactly what
the gateway's own dropdowns show, so an operator there picks between `DATA_BITS_5` and
`DATA_BITS_8` rather than between 5 and 8. `baudRate` is the one list taken from the stack's
webapp instead — it is a plain `int` the stack never bounds, so there is no enum to read, and its
`BAUD_RATES` constant is the thirteen rates an adapter is actually jumpered for.

**`commPortId` was a text box, and is a picker since 2026-09-30.** It came out as the paragraph
below predicted — one allowlist line, one service method and a subclass of `GatewayFormComponent`
— and the estimate was wrong only about the allowlist line, which also needed `/v2/utilities`
adding to `ADMIN_FAMILIES`. The list is the gateway's answer rather than a constant, so it comes from
`SerialDataSourceFormComponent.runtimeOptions()` over `GET /v2/utilities/gw/serial-ports` and not
from the layout's `options`. Verified against the route live: 200, a plain array of paths.

Worth the release because **nothing validates the value**: a wrong device path saves cleanly, the
source comes up, and it simply never reads — which presents as a wiring fault rather than a typo.
The names are not stable either, a USB adapter re-enumerating to a different `ttyUSB` number when the
ports are replugged, so an operator cannot carry one over from another install.

It stays a text box for a customer user, deliberately. `/v2/utilities` is tenant-admin only in the
allowlist, because a list of the host's devices is reconnaissance — the same judgement
`/v2/server/network-interfaces` already carries — so the component treats an error as "no list" and
leaves the field as it was.

> Corrected while doing type 6: this paragraph originally said BACnet MS/TP needed the same picker,
> so the two could be done together. It does not. An MS/TP data source has no serial settings at
> all — they are on `MstpLocalDeviceConfigModel`, which is a BACnet **local device**, a thing
> Cortex has no form for. `commPortId` on `MODBUS_SERIAL.DS` is the only field in the product that
> wants `/v2/utilities/gw/serial-ports`, so the picker is a release of its own after all.

**Verified.** A throwaway `ZZ Serial Probe` created through the form on `Inferrix Gateway 155`
and deleted afterwards; the gateway is back at 12 data sources and 100 points, and nothing was
enabled, so no port was ever opened. On add it came back carrying every gateway default
(`timeout 500`, `retries 2`, `maxReadBitCount 2000`, `maxReadRegisterCount 125`,
`maxWriteRegisterCount 120`, `ioLogFileSizeMBytes 1.0`) *and* all seven layout defaults
(`baudRate 9600`, `flowControlIn`/`flowControlOut` `NONE`, `DATA_BITS_8`, `STOP_BITS_1`,
`parity NONE`, `encoding RTU`). Reopening it showed each stored constant back as its label
(8, 1, None, RTU). An edit to `ASCII` / 19200 / 7 bits / even parity / RTS-CTS wrote
`encoding: ASCII`, `baudRate: 19200`, `dataBits: DATA_BITS_7`, `parity: EVEN`,
`flowControlIn: RTSCTS` and left `flowControlOut`, `stopBits`, `echo` and every tuning field
alone. All six dropdowns were opened and read on screen and carry exactly the values their enums
declare. The Advanced panel renders all ten of its rows — the type 3 `tb-form-panel` fix holds
for a second type. Console clean: no errors, no warnings.

### 5 — `BACNET_IP.DS` / `BACNET_IP.PL` (done, 2026-09-26)

The first type where the form is worth more than the gateway's own, and the first where a **data
source** needed a component rather than a layout.

**Three lookups, all already allowlisted.** `localDeviceConfig`, `objectTypeId` and
`propertyIdentifierId` are declared bare strings, and each is a key into a route the proxy already
carries — `/v2/bacnet/local-devices`, `/v2/bacnet/object-types`,
`/v2/bacnet/object-properties/{type}`. None can be a layout constant: the first is per-install
rows, and the third depends on the second. So `BacnetDataSourceFormComponent` and
`BacnetPointFormComponent` extend `GatewayFormComponent` in the shape `VirtualPointFormComponent`
established, and the layouts carry only what is declarative.

**The third list is the point of the exercise.** `/v2/bacnet/object-properties/{type}` reports,
per property, the data types it can be read as — which is the same list
`BACnetDataSourceDefinition.validate` checks `dataTypeId` against. Narrowing the picker to it makes
that rejection unreachable from the form. The gateway's own UI offers all five types for every
property and leaves the operator to discover the mismatch on save (**W25**). Measured live:
`present-value` on an analog input offers four, `object-name` offers one.

**A held data type the new property cannot serve is cleared**, not left to submit silently — the
same call taken for the Modbus range, and the opposite of what the gateway's form does. A held
*property* the new object type does not have is **substituted** rather than cleared, because an
empty one is not a validation message here: `toVO` calls `PropertyIdentifier.forName(...)` before
anything validates, so an absent property identifier is a 500. `present-value` is the substitute
where the type has one.

| omitted from a `POST /v2/data-point` | gateway answers |
|---|---|
| `propertyIdentifierId` | **500** Internal Server Error |
| `objectTypeId` | 400 Bad Request |
| `multiplier` | **201 Created**, stored `0.0` |

That last row is why `multiplier` is defaulted. `BACnetPointLocatorVO` starts at 1.0 and
`BACnetPointLocatorModel` declares the same field with no initialiser, so an absent key is 0 and
`BACnetDataSourceRT` applies `raw * 0 + additive` to every numeric read for the life of the point.
No error, no warning. `ModbusPointLocatorModel` carries the initialiser on the model and is
unaffected, which is how the difference was found. Filed as **D54 (P1)**.

**What goes where.** The data source is three fields — the poll, the local device, the COV
subscription timeout — and the point is the object address (`remoteDeviceInstanceNumber` /
`objectInstanceNumber`, then `objectTypeId` / `propertyIdentifierId`), its data type, the two
toggles and the scaling. `writePriority` appears only on a settable point: it is validated 1-16
whatever the point is, so it is still *sent* while hidden, and what the gate removes is a field
that decides nothing on a point nobody can write to. That gate is the first on a boolean, so
`visibleWhen.values` widened from `string[]` to `(string | number | boolean)[]` — `visible()` has
always compared against the control's own value rather than its label.

**A proxy bug of our own, found by using it.** `/v2/bacnet/local-devices/{id}` was allowlisted with
the numeric `ID` pattern, but a local device's key is a generated UUID. The list and the create
worked; `GET`, `PUT` and `DELETE` on any real id answered 403 from our own allowlist — so a local
device added through Cortex could never be read back, edited, or removed through it. Measured
against the live gateway: a numeric id answered 404 *from the gateway*, the real UUID answered 403
*from the proxy*. Changed to `XID`, which admits a UUID and still refuses a second path segment.
The route test asserted `/v2/bacnet/local-devices/3`, which is why it passed — it now asserts the
shape the gateway actually issues.

**Verified.** A throwaway local device (`ZZ Cortex probe`, UDP 47899 so it could not collide with
real BACnet traffic on 47808), a `ZZ BACnet Probe` data source and a `ZZ BACnet Point` created
through the forms on `Inferrix Gateway 155`, then deleted; the gateway is back at 12 data sources
and 100 points. The local device picker offered the one row, and the source came back holding its
UUID with `covSubscriptionTimeoutMinutes: 60` — the VO's own default, which is unreachable through
REST without the layout supplying it (**D56**). The point form opened on Analog input /
present-value / Numeric / multiplier 1 with `writePriority` absent, showed it as "16 (lowest)" the
moment Settable was turned on, and saved as `ANALOG_INPUT` / `present-value` / `NUMERIC` /
instance 1001 / object 3 / multiplier 0.1 / additive 2.0 / writePriority 16. The three lists
measured 1, 40 and 60 entries against the routes that serve them. Switching the property to
`object-name` narrowed the data types to Alphanumeric alone and cleared the held Numeric. The
gateway had been upgraded to stack 5.1.1 since type 4; the schemas of all seven previously laid-out
types were re-read and are unchanged. Console clean.

### 6 — `BACNET_MSTP.DS` / `BACNET_MSTP.PL` (done, 2026-09-26)

**The same models, so the same forms.** Resolving both schemas through their `allOf` chains gives
`BACNET_MSTP.DS` the fifteen fields of `BACNET_IP.DS` and `BACNET_MSTP.PL` the thirteen of
`BACNET_IP.PL` — the same names, the same types, no field either way. BACnet object types and their
properties are a property of BACnet, not of the transport that carries it, and what *is* per
transport — the line settings — lives on `MstpLocalDeviceConfigModel` (`commPortId`, `baudRate`,
`thisStation`, `retryCount`, `maxMaster`, `maxInfoFrames`, `usageTimeout`), which is a local device
rather than a data source. So `BACNET_DATA_SOURCE` and `BACNET_POINT` became shared layout
constants and both types point at them. No new layout, no new component.

**One addition: the picker has to know the transport.** `localDeviceConfig` is declared on
`BACnetDataSourceVO`, which both extend, and nothing on the gateway checks that a source's
transport agrees with the local device it names — `validate` only checks that the id resolves, and
`LocalDeviceFactory` builds whatever the config says. An MS/TP source naming an IP local device is
accepted and then quietly speaks BACnet/IP on a bus that is not there. So
`BacnetDataSourceFormComponent` took a `transport` input and filters the list; a row whose `type`
the gateway did not send is kept, because dropping a usable local device over a field this form
does not otherwise read would be worse than showing one row too many.

**`pointLocatorType` in the layout.** `/v2/data-source-types` answers `null` for this one type
alone, so `locatorType()` used to fall through to the type of a sibling point — which works for
every source that already has one and fails for the case that matters, a source getting its first.
The layout now names it, consulted between the gateway's published answer (still preferred, so a
fixed gateway wins immediately) and the sibling scan.

**And then the gateway refuses every point.** Any `POST /v2/data-point` on a `BACNET_MSTP.DS` row
that carries a locator answers **500**, whatever the locator is. Isolated with five probes of one
body: an MS/TP locator on an *IP* source gives a clean 422 naming the expected type, no locator at
all on the MS/TP source gives a clean 422 "Required value", and both an MS/TP *and* an IP locator on
the MS/TP source give the same 500 — which places the fault in the one step that needs the data
source's declared locator type, the `null` above. One missing declaration, both symptoms. Filed as
**D57 (P0)**, with **A22** asking whether MS/TP is meant to carry points at all; if the answer is
no, Cortex should say so on the data source instead of offering an Add point button.

Nothing conditional was added for it. A guard would have to come out again the moment D57 lands,
and supplying the locator type Cortex already knows is as far as it should go before A22 is
answered.

> **Both closed 2026-09-27, and nothing here has to be undone.** A22 is **yes** — MS/TP is meant to
> carry points, and the `null` was an unfinished stub rather than a decision, so the Add point button
> stays. D57 was one method: `BACnetMstpDataSourceVO.createPointLocator()` returned `null` and was
> *declared* `PointLocatorVO`, and the two symptoms came from those two facts separately — the
> declared type is what the types listing reads by reflection, and the returned value is what
> `pointLocatorBelongsToDataSource` dereferenced. This section's localisation was right about where
> and a little off about how: it read them as one fault rather than two. The separate guard asked for
> above is in as well, as a 422 naming the field.
>
> So the layout's `pointLocatorType` is now a fallback that never fires — `/v2/data-source-types`
> answers `BACNET_MSTP.PL` on the fixed build, and the gateway's own answer is consulted first. It
> costs one line and self-deactivates, so it stays until a gateway carrying the fix is the only one
> Cortex talks to. **Still owed: the on-screen pass for an MS/TP point, which was impossible before
> this and has not been done since — the gateway has not been upgraded yet.**

**A second proxy bug of our own, same shape as type 5's.**
`/v2/data-source/default-event-types/{type}` was allowlisted with `TYPE`, which forbids dots — and
every model type has one (`MODBUS_IP.DS`). The route was 403 for every input it can ever be given.
Its test asserted `default-event-types/ModbusIp`, a name the gateway does not use, which is why it
stayed green; it now asserts the real spelling, the MS/TP one, and that `../data-source` is still
refused. Fixed with a `MODEL_TYPE` pattern rather than by widening `TYPE`, which also serves
`/v2/event-detector-type/NUMERIC` and `/v2/bacnet/object-properties/ANALOG_INPUT`, neither of which
should admit a dot. Nothing in the UI calls the route yet, so this was latent rather than broken on
screen — the same kind of latent the type 5 bug was not.

**Verified.** A throwaway MS/TP local device (`ZZ Cortex MSTP probe`, `/dev/ttyUSB9`, a port that
does not exist) and a `ZZ Cortex MSTP probe` data source created through the form on
`Inferrix Gateway 155`, then deleted; the gateway is back at 12 data sources and 100 points. The
local device picker offered **only** the MS/TP probe and not the BACnet/IP one created for type 5,
which is the transport filter working. COV timeout prefilled 60, and the saved row came back with
`localDeviceConfig` holding the MS/TP UUID, `covSubscriptionTimeoutMinutes: 60`, `enabled: false`,
and the picker showing the device's label again on reopen. *(The two throwaway local devices
themselves outlived their rows — the data sources were deleted at the time but the devices were not.
Both were removed on 2026-09-30, checked first for references: no data source named either, and no
BACnet source existed at all. Zero local devices remain and the same 12 data sources.)* **Add point on a source with no points
rendered the BACnet locator form** — the case that is impossible without the layout's
`pointLocatorType`, since the gateway publishes `null` and there is no sibling to copy — with all
nine fields in order, Analog input / present-value / Numeric / multiplier 1, `writePriority` hidden
with Settable off, and 60 object types in the list. Saving it is what surfaced D57. Console clean
apart from that 500.

### 7 — `META.DS` / `META.PL` (done, 2026-09-27; parked 2026-09-26)

**Read before laid out, and then not laid out.** `META.PL` is a scripting locator: `script`,
`scriptEngine`, a `context` of other points bound to variable names, `updateEvent` /
`updateCronPattern`, and `scriptPermissions`. The data source itself is the emptiest in the product
— eleven fields, every one of them shared with every other data source, no protocol fields at all —
so the whole of this type is the point.

Three facts, in the order they were established:

1. `scriptPermissions` is a plain string on the REST model, `toVO()` turns it straight into the
   permission holder the script runs as, and `MetaPointLocatorVO.validate` is an empty method. The
   gateway stores what it is sent: measured live, a group name that exists nowhere came back
   verbatim on a 201.
2. That value is what decides how tightly the script engine is confined — the Nashorn definition
   asks the *script's* holder for its role and only strips the Java-access bindings in the confined
   branch.
3. **Cortex forwards `/v2/data-point` with no body inspection.** `bodyIsAllowed` matches
   `/v2/event-handler*` alone, because the one payload it was written for was the process handler.
   Cortex's allowlist excludes the script and certificate families by name, and this walks past that
   exclusion because from the outside it is a data point.

So the surface is already open and already on screen: META renders today through the generic
schema-driven form, `script` included. Nothing here opened it and nothing here has tightened it.

Filed as **D58 (P0, security)** with **A23** — is `META.PL` meant to be writable through the v2
data-point route at all? — and **D59** for three more bare-primitive defaults found on the way.

**Why parked rather than laid out.** A layout would decide, in passing, whether Cortex offers a
script editor for the gateway; that is not a layout decision. The three shapes a fix could take
each imply a different form: if the gateway stamps the holder from the authenticated user, the field
comes off the form entirely and META is an ordinary type; if it validates the field instead, the
form needs a picker and Cortex needs to know the caller's groups; if scripting becomes
administrator-only, Cortex should show a META point and refuse to author one. Guessing costs more
than waiting.

**Cortex's own gap, recorded here rather than in the stack docs, because it is ours:** the body
guard is per-route and was written around one payload. A `/v2/data-point` body carrying a
`META.PL`, `SCRIPTING.PL` or any other locator with a script in it is forwarded unread. Whether
that becomes a field-level guard, a locator-type refusal, or nothing at all depends on A23, so it
is not being fixed ahead of the answer — but it should not be discovered a second time.

> **Unparked 2026-09-27. A23 was answered the narrow way, deliberately**, and it resolves the
> question above: `META.PL` stays writable through `/v2/data-point` under the data-point permission,
> and the escalation is closed by *validating* the groups rather than by gating the route. So Cortex
> keeps forwarding the route unread and needs no body guard — a caller can no longer give a script
> reach they do not have, which is the property the guard would have been protecting. The wider answer
> (a permission of its own for scripting) sits cleanly on top later and needs nothing undone.
>
> D58 turned out smaller than this document claims, and the claim is worth correcting: it is **not**
> true that "nothing checks the caller holds what they asked for". `MetaDataSourceDefinition.validate`
> already called `permissionService.hasPermission(user, pl.getScriptRoles().getPermissions())` — in
> the right place — and **discarded the boolean**. The whole defect was an unused return value. Acting
> on it needed one real change beyond that: `hasPermission` splits its query on commas and answers
> true if *any* part matches, so the groups are now checked one at a time, or a caller holding
> `operators` could have asked for `superadmin,operators` and passed.
>
> D59 went further than asked: `logLevel` was defaultable too and was never measured here, and
> `variableName` **is** defaultable — this document was wrong to call it otherwise, since the VO seeds
> `my`. `context` stays required, and correctly so.
>
> **What the answer requires of the form**, quoted from the resolution: seed the four defaultable
> fields (`logSize`, `logCount`, `contextUpdateEvent`, `logLevel`) or leave them out — both work now;
> require at least one `context` entry with `contextUpdate` set when `updateEvent` is `NONE`; and
> expect a 422 on `scriptPermissions` naming any group the signed-in user does not hold.
>
> **What the form still cannot do with a layout**, found on re-reading the schema for this: `script`
> is `{"type": "string"}` with no `format`, so the mapper renders a JavaScript body in a **single-line
> text input**, and `context[].xid` is a point reference rendered as a free text box inside a
> delegated array. Neither is expressible in the layout language — it has no notion of overriding a
> rendered type, and no notion of a nested field at all. That is the open decision on this type; the
> rest of it is an ordinary layout.

**Laid out 2026-09-27, against a stack carrying the fixes.** Measured on
`inferrix-stack-v5.1.x` booted locally on `:8080` rather than over the LAN, since the deployed
gateway is on a network this machine cannot currently reach — so every number below is from the fixed
build, and the only thing still owed is the on-screen pass.

**`updateEvent` is the form, and `NONE` does not mean "never".** `MetaPointLocatorVO` registers
`UPDATE_EVENT_NONE = 0` — the same id as `UPDATE_EVENT_CONTEXT_UPDATE` — and gives it the message key
`dsEdit.meta.event.context`, "Context update". A point on it runs when a context point it flagged
changes. That is what makes `validate`'s requirement legible rather than arbitrary, and it is
conditional in a way neither the schema nor this document had noticed:

| `updateEvent` | `context` | response |
|---|---|---|
| `NONE` | one entry, `contextUpdate: true` | **201** |
| `NONE` | `[]` | **422** `context`: *"No points are set to update context"* |
| `NONE` | one entry, `contextUpdate: false` | **422** — same |
| `MINUTES` | `[]` | **201** |
| `CRON` + pattern | `[]` | **201** |

So the requirement belongs to `NONE` alone. It is **not** expressed on the form: a layout can hide a
row and narrow a list, and has no word for "this array needs an entry when that select holds this
value". The gateway's refusal names the field and says why, and the field's own hint — the schema's
words — says it before the operator gets there. Adding a validator for it is a change to make when an
operator asks for one.

`updateCronPattern` is gated on `CRON`, which is the one option of the eight that brings a field with
it. A pattern is only parsed on `CRON`: junk in it is accepted while the event is `NONE` (measured),
so the gate hiding a stale pattern costs nothing.

**Five fields hidden, for four different reasons.** `isSettable()` returns a hard `false` on this
locator, so a meta point never takes a write; `toVO` never reads `relinquishable`;
`configurationDescription` is generated from the script's first 40 characters (confirmed on the read
back — it comes out as `'return p1.value;'`); `scriptEngine` is a one-value enum with nothing to
choose. And `scriptPermissions` is hidden **as a decision**: it is what
`NashornScriptEngineDefinition.createEngine` reads to hand out the confined engine or the one with
Java access, and an omitted value now means no groups, which is the confined one. The stack validates
it against the caller's own groups, so this is not the security boundary — it is a browser-reachable
form declining to ask for Java access. An existing point keeps whatever it was given: the dialog
spreads the stored model under the form's values, so a hidden field is carried through a save
untouched. Verified end to end — a `PUT` of the read-back row with only `script` changed answers 200
with `scriptPermissions` intact.

**Two hidden fields are defaulted rather than dropped, and that is the load-bearing part.** Both
`dataType` and `scriptEngine` map through `ExportCodes.getId`, which answers **-1** for a value it
cannot find — and `getId(null)` finds nothing. A locator posted without either is accepted **201**
and reads back `null`, storing a data type and a script engine that are not constants. Filed as
**D65** (family-wide: 53 locator models make that call unguarded) and **D66**. The four the stack now
defaults itself — `variableName` (`my`), `logLevel`, `logSize`, `logCount` — are seeded anyway, so the
form shows what the gateway would have chosen; `contextUpdateEvent` likewise. `updateEvent` is seeded
because it is *not* defaultable: an absent one is a 422, `"Invalid value"`.

**The two things a layout could not say, and now can.** Both were the open decision above, and both
were built rather than deferred:

- **`types`**, a new layout key: `script` becomes a `textarea`. Six lines on the descriptor and one
  method on the renderer, and the type comes from our own table and never from the device. It only
  ever moves a field *between* the types the form lays out itself — naming a delegated array there
  would replace its editor with a control that cannot hold its rows, so such an entry is ignored
  rather than obeyed.
- **A looked-up option list on a field inside a delegated array.** `runtimeOptions()` gains a second
  key shape, `array.field`, and `META.PL.context.xid` becomes a select of every point on the gateway.
  The rewrite lands on the array property's own `properties`, which is what `tb-dynamic-form` clones
  per row (`toPropertyGroups`), so every row gets it including rows added afterwards. It is applied
  once, guarded on the list's identity, because `delegatedProperties[id]` has to keep its reference or
  the form inside it is rebuilt under the operator. `META.PL` therefore gets a per-type component, the
  third after `VIRTUAL.PL` and the BACnet pair — the whole of it is one HTTP call.

  A nested `xid` is a data point reference *everywhere* it appears in the schema document — a context
  variable, the point a detector watches, the point a published point publishes, whose own schema says
  so in as many words — so it is labelled "Point" in `NESTED_FIELD_LABELS` rather than per type.

**`META.DS` is `{}`.** Every field it declares is one the platform already strips: identity, the two
descriptions, and the four the points table owns. It has no poll period at all, because a meta point
is driven by its own `updateEvent`. The entry exists to mark the type as worked through.

**Verified.** 61 layout specs and 8 new renderer specs green — and karma *does* run in this repo when
`--include` narrows it to one spec file, which is worth knowing after months of assuming otherwise
(the circular-import crash is a property of the whole-bundle build, not of the runner). The exact body
the form posts on an add — the nine defaults plus a script and one context entry, with everything
`keep()` drops on an add left out — answers **201** against the fixed build, and the edit round trip
answers **200**. The probe source and its eighteen points were deleted afterwards; the instance is back
to the 21 data sources it started with.

### 8 — `SNMP.DS` / `SNMP.PL` (done, 2026-09-26)

**The version is the shape of the form.** `SnmpDataSourceDefinition.validate` branches on
`snmpVersion` and asks for a different set either side: v1 and v2c authenticate with a community
string, v3 with a security name, a context name and two protocols. Nineteen protocol fields on the
data source, ten on the point, and eleven of the nineteen belong to exactly one side of that branch.
So the whole type is one layout with `visibleWhen` doing the work — not two layouts, because it is
one model and an operator changes their mind about the version while filling the form in.

**A gate on a gated field reads a value nobody can see.** The two passphrases were first gated on
their own protocol, which is the more precise condition: `NONE` means there is nothing for a
passphrase to be, and it covered v1 and v2c for free since both protocols default to `NONE`. It was
wrong on screen. A gate hides a row and **keeps its control**, which is what lets a version switch
be reversible — so picking v3, then MD5, then going back to v2c left "Authentication passphrase" on
a v2c form with the protocol that summoned it hidden. Both passphrases are now gated on the version
like everything else in the v3 half, and a cross-cutting spec refuses any rule whose `by` is itself
gated. What it costs is an inert passphrase box on a v3 source configured for no authentication,
which is the rarer wrong thing; a compound condition would fix that too and has not been added for
one field pair.

**Both protocols are defaulted for every version, and that is the load-bearing default.** They are
`ReverseEnum`-backed on the model and `ReverseEnumMap.get` calls `Objects.requireNonNull`, so the
constructor that builds the response from the saved VO throws on a null. Measured: omitting either
answers **500** on a v2c source where neither means anything — and the row is written first, so it
then cannot be read (`GET` by xid 500s on the same path) and `DELETE` 500s while still deleting it.
Filed **D60 (P1)**. `NONE` is both what an operator would pick and what keeps the row legible.

**Relabelled, not narrowed, for the two protocols.** They are published enums, so the mapper already
offers the right values — what it cannot do is spell them: its humaniser lower-cases all but the
first letter, so `MD5` renders as "Md5" and `AES256` as "Aes256". An operator matches these against
their agent's own configuration, so the layout supplies the labels and leaves the values alone.
`snmpVersion` and `setType` are narrowed rather than relabelled: both are bare strings on the model,
mapped through a code table rather than an enum.

**`settable` is hidden on an SNMP point, because it is an answer.**
`SnmpPointLocatorVO.isSettable()` returns `setType != 0`, and `SnmpPointLocatorModel.toVO` builds a
fresh VO from seven fields — that is not one of them. A control for it would change nothing while
appearing to. `Not settable` on the set type is the same statement and the one the gateway reads.

| omitted from a `POST /v2/data-point` | gateway answers | stored |
|---|---|---|
| `multiplicand` | **201 Created** | `0.0` — every reading scaled to nothing |
| `setType` | **201 Created** | `null`, and `settable` reads **true** |
| `oid` | 422 `oid: Required value` | — |
| `dataType` | 422 `dataTypeId: Invalid value` | — |

`multiplicand` is BACnet's `multiplier` defect a third time. `setType` is worse:
`ExportCodes.getId(null)` returns **-1**, not 0, so a point saved without one reports itself
*writable* with a set type no SNMP type answers to. Both defaulted, both filed as **D63**.

**Two gateway defects make this type half-usable whatever the form does.** `SNMPv3 cannot be
configured through v2 REST at all` — `SnmpSettings.getSnmpVersionId("v3")` sets its local to 3 and
then maps only 0, 1 and 2, so it returns -1 and `validate` rejects it (**D61**, live-confirmed 422).
And `GET /v2/data-source` **omits every SNMP row**, although each is readable by xid (**D62**,
measured three times, with and without query parameters). The second is why the point form could not
be opened on screen: a data source Cortex cannot list is one no operator can click. v3 is still
offered in the picker, for the reason MS/TP's Add point button is still offered — a workaround would
come out again the moment the mapping is fixed, and the 422 at least names the field.

> **All five closed 2026-09-27, and the paragraph above has D62 wrong.** Nothing about that defect
> is SNMP-specific, and the rows that vanish are not the SNMP ones. `/v2/data-source` *streams* its
> array, so a throw from one row's `toModel` ended the array where that row was — the 200 and every
> row before it were already on the wire, and every row **after** it was lost too, whatever its type.
> The correct statement is **one unreadable row truncates the tail of the listing**, and the
> unreadable rows were exactly the null-protocol ones D60 describes. My own probe rows vanished
> because they sat behind those, not because of anything about SNMP; both alternatives this section
> offers as the likely cause are wrong. Fixed at both ends: D60 removes the throw, and a skipped row
> is now left out and logged at ERROR with its class and id while the walk continues.
>
> The rest, and what each means for Cortex:
>
> - **D61** — v3 is reachable. The version helper now asks `SnmpVersion.values()` instead of a
>   hand-written switch, so the option in the picker works and the next version added is reachable the
>   day it is declared. The reason for offering v3 anyway held.
> - **D64** — the four credentials carry `@JsonProperty(access = WRITE_ONLY)` and `@WriteOnlySecret`,
>   so the schema now publishes `writeOnly: true` and the gateway merges an absent value against the
>   stored one. **Cortex needs no change for this**: the mapper already password-types a `writeOnly`
>   field and the dialog already treats empty as "unchanged", and the two arrive together by
>   construction, so the blanking hazard this work worried about cannot occur. `readCommunity` is
>   included, which is right — for v1 and v2c it *is* the authentication.
> - **D60, D63** — the model initialisers are in, so the layout's `defaults` for the two protocols
>   and the four locator fields now agree with the model rather than rescuing it. They stay: they are
>   still what a new row should hold, they are what makes the form correct against a gateway that has
>   not been upgraded, and `defaults` only fill an absent key. D63 also gained a `setType` validity
>   check, which catches the misspelling a default cannot.
> - **A24** — the data-type message now names the client's field.
>
> **Still owed: the on-screen pass for an SNMP point**, which D62 made impossible and which the fix
> makes possible again. Needs the gateway upgraded, or simply the poison rows gone.

**One Cortex bug of our own, found by using the form, and it was never SNMP-specific.**
`saveDataSource` and `saveDataPoint` chose POST or PUT by whether the model carried an `xid`. The add
dialog offers the XID field — its own hint says "Leave blank to let the gateway generate one" — and
the gateway accepts a caller-chosen xid on a create, so an operator who typed one got a `PUT` to a
row that does not exist yet: 404, dialog closed, nothing saved, for **every** model type. Both
methods now take the intent from the caller, which reads it before the dialog can hand back an xid.
Fixed here rather than filed because it is two call sites, and because D62 makes an SNMP row created
without a chosen xid impossible to find again.

**Verified.** `ZZ UI SNMP probe` created through the form on `Inferrix Gateway 155` with a
hand-typed xid, read back and deleted; the gateway is at 12 listed data sources and 100 points. On
add it came back on v2c, port 161, trap port 162, timeout 1000, retries 2, with the community pair
shown and the eight v3 fields absent. Choosing v3 replaced the community pair with security name,
context name and both protocol/passphrase pairs; choosing MD5 and AES256 read "MD5" and "AES256";
going back to v2c restored exactly the community pair with no passphrase left behind. The Advanced
panel carries retries, timeout, trap port, maximum vars and local address. The saved row round
tripped with every default and both protocols at `NONE`, and the v3 fields null. The point form's
fields and defaults were exercised against the live gateway by REST rather than on screen, for the
D62 reason above. Console clean.

> **Re-verified 2026-09-27 against the fixed build, and it moved two things here.** D60-D64 are all
> in: `authProtocol` and `privProtocol` now carry real enums, the four credentials are `writeOnly`,
> and the nine invisible `ZZ …` rows are listable again, so D62's truncation is gone.
>
> **The `writeOnly` change makes the reviewer's earlier 🟡 the live case, and it was already handled —
> but it also exposed a defect that was not.** The rebuttal to that review rested on a measurement:
> on 5.1.1 `readCommunity` came back in full, so an empty form field could not overwrite a stored
> secret. On the fixed build it comes back **absent**, so `keep()`'s password handling — which was
> already there — is now load-bearing rather than belt-and-braces. What the rebuttal did not
> anticipate is the other end of the round trip: `DatasourceResource.update` answers a `PUT` with
> `service.update(xid, model.toVO())`, a **fresh** VO from the body alone. A key the body omits is
> stored as `null`. So dropping the empty secret from the payload — the correct client behaviour, and
> the only one available, since the GET will not return it — was **erasing the credential** on every
> edit and answering 200.
>
> Filed as **D68 (P0)**. Cortex's half is fixed here: an update is now a `PATCH`, at both
> `saveDataSource` and `saveDataPoint`. `PATCH` resolves through `PartialUpdateArgumentResolver`,
> which maps the stored VO to a model — `fromVO` copies the secrets — and applies the body over it
> with `readerForUpdating`, so an absent key keeps what the gateway holds. Measured: a `PATCH` body of
> `{modelType, name}` alone answers 200 with every other field intact. Nothing else about the save
> changes, because the dialog already sends every non-secret key explicitly on an edit.
>
> This is not SNMP-specific and it is why it was worth chasing before row 9: `MQTT.DS`'s broker
> password, `OPC.DS`'s and the HTTP retriever's auth are all `writeOnly`, and MQTT is next.
>
> **A second fix, on the v3 add form.** `contextName` is refused when the key is *absent* — 422,
> "Required value" — and accepted when it is `""`; `engineId` is the exact reverse (absent fine,
> `""` refused `validate.minLength`). Neither is marked `required` in the schema. The add-drop that
> makes every other field's Java initialiser work therefore turned a correctly filled v3 form into a
> 422 on a field the operator deliberately left blank. A new layout key, `sendEmpty`, names the
> exception — `SNMP.DS` names `contextName` and nothing else — and `keep()` sends `''` for it on an
> add rather than dropping it. Filed as **D69**, with **D70** for the related hole: a v3 source with
> `MD5` and no passphrase at all is accepted 201 and cannot authenticate.
>
> Verified end to end on the fixed build: the exact body the v3 add form now posts answers **201**,
> and the read-modify-write edit of it answers **200**. The six probe rows were deleted; the instance
> is back to the 21 data sources it started with.

### 9 — `MQTT.DS` / `MQTT.PL` (done, 2026-09-28)

**The first data source with no polling period at all.** `MqttDataSourceVO` is not a polling source —
it holds a live client and is driven by what the broker sends — so there is no `timePeriod` on the
model. Every form before this one opened on "Polling interval"; this one does not have the field.

**Four fields cannot be omitted, and each one answers 500 rather than a message.** `toVO` resolves
`qosType`, `publishTopicType`, `subscribeTopicType` and `publishQosType` with `Enum.valueOf` and no
null check, and `toVO` runs before `validate`. `brokerUri` is the same class through `validateURI`,
which does `new URI(vo.getBrokerUri())` and then `uri.getScheme().hashCode()` — so an absent URI **and
an empty one** are both a 500 with no field attached. Measured, all five. Filed as **D71**.

The four enums are seeded. `brokerUri` cannot be: there is no value that would be right, and a
plausible-looking `tcp://` with no host actually *passes* validation and produces a source that never
connects. So it needed the form to refuse it, which needed two things the layout language did not have:

- **`required`**, a layout key that adds to whatever the schema already marks required and never
  subtracts. `MQTT.DS` names `brokerUri` and `topicFilters`; `MQTT.PL` names both topics.
- **A save that reads the form's validity**, which the dialog never did. The schema forms are bound
  with `standalone: true`, so the `NG_VALIDATORS` each one registers reached no parent control: only
  `identityForm` was checked, and a required field was decorative — the dialog closed and the gateway
  answered for it. `save()` now collects every rendered form through
  `@ViewChildren(GatewayFormComponent)` and refuses an invalid one, which needed each per-type
  subclass to provide itself under that token (a subclass is a different directive, so the query would
  not otherwise see it). A `mat-error` on each control branch says which field, because a blocked save
  with no message is a worse dead end than the 500 it replaces.

  This was a pre-existing hole rather than an MQTT one — any schema-declared `required` field had the
  same gap. Nothing regressed by closing it: **no rendered field on any of the nine worked types is
  schema-required**, checked across the whole document, so the gate bites only where a layout asks it
  to.

**Both MQTT topics are required, and that is the model.** `MqttTopic.validate(topic, false)` runs over
each — no wildcards, length 1–65535 — and a null one comes back as a 422 quoting a Paho NPE. So there
is no publish-only or subscribe-only MQTT point, which is also why `settable` is hidden:
`isSettable()` answers `publishTopic != null && length > 0`, which a valid point always satisfies. It
is derived, not chosen.

**`topicFilters` is a newline-separated list** — split on `\n`, each line validated with wildcards
allowed — so it is a textarea. The two PEM certificates are textareas too. `privateKey` deliberately
is **not**: it is `writeOnly`, which types it as a password, which is what makes an empty one mean
"unchanged" in `keep()`. A textarea would turn every save of an unchanged key into an erasure.

**Only two of the three TLS fields are gated.** `useCertificate` is the client-certificate switch —
`MqttConfigurationMapping` passes it as `awsIot` and `MqttClientRuntime` reads it to choose
`getAwsSocketFactory` (mutual TLS) over `getSocketFactory` (server-authenticated TLS). So the client
certificate and its key are gated on it and **the CA is not**: `validateURI` requires a CA for any
`ssl://` broker whatever the switch says, and gating it would hide the field that refusal names.

`keepAliveInterval` and `connectionTimeout` seed 60 and 30 — the values Cortex's own broker dialog
already uses for the same fields on the platform-integration client, so the two forms agree. Absent
lands as 0, which `validate` accepts and which means "no keep-alive" and "wait forever" to Paho.

**And a correction to the 2026-09-27 verb change, found by measuring instead of reasoning.** That
commit switched both the data source and the data point update to `PATCH`, to stop `PUT` nulling the
secrets the `GET` will not return (D68). The data source half was measured and is right. **The data
point half was not, and it was wrong**: `PartialUpdateArgumentResolver` merges with
`readerForUpdating`, which cannot merge into a *polymorphic* member, so `PATCH` refuses any body
carrying a `pointLocator` — 400 `"Failed to read request"`, whatever is in it. Every point edit
through Cortex would have failed.

Points are back on `PUT`, and safely, for a measured reason: **no `pointLocator` type in the whole
schema document declares a `writeOnly` field.** The secrets are on `SNMP.DS`, `MQTT.DS`, `OPC.DS` and
`MQTT_SENDER.PUB` alone. Filed as **D73**, with the part Cortex cannot fix: a publisher body carries
`points`, so `PATCH` refuses it, and `MQTT_SENDER.PUB` declares two `writeOnly` fields, so `PUT`
erases them — **there is no verb a client can use to edit an MQTT sender**, and our publisher save
erases both credentials on every edit until the stack fixes one of the two.

**Verified.** The exact body each form posts answers 201 on the fixed build, and both edits 200 — the
point edit only after the verb was corrected. 72 layout, 40 schema, 10 renderer and 17 service specs
green. Sixteen probe rows deleted; the instance is back to the 21 data sources it started with. The
on-screen pass is owed with rows 6, 7 and 8.

### 10 — `HTTP_RECEIVER.DS` / `HTTP_RECEIVER.PL` (done, 2026-09-28)

**The whole data source is an access list.** There is no connection to configure and no poll — the
gateway runs a servlet and devices POST to it — so once the platform strips the identity fields, the
two descriptions and the four the points table owns, the form is `ipWhiteList`, `deviceIdWhiteList`
and nothing else. The smallest type worked so far, and the one where the two fields left are the
security boundary.

**Both lists are seeded because an absent one is a 500.** `validate` iterates each array with no null
check and `toVO` copies the model's field straight across, so a body omitting either throws inside
the validator. `HttpReceiverDataSourceVO` starts on `*.*.*.*` and `*`, which is what the layout seeds
— the D54 family again: the defaults exist and the model cannot reach them. Filed as **D74**.

**An empty list is accepted and then drops every request**, which is the part worth being careful
about. `ipWhiteListCheck` walks the array and returns false having found no match, and
`globWhiteListMatchIgnoreCase` answers false for a zero-length array before looking at anything. So
deleting every row in the editor saves 201 and produces a receiver that silently rejects everything.
Filed as **D75**.

Cortex cannot make these `required`: they are arrays, rendered by the shared array editor, and a
required-field validator needs a control. What it can do is seed them permissive and say the trap in
the label — "Allowed IPs (empty allows none)" — because the schema carries **no description for
either field**, so there is no hint channel to put it in. That is the second type in a row where the
label is doing a hint's job (MQTT was the first); if a third turns up, a `hints` layout key is
probably worth the six lines.

**The point is one field.** `parameterName` is matched against the keys of whatever was posted, and
`validate` refuses an empty one, so it is `required` rather than left to the round trip. `dataType` is
seeded for convenience rather than to avoid a crash: this is the **first locator whose validator
actually checks it** (`DataTypes.CODES.isValidId`), so an absent one is a clean 422 naming the field
rather than the silent `-1` of D65 — and the message says `dataType`, the client's name, not
`dataTypeId`.

`binary0Value` is gated on BINARY, the same field and the same rule as `SNMP.PL`:
`HttpReceiverDataSourceRT` compares the posted string against it only when the data type is BINARY
**and** it is non-empty, and parses by type otherwise. `settable` is hidden because `isSettable()`
returns a hard `false` — a receiver is pushed to, never written to — and a submitted `true` is
accepted and ignored.

**Not built, and noted rather than guessed at:** the form says nothing about *where* devices should
POST. That is the one thing an operator opening this type actually needs, and it is a computed value
rather than a field, so no layout key reaches it. Worth a decision of its own.

**Verified.** Both add bodies 201 against the fixed build, the source's edit 200 on `PATCH` and the
point's 200 on `PUT`, `settable` reading back `false` after a submitted `true`. 76 layout and 40
schema specs green. Fourteen probe rows deleted; the instance is back to the 21 data sources it
started with. On-screen pass owed with rows 6-9.

### 11 — `HTTP_JSON_RETRIEVER.DS` / `HTTP_JSON_RETRIEVER.PL` (done, 2026-09-28)

**The most complete type so far, and the one with the most dead weight.** A polling source that GETs
one JSON document and reads each point out of it by JSON Pointer, which makes the data source four
fields (URL, timeout, retries, auth) and the point five (pointer, data type, value format, timestamp
pointer, timestamp format). It is also the first type where a whole feature exists on both sides of the
model and cannot run: `setPointUrl`, `setPointName`, `settable` and a fully written `setPointValue` all
serve a path whose gate, `isSettable()`, returns a hard `false` (**D76**).

**Three fields hidden, and all three for the same reason: no code reads them.** `settable` is the D76
gate; `relinquishable` is never read by `toVO`; and `ignoreIfMissing` is stored, serialised, mapped —
and then read by nothing at all (**D77**). The gateway's own help text describes what that checkbox is
for, the regex retriever this type was derived from honours it, and the port lost it: `parseValue`
throws on a missing node for all four data types and the poll raises the parse event either way. A
switch that suppresses nothing is worse than an absent one, so it is hidden rather than shown.
`setPointUrl` and `setPointName` are **advanced rather than hidden**, because unlike the other three
they are stored, validated and would start working the day D76 is fixed.

**The timeout is required and floored because the gateway's refusal cannot be shown.**
`HttpJsonRetrieverDataSourceDefinition.validate` files its `timeoutSeconds <= 0` message under the
property name **`updatePeriods`** — the poll period, a field this form does not have (**D78**). So a
zero or empty timeout comes back as *"Must be greater than zero"* pointing at nothing on screen. The
answer is to keep the value from ever leaving the browser: seed the model's own 30, mark it required
against an empty box, and floor it at 1 because `required` counts a typed zero as an answer.

`retries` is required too but for a different reason, and **D78 does not reach it** — a first draft of
this section said it did. `validate` refuses only a negative there and files that under `retries`, its
own name, so a wrong value says which field it is. What it is protected from is an empty box: that
posts `null`, Jackson lands it on the primitive as 0, and the source silently stops retrying. Floored
at 0, which is what the gateway allows. Both sit on the main rows rather than under Advanced — a
required field behind a closed toggle is the same trap in a different place, which is why the layout
spec forbids that combination.

**The bearer token is a credential the gateway hands back in plain text**, and that is what decides how
to render it. `bearerToken` carries no `writeOnly`, so `GET /v2/data-source/{xid}` returns it verbatim
to anyone who can read the row (**D79**). The first attempt typed it as a password, on the reasoning
that every other credential in the table is one — and that was wrong in a way worth recording, because
`keep` drops an empty password rather than sending it. On a field the read does not carry that is
protection: an untouched box cannot blank a stored secret. On a field the read *does* carry it is a
functional gap, because the box is always populated, so clearing it is an operator deliberately
removing a credential and dropping the empty value silently keeps it. **A stored token could never be
removed through the form.** So it is a textarea — long, like the gateway's own form makes it — and
empty means empty, measured both ways: `""` stores empty, `null` stores null. The invariant that
matters is left intact rather than blurred: a password field is one the schema marked `writeOnly`, and
the day the stack marks this one, the mapper types it as a password by itself and `keep` starts
protecting it — correctly, because by then the read will no longer carry the value.

It is gated on `bearerAuth`, and the two share a row — the schema declares the token *before* the
switch that reveals it, so left alone the box would appear above its own switch.

**The pointers are JSON Pointers, and the gateway says so in a language nobody reads.**
`JsonPointer.valueOf` runs inside `validate`, so a pointer that does not start with `/` is refused
before it can break anything — but the message is `dsEdit.httpJsonReceiver.jsonPointerInvalid`, a key
with no translation and with *Receiver* where every other name says *Retriever*. It is one of **15
missing message keys out of the 18 this type uses** (**D80**), and two of those are in front of an
operator in normal use: the same table also shows every point's configuration description as
`dsEdit.httpJsonRetriever.dpconn`. Cortex does not paper over either. A form that guessed at the
gateway's translations would be wrong in a way that is harder to notice, and the raw key at least names
what is missing.

**This is the type that paid for the `hints` layout key**, which row 10 said was worth six lines if a
third type turned up with no schema descriptions. It did, and with four fields wanting one rather than
one. The channel already existed and was being missed: the mapper fills `FormProperty.hint` from a
property's `description`, and the template already renders it as the tooltip on an info icon inside the
field. So `hints` writes to the same place, takes precedence over a description the device sent, and
the labels go back to being names — "Value pointer", not "Value pointer (e.g. /data/0/temp)", which is
where ThingsBoard puts a syntax rule and where the user asked this UI to be. Two limits worth knowing:
a **delegated** field has no hint channel, because `tb-dynamic-form` draws its own rows and the icon is
not one of them — which is why row 10's whitelist labels stay long — and a `switch` renders its hint on
the toggle's label instead.

**`valueFormat` is one field with two meanings**, which is why the label names both. For NUMERIC it is
a `DecimalFormat` pattern applied to a textual value, and validated as one (a malformed pattern is a
clean 422 with a real English message from `DecimalFormat` itself). For BINARY it is the text that
means 0: `new BinaryValue(!valueFormat.equals(node.textValue()))`, so anything else reads as 1.
MULTISTATE and ALPHANUMERIC ignore it. The gateway's own form solves this by relabelling the field per
data type, which a static descriptor cannot do — so both meanings go in the hint, which has room for
them. `timeFormat` has a narrower rule worth saying too: a `long` timestamp is epoch millis whatever is
in the box, and the format is consulted only for a textual one.

**The poll period turned out to be everybody's problem.** `timePeriod` and its own `timePeriodType`
are the only two names that appear in **any** `required` array in the whole schema document — 28 sites
and 1 — and `timePeriod` is a delegated fieldset, so a source saved without opening it posts `{}` and
is refused, while one with a unit and no count is **accepted with a zero period**. (An earlier draft of
this section, and of the commit message, said `timePeriodType` was the only `required` in the document.
That was read off the `TimePeriod` component alone and is wrong: `timePeriod` is required at the top
level of 13 data-source types and in 15 of the components they inherit from. The seed keys on the
*property*, which is declared on 18 types, so the implementation was right and only the sentence was
wrong.)
Measured on this type and on `MODBUS_IP.DS`: nothing validates the count anywhere.
`PollingDataSourceVO.validate` has the guard and is unreachable from REST, and
`PollingDataSourceDefinition`, which carries the same one, is extended by a single type in the tree
(**D81**).

That made the fix a shared one rather than a line in this layout: `gatewayFormDefaults` now seeds
`{timePeriod: 5, timePeriodType: 'MINUTES'}` — `PollingDataSourceVO`'s own initialisers, which no
subclass overrides — onto any new data source **whose schema declares the field**. Keyed on the schema
rather than on a list of types, so it reaches the 6 polling types already laid out and the 11 not yet
written without an invariant anyone has to remember. Keying on the schema also draws a line a hand-kept
list would have got wrong: `MODBUS_SLAVE_DEVICE.DS` does not declare a poll period and
`MODBUS_SLAVE_DEVICE_POLLING.DS` does.

**What the seed closes is the source nobody opened the fieldset on, and only that.** An operator who
opens it and clears the count still posts `{timePeriod: null, …}` and still gets a zero period, and no
client-side rule can stop them: the mapper does mark the fieldset `required`, but a fieldset is
delegated to `tb-dynamic-form`, `build` creates no control for it, and the delegate is bound
`standalone: true` — so neither the form's own `validate` nor the dialog's save gate ever sees its
validity. Cortex removes the silent zero, not the deliberate one. The rest is the gateway's to fix.

**Deviations from the gateway's own form, all deliberate.** Its webapp marks `setPointUrl`,
`valueFormat`, `timePointer` and `timeFormat` **required**, which the Java contradicts: `validate`
requires none of them, and requiring all four would block the ordinary case of a numeric document with
no timestamp. It also binds its "Settable" toggle to `pointLocator.setPointName` — a boolean written
into a string field — and offers no control for `settable` at all. And it omits `timeoutSeconds` and
`retries` entirely, although the gateway's own help text describes both as ordinary configuration.
Filed as **W26-W27**.

**Verified.** Both add bodies 201 with exactly the payload the form produces, including
`alarmLevels: []` and the seeded period; the source's edit 200 on `PATCH` with the token omitted and
the stored token intact afterwards; the point's edit 200 on `PUT` carrying the three hidden fields back
unchanged; `settable: true` reading back `false`. 82 layout, 40 schema, 10 form and 17 service specs
green. Every probe row created `enabled: false` against a dead port and deleted; the instance is back
to its original 21 data sources. On-screen pass owed with rows 6-10.

**What the adversarial review found, and what it changed.** Five findings on the row-11 commit, four
of them acted on and one of them reaching back into rows 7 and 8.

- **Every point locator offered IMAGE, and three of them cannot make one.** The schema declares all
  five `DataTypes` on every locator, because the column holds any of them — but
  `SnmpPointLocatorRT.variableToValue` and `HttpJsonRetrieverPointLocatorRT.parseValue` both end their
  switch on `default: throw`, and `JavaScriptService.coerce` (which is what a META point's script
  result goes through) ends its chain of branches the same way. Nothing validates `dataType` on any of
  the three, so a point saved as IMAGE is accepted and then fails on every poll for ever, reading
  nothing. `VIRTUAL.PL` had been narrowing the list since row 1 for its own reasons; `META.PL`,
  `SNMP.PL` and `HTTP_JSON_RETRIEVER.PL` now share the same constant, and a spec names all four.
  The review's own recommendation — a blanket rule that *every* laid-out locator must narrow — is
  wrong, and checking it is what showed why: `PointValue.stringToValue` has a real `DataTypes.IMAGE`
  branch that builds an `ImageValue` from the posted string, so `HTTP_RECEIVER.PL` genuinely supports
  an image and narrowing it would remove a working capability. Which types belong in the list is read
  per type, from the code that converts the value. MQTT is the one still unread.
- **`required` does not refuse a zero**, so it did not cover the value D78 is actually about: Angular's
  `Validators.required` counts `0` as an answer, and `timeoutSeconds: 0` is precisely what comes back
  as *"Must be greater than zero"* against `updatePeriods`. Hence the new `min` layout key — six lines,
  because `validatorsFor` already honours `FormProperty.min`; the schema simply declares no `minimum`
  here. `timeoutSeconds: 1`, `retries: 0`, and a message for the error the number field could not
  previously explain.
- **The claim that typing a field as a password buys `keep()`'s protection was false.** `keep()` builds
  its set of secrets from the *mapper's* properties, where `password` means the schema marked the field
  `writeOnly`; it never sees `layout.types`. So a layout-typed password would have rendered masked with
  none of the empty-drop protection that makes masking safe — worse than either honest option. The
  bearer token had already been moved to a textarea for a different reason (it is clearable now), which
  happens to be the right end state; what was missing was anything holding the rule. A spec now refuses
  `FormPropertyType.password` anywhere in a layout's `types`.
- **A closed `visibleWhen` gate keeps its control and still sends its value**, so a token typed and
  then hidden by turning `bearerAuth` off is stored anyway. That one is **left alone deliberately** —
  see the third open decision below.
- **Two smaller corrections**, both to prose rather than code: `retries` was bracketed with
  `timeoutSeconds` under D78 when the gateway names that field correctly (above), and the seed's reach
  was overstated (above). And one note for the on-screen pass: `['bearerAuth', 'bearerToken']` is the
  first explicit row in the table to pair two types `PAIRABLE_TYPES` excludes — a toggle beside a
  two-row textarea. `pack` honours it unconditionally and the gate closing collapses the row to the
  switch alone, so it is correct; whether it *looks* right is the one thing a spec cannot answer.

### 12 — `INTERNAL.DS` / `INTERNAL.PL` (done, 2026-09-28)

**The gateway watching itself**, and the first type where the gateway's own UI is not a reference at
all: its `internal-datasource` component delegates to the shared **mesh-node** sensor form, so it shows
`address`, `anchorNode`, `location` and `zone` — none of which this model has — and offers neither the
poll period nor `createPointsPattern`. The extra keys are silently ignored rather than refused
(measured: an add carrying `address` saves 201), so it saves, it just cannot configure anything.
Filed as **W28**. Parity here means matching the Java.

**The data source is one regex.** `InternalDataSourceRT` compiles `createPointsPattern` once and, on
every poll, creates a point for each registered monitor whose **whole id** matches (`matcher(...).
matches()`, not `find()`) and that it is not already tracking — enabled, named from the monitor, with
logging types chosen by what kind of monitor it is (ON_CHANGE for a success count, 5-minute INTERVAL
MAXIMUM for a poll duration). Left empty the source creates nothing and points are added by hand — so
`provisionedPoints` would be **wrong** here, even though this is the most provisioned type in the
sequence: the button it removes is the only way to add a point to a source with no pattern.

The live `internal_monitoring_ds` is **not** the by-hand example, as a first draft of this row claimed.
`InternalLifecycleDefinition.postInitialize` installs its 23 points from a fixed table, under an
`else` branch commented *"Ensure all points are added"* that re-runs on **every boot** and skips the
ones that exist — so a point deleted through Cortex returns when the gateway restarts, and nothing
in Cortex can hold that deletion. The decision stands unchanged, for the other half of the reason: that
provisioner serves exactly one xid (`internal_monitoring_ds`), and a source an operator creates has no
provisioner at all.

**The one field a client needs does not exist on the wire.** `GET /v2/stack-monitor` returns 98 monitors
as `{name, value}` — the translated name and the current value, and **not the id** (**D83**). The id is
what `monitorId` stores and what the pattern matches, and it is not derivable from the name:
"Schedules" is `com.inferrix.stack.dao.ScheduleDao.COUNT`. So the picker this type obviously wants
cannot be built, and both fields fall back to text with a hint carrying real id shapes. The
`/{id}` route can confirm an id already in hand but cannot enumerate one, which is the wrong way round.

**A wrong id is accepted and then reads nothing for ever.** The validator is written —
`try { getMonitor(id) } catch { "internal.missingMonitor" }` — and cannot fire, because `getMonitor`
answers an unknown id with null rather than an exception (**D85**). Measured: `no.such.monitor` saves
201 and the point simply never updates, because `doPoll` and `forcePointRead` both skip a null monitor.
Only an *absent* id is refused, and that is the one case that throws. So `monitorId` is `required` here,
which covers the refusable mistake and cannot cover the accepted one; the hint asks the operator to
check the value after the first poll, which is the only verification left.

**`dataType` is disabled rather than offered.** `InternalPointLocatorModel.toVO` builds a fresh VO and
sets `monitorId` alone, so a submitted data type never reaches storage and the VO's own NUMERIC is kept
(**D84**, measured: a point posted ALPHANUMERIC reads back NUMERIC). The type genuinely varies — the
live `internal_name_hardware` point is ALPHANUMERIC, and not as a legacy leftover: `maybeCreatePoints`
does `pl.setDataTypeId(DataTypes.ALPHANUMERIC)` for that one xid, in current code, on every boot. So
it is a configuration only the Java can produce, which makes the worse half of D84 — that **any save
of such a point resets it**, since `PUT` replaces the locator wholesale and `PATCH` refuses a
polymorphic member (D73) — a standing trap on a point the gateway itself ships, rather than a hazard
confined to old installs. The boot-time provisioner will not repair it either: it only creates points
that are missing. That half is read from the source rather than
measured: reproducing it would have meant breaking the live text point on a shared instance. Nothing on
this side can prevent it, because the gateway discards the field rather than misreading it; what the
layout can do is stop pretending the dropdown works, and say why in the hint.

**Seeded with a monitor that exists.** `InternalPointLocatorVO` starts on
`…WorkItemMonitor.highPriorityWaiting`, which resolves live to "Waiting High Priority Threads" — so a
new point is savable as it opens and shows the shape of an id at the same time, which given D83 is the
only teaching material available. Safe to carry a device-side id in our own table because that monitor
is not optional: `StackMonitoringService` is a plain `@Component` whose constructor creates it, with no
condition, and `internal-ds` compiles against that class — so a gateway offering this type has the
monitor. One live measurement would not have been enough to claim that.

**Verified.** The form's own bodies: add 201 with the seeded period and `alarmLevels: []`, `PATCH` 200
adding a pattern, a malformed regex a clean 422 naming `createPointsPattern`, point add 201 on the
seeded monitor, point edit 200 on `PUT` carrying the hidden fields and the disabled data type back. 90
layout and 40 schema specs green. Six probe rows and one probe source deleted; the instance is back to
21 sources and 106 points as it then stood, and the live internal source and its 23 points were not
touched. (That baseline is 12 since row 13 deleted the nine `ZZ …` SNMP rows row 8 had left.) On-screen
pass owed with rows 6-11; first item on this row's list is the point form, where `pack` would have
paired `dataType` with `monitorId` and the layout deliberately does not — a full-width row each, so a
61-character monitor id is not cut off, at the cost of a wide greyed select below it. Second item is
the `dataType` tooltip itself, which is the fix this row's review produced and the one thing here that
only a mouse can confirm.

**A 500 that is nobody's type in particular.** A duplicate `xid` answers **500 Internal Server Error**
with no field, on both `POST /v2/data-source` and `POST /v2/data-point` (**D86**). Both add forms offer
the xid, so it is an ordinary operator mistake answered with the least informative status there is. It
has been reachable through all twelve rows; it took this one to notice, because a probe reused an xid.

**What the adversarial review found, and what it changed.** Nine findings on the row-12 commit, eight
acted on and one rejected with a reason.

1. *A hint on a disabled field could not be read* — the high one, and it made the row's central
   decision ("stop offering a dropdown that lies; say why in the hint") ship a greyed box beside a dead
   icon. Material sets `pointer-events: none` on a disabled field's `.mat-mdc-text-field-wrapper` and
   re-enables only `.mdc-text-field__input`; `matIconSuffix` renders inside that wrapper, and
   `MatTooltip` opens on mouseenter, touchstart or keyboard focus — none of which a non-focusable
   `mat-icon` behind `pointer-events: none` will ever see. Confirmed in the compiled Material CSS and
   in the form-field template, and there is no ThingsBoard-wide override. One line in
   `gateway-form.component.scss` (`pointer-events: auto`), which also repairs three cases the **mapper**
   has produced since it was written: a `readOnly` field whose schema carried a description, a
   `WIRE_STRING_COMPONENTS` ref ("Reported by the gateway"), and an unresolved `$ref`.
2. *The reviewer also wanted a spec forbidding a hint on a `readonly` field* — **rejected**, because it
   contradicts finding 1's own fix. The reason the `hidden` rule exists is that a hidden field renders
   no icon at all; a disabled one renders an icon, and after the CSS fix that icon works. Recorded as a
   comment on the spec so the next reader does not re-derive the wrong half.
3. *"Its points are added by hand, which is what the one live source on the bench gateway does"* — wrong,
   and it was the load-bearing premise for omitting `provisionedPoints`. Corrected above: the live
   source is provisioned from Java on every boot. The decision survives on its other half.
4. *"The live `internal_name_hardware` point is ALPHANUMERIC because it predates this REST surface"* —
   wrong, and it understated D84. `maybeCreatePoints` sets that data type in current code, every boot.
5. *D80 and D87 each attributed a French string to `i18n_en.properties`* — both false.
   `core/.../i18n_en.properties:1419` is `event.ds.dataParse=Point data parse exception` and
   `Modules/internal-ds/.../i18n_en.properties:9` is `dsEdit.internal=Internal Datasource`; the French
   is in `i18n_fr.properties` where it belongs. Both clauses dropped here and in the handed-over specs,
   and every other row was re-grepped for the same mistake — there were exactly two.
   **That last clause is no longer true, and the row-19a review found the third (2026-09-30).** Row 19a
   claimed the current sensor's five all-phase keys "are in `i18n_fr.properties` and in no other
   bundle". They are in no bundle at all — zero matches across every properties file in the stack. It
   is the same mistake running the other way: an English-bundle *absence* explained by a French-bundle
   *presence* that was inferred from the neighbouring per-phase keys and never grepped. Corrected in
   four places. The rule this keeps proving: grep the key, not the key next to it.
6. *The `createPointsPattern` hint did not say the whole id must match* — `InternalDataSourceRT` uses
   `matcher(id).matches()`, so `COUNT` on its own creates nothing and reports nothing. Both worked
   examples in the hint happened to be anchored, which hid it. One clause added.
7. *`monitorId` rendered second, behind the disabled box* — schema order is `dataType` first. Now
   `rows: [['monitorId'], ['dataType']]`; a row each rather than a pair, because an id runs to 60-odd
   characters and half a line cuts it off mid-package.
8. *The shared `settable` `@Schema` description names two read-only-by-construction locators and there
   are three* — `InternalPointLocatorVO.isSettable()` also returns false unconditionally and is not in
   the sentence, so a client generating its form from the schema offers a toggle the gateway accepts
   and ignores. Filed as **D88**. Cortex already hides the field here, but from reading `toVO` rather
   than from the schema — which is the reliance that description exists to remove.
9. *An escaped device description rendered its entities literally* — pre-existing in the mapper, and
   `INTERNAL.DS`'s Advanced panel is where it finally showed: `quantize` read "…rather than the
   source&#39;s start time". `escapeCell` is for a table cell, which is HTML; a hint's only sinks are
   `matTooltip`, `[tb-hint-tooltip-icon]` (another `matTooltip`) and `tb-dynamic-form`, which renders a
   delegated field's hint the same way — all `textContent`. The one HTML sink in that component,
   `[innerHTML]="safeHtml"`, is fed by `htmlContent` on a `htmlSection` property, which this mapper
   cannot emit: the markup `FormPropertyType`s and `condition` are refused by specs of their own.
   **The trade-off, stated plainly:** the mapper now puts device free text into `hint` unescaped, so
   the defence is the sink and no longer the data. Anyone giving a hint an HTML sink later breaks it.
   The spec that asserted the escaping now asserts the description arrives verbatim, and says why.

## What the 5.1.3 cut changed here

The gateway closed D65-D88 on 2026-09-29. Eight of them are rules a form has to follow, so this is
not only a ledger update. Everything below was **re-probed live against 5.1.3** on the team's local
instance, which is back where it started -- 21 sources / 106 points at the time, 12 / 106 once row 13
cleared the nine `ZZ …` rows row 8 had left behind.

**A locator's data type is now mandatory, on all 61 types.** `DatapointService.validate` refuses a
locator whose `dataTypeId` is not in `DataTypes.CODES` (**D65**) — an omitted one resolves to `-1`,
an unset one is `UNKNOWN` (0), and both are a 422 *"Invalid value"* against `dataType`. Every locator
in the document declares the field and **none of them marks it required**, so the rule cannot come
from the schema, and with 61 types it does not belong in a per-type layout either. It went into the
mapper, on the family: `schemaToFormProperties` marks `dataType` required when `family` is
`pointLocator`. Two layouts sit on top of it without conflict — `MODBUS.PL` hides the field, and a
hidden field has no control to validate (Modbus derives the type from the register range anyway);
`VIRTUAL_MESH_NODE.PL` disables it, and Angular leaves a disabled control out of validation
entirely, which is inert rather than wrong because that source has `provisionedPoints` and so no Add
button at all.

**`INTERNAL.PL` got its two fields back.** `toVO` copies the data type now (**D84**), so the
dropdown is a real dropdown rather than the disabled box that row 12 shipped — measured, an
ALPHANUMERIC point reads back ALPHANUMERIC. And `GET /v2/stack-monitor` publishes each monitor's id
(**D83**), so `monitorId` is a **picker** rather than an id typed from memory:
`InternalPointFormComponent`, 85 lines, the fifth component extending `GatewayFormComponent` and the
fourth on a locator, using the same `runtimeOptions()` hook the others do. A wrong id is also refused now (**D85**, measured 422 *"No monitor with id
…"*), which is what makes the picker a convenience rather than the only defence.

**`IMAGE` is off the internal list too, and that one is ours.** D82 refused it on the three locators
whose runtime throws; `INTERNAL.PL` was not one, because until D84 its submitted type was discarded.
Now that it is kept, an IMAGE internal point saves 201 and can never hold a value —
`InternalDataSourceRT.doPoll` has a `Number` branch and a `String` branch and nothing else. Filed as
**D89**; the dropdown is narrowed in the meantime.

**`HTTP_JSON_RETRIEVER.PL` lost two of its four hidden fields.** `isSettable()` answers the stored
field (**D76**), which is the only entrance to a set-point path that was already fully written, and
`ignoreIfMissing` now suppresses the parse event it always existed to suppress (**D77**). Both are
shown. `setPointName` is gated on `settable` rather than required, because the invariant against a
required field behind a closed gate is the right one — the gateway's refusal names it, and the field
is on screen at exactly the moment the rule applies. Measured: `settable: true` with no
`setPointName` is a 422 naming `setPointName`, and with one but no `setPointUrl` on the source it is
*"The data source has no set point URL, so this point cannot be settable"* — which the hint says
first.

**That second refusal has no right control to land on, and the hint is the whole mitigation.** The
gateway files it against `setPointName`, a field the operator has filled in correctly, because a data
point's validation response can only carry the data point's own fields — the value that is missing
lives in the Advanced panel of the *data source* dialog. The message text names the real cause, which
is the best the wire allows, so this is not filed as a defect on either side. A per-type point form
could read the open source's `setPointUrl` and refuse the toggle before the round trip; that is one
field on one type and is not worth a component until something else needs one.

**The bearer token is a password now, and the layout says nothing about it.** D79 marked it
`writeOnly`, so the mapper types it and `keep()` protects it. The textarea override that row 11
shipped existed only because the schema did *not* mark it: the read carried the token, so masking it
would have made a stored one impossible to clear. Measured on 5.1.3: `bearerToken` is absent from
`GET /v2/data-source/{xid}`, and `SecretFields.merge` restores the stored value for null **or
blank** — so an empty box means unchanged, as it does for the other three credentials on this
gateway. The third Open decision below is settled by that, not by us.

What the change is **not** is pure gain, and the first draft of this section read as if it were. A
`password`-typed field has its empty value dropped on an edit as well as an add, so a stored bearer
token can no longer be cleared from Cortex — and not from the gateway either: `SecretFields.merge`
restores the stored value for null **or blank**, on every write, by design. The textarea did allow
clearing. The way to stop a token being used is `bearerAuth: false`, which leaves it stored and
unused; the same is true of the two SNMP passphrases and their protocols, and both hints now say so.
Losing that is worth the masking and the D79 fix it comes with, but it is a loss.

**And the override was wrong in both eras, differently — which closed a hole in the descriptor.**
The review's reading, that a textarea "rendered the token in the clear while the dialog silently
treated it as a secret", conflates them: before 5.1.3 the mapper typed the field `text`, so `keep()`
did *not* treat it as a secret and the clearing worked, and the exposure was real — the read carried
the token and a plain multiline box painted it on screen. After 5.1.3 the mapper types it `password`
and there is nothing left to expose, but `keep()` reads secrecy off the *mapper*, never off
`layout.types`, so the override left a plain box that dropped its empty value: a control that looks
clearable and is not. Both halves are the same defect — a layout must not retype a secret — and
nothing stopped one. `laidOutType` now refuses it, beside the refusal that already protects a
delegated field's editor. The existing spec that a layout never *names* `password` is the other
direction and stays; a spec cannot cover this one, because it cannot see which fields the schema
marked `writeOnly`.

**`SNMP.DS` lost its `sendEmpty` workaround.** Row 8 had to send `contextName: ""` while omitting
`engineId`, because the two refused opposite things. D69 made absent and blank both mean "not set"
on both fields — measured 201 with the pair omitted and 201 with both `""`. The two passphrases
carry a hint rather than `required`, for the gate reason above: D70 requires one once a protocol
other than NONE is chosen, checked on the *merged* VO so an ordinary edit that omits an unchanged
passphrase still passes.

**Two things that need no change here, recorded so the next reader does not re-derive them.** D74
confirms our whitelist seeds are the VO's own defaults (`*.*.*.*`, `*`), and D75 refuses an emptied
list — which cannot be said in the UI, because an array is delegated to `tb-dynamic-form` and its
array container has a title and no hint channel. D66 defaults `scriptEngine` server-side; the
layout keeps its own default, which is now what lets this form work against an older gateway.

**Three findings were mine and wrong.** D68 was fixed on 2026-09-22, five days before I filed it —
I measured a `PUT` against an older jar, which is the same mistake in a different direction to every
"read the Java first" note in this document. D72's central claim that the MQTT enum labels are
missing is wrong: eleven of thirteen are present. D67's first table row is wrong:
`dsEdit.meta.event.none` exists. The pattern in all three is a measurement or a grep taken as
conclusive without the source beside it.

**D73 stays a 400 on purpose, and the reason it existed is gone.** A `PATCH` carrying a polymorphic
member still fails; the workaround it was filed to justify is unnecessary now that D68's fix makes a
read-modify-write `PUT` safe. The verb asymmetry this feature works to is unchanged — sources
`PATCH`, points and publishers `PUT` — but the reason to prefer `PATCH` for a secret is not.

### 13 — `MESH_CONTROLLER.DS` / `MESH_CONTROLLER.PL` (done, 2026-09-29)

Three fields on the source and two on the point, and the mesh owns most of them. The first type in
the sequence whose **source** is provisioned as well as its points.

**The gateway makes both.** A controller joining the mesh reaches
`MeshControllerNodesDataSourceCreationManager`, and `CreateMeshControllerVO.createDataSource` saves
the source at the node's address, calls `createDataPoints` — one point per `MeshControllerAttributes`
constant, with that attribute's own data type and text renderer — and starts it. There is one
constant, `HEARTBEAT(1, "HEARTBEAT", BINARY, …)`. The live source on the team's instance is exactly
that: address 11, one BINARY `HEARTBEAT` point. So `provisionedPoints` is on, for the reason it was
wrong on `INTERNAL.DS` and right here: the Add button could only ever offer a second point for the
one attribute that exists.

**`address` is read-only, after the review took the first version apart.** It shipped editable, on
the argument that disabling it would make the type unaddable by hand and that refusing to create a
source is not this layer's decision. Three things say otherwise, and the review found all three.
`MeshControllerDataSourceDefinition.isEnabled()` returns **false** — the flag whose interface javadoc
is *"so that it could be listed in the dropdown selection menu"* — so the gateway has already taken
that decision. A source created through REST gets **no points**, because `createDataPoints` runs only
from `CreateMeshControllerVO` on the mesh-join path, and `provisionedPoints` then removes the only
button that could add one: the path being defended produces a source that can never hold a point.
And `deleteRelationalData` keys `deviceCache.addRemoveDevices` and
`MeshNodeInfoService.deleteByAddress` on `vo.getAddress()`, so an edited address orphans the
mesh-node row filed under the old one — a harm `VIRTUAL_MESH_NODE.DS`'s `controllerAddress` does not
have, which is what makes read-only the consistent choice rather than an arbitrary one.

The gateway's own rule is still worth writing down: `validate` refuses `0` and `-1` and the model
**initialises `address` to `-1`**, so an omitted one is a 422 rather than a default (measured: all
three answer *"Invalid value"*). It is in the layout's comment rather than in `required`/`min`,
because a disabled control is left out of Angular's validation and the pair would read as a rule and
enforce nothing — which is exactly what the `required`/`readonly` spec added on 2026-09-28 forbids.

That the Add menu offers this type at all is **D92**: `DataSourceDefinitionModel` publishes `type`,
`name` and `pointLocatorType` and not `isEnabled()`, and 48 definition classes in the tree return
false from it. One field on one model, the same shape as D83, and Cortex cannot filter its own menu
without it.

**`anchorNode` and `location` are both live.** `MeshControllerDataSourceRT` hands the address, the
anchor flag and the zone to `MeshControllerMeshActionListener`; `location` is the model's name for
the VO's `zone`, which is worth knowing when reading the Java next to the wire.

**The attribute list is written out here because the gateway cannot check it.** `MeshPointLocatorVO`
declares one `public static ExportCodes ATTRIBUTE_CODES` and **34 subclasses reassign that one
field** from their own static initialisers — every sensor tag, the thermostats, the light
controllers, the mesh extender and switch, and the controller. Each replaces the table rather than
adding to it, so the 34 of them share one table: whichever initialised last. (There are two tables in
all -- `MeshControllerNodesPointLocatorVO` declares its own.) The 34 stands; the reason given here
first did not. That class extends `AbstractPointLocatorVO`, a sibling hierarchy, so it shadows
nothing. The arithmetic is 38 assigners minus the base's own, minus that class and its two
subclasses. Corrected 2026-09-30.
`MeshControllerDataSourceDefinition.validate` reads it through inheritance, and so does
`fromVO`. Measured on 5.1.3: a `MESH_CONTROLLER.PL` point saved **201** with
`attributeId: "BATTERY"` — which `MeshControllerAttributes` does not declare — and read back as
`"BATTERY"`. Filed as **D90**, and it is general to all 34. A one-option list on this side is the
only refusal available; it is a convenience, not a boundary, because anything posting to
`/v2/data-point` directly still gets its 201.

> **Superseded by D108 (row 18).** This row, and the review that followed it, read the shared table
> as a fixed if arbitrary snapshot — "whichever class loaded last", settled at boot, probed to
> `MeshExtenderAttributes` exactly. It is not settled at boot. A static initialiser runs at first
> class **load**, so the table changes while the gateway runs, and row 18 flipped it mid-session and
> measured all three consequences: valid attributes refused, foreign attributes accepted, and stored
> points reading back `attributeId: null`. The narrowing here is still right; the reason it cannot be
> relied on is worse than this section says.

`dataType` is narrowed the same way and for a related reason: `HEARTBEAT`'s conversion is
`value -> new BinaryValue(value.getBooleanValue())` and the provisioner stores the attribute's own
`BINARY`, while the definition checks only that the submitted type *exists*. A numeric mesh
controller point would be a point the mesh writes a binary value into.

**Both locator fields are read-only.** `provisionedPoints`' own description already claims that of
every locator field on a provisioned point, and `readonly`'s description gives "the attribute a point
reads" as its example; the first version of this row marked neither, which the review caught as a
contradiction with both. The option lists stay, because a disabled select still needs its item to
render a label rather than the raw constant.

**`settable` and `relinquishable` are hidden, and this is the pre-D84 shape again.**
`MeshControllerPointLocatorModel.toVO` builds a fresh VO and copies `attributeId` and `dataType`
alone, while `fromVO` inherits the base and reports `settable` back. Measured: `settable: true`
saves 201 and reads back `false`; `relinquishable` reads back `null`. Filed as **D91** — and noted
there that this locator is *not* read-only by construction the way D88's list is:
`MeshPointLocatorVO.isSettable()` answers the stored field, it is simply never given one.

**Verified.** The form's own bodies: source add 201 with address/anchor/location round-tripping,
`PATCH` 200 on the location with the address preserved, point add 201 with the configuration
description resolving to "Heartbeat", `PUT` 200 on the whole point with the hidden fields carried
back. 94 layout and 41 schema specs green. Every probe row deleted; the instance is back to 12
sources and 106 points — **12 rather than 21 because the nine `ZZ …` SNMP rows left behind by row 8
were mine and are now gone**, which closes one of the cleanup items this document has been carrying.
On-screen pass owed with rows 6-12.

**What the adversarial review found, and what it changed.** Six findings, all six acted on, and two
of them undid decisions this row shipped.

1. *The `HTTP_JSON_RETRIEVER.PL` row comment described a layout `pack` does not produce* — the high
   one, and it was my own documented rule I had failed to apply: an explicit row lands where its
   **first** member falls in schema order, and `settable` is the second property the schema declares.
   `['settable', 'setPointName']` therefore sat near the top, between the pointer and the formats,
   while the comment claimed it was last. Fixed by naming the row `['setPointName', 'settable']`,
   which is the only way to seat it last; the cost is that the row reads key-then-switch.
2. *`required: ['valuePointer']` had become stricter than the gateway* — on a path this same commit
   opened. With `settable` hidden the rule matched; unhiding it made a **write-only** point
   unsaveable from Cortex, and the gateway accepts one (measured 201, `settable` on with a set point
   key and no pointer at all). Dropped to a hint. Inventing a refusal the gateway does not make is
   the mirror of the mistake this feature exists to avoid.
3. *`MESH_CONTROLLER.PL` marked nothing read-only*, contradicting both `provisionedPoints`' own
   description and `readonly`'s. Both fields are read-only now.
4. *The hand-add path this row defended produces an unusable source* — see `address` above.
5. *The read-only argument was arbitrary as written* — three concrete tiebreakers, all pointing the
   same way, and D92 came out of the third.
6. *The layout comment said 37 subclasses; it is 34* — the doc and the commit message had it right,
   the code comment did not. 36 classes assign one of the two tables; 34 assign the base's.

A second pass on the same commits found six more, five of them prose and one of them dead code.
*"An attribute only the mesh extender declares"* was wrong in four places — `BATTERY` is declared by
**21** attribute enums in the tree; the conclusion survives because the reviewer narrowed the live
table by probe to exactly `MeshExtenderAttributes`, but the sentence did not. *"62 point locators"*
was wrong three times: the live document publishes **61** locator families and 63 data source
families, which is where 62 came from by drift — and the substantive half held, all 61 declare
`dataType` and none marks it required. `InternalPointFormComponent` is the **fifth** component
extending `GatewayFormComponent`, not the third, and 85 lines rather than 80. The `bearerToken`
comment rewritten on 2026-09-28 claimed `PAIRABLE_TYPES` would allow a toggle beside a password box
on its own — it would not, a `switch` is not in that list, and the sentence it replaced was closer to
true. And two "21 sources" baselines survived in this document while row 13 contradicted them at the
bottom of it.

The dead code: removing `sendEmpty: ['contextName']` left the whole **`sendEmpty` mechanism** with no
users — the interface field, its twelve-line doc, the branch in `keep()` and a spec guard. All four
are gone. It existed for exactly one field and D69 closed the rule it worked around; the reason it
existed is recorded in `keep()`'s own comment so that the next person to meet an absent-versus-blank
gateway does not have to rediscover it.

### 14 — `PING.DS` / `PING.PL` (done, 2026-09-29)

Chosen by the user from the four addable types left, after OPC was skipped. The smallest type in the
whole sequence, and the first one where the gateway's validation was **stricter** than what the
layout was about to claim.

**The data source is nothing.** `PingDataSourceVO` adds no field to `PollingDataSourceVO` — it
overrides `getConnectionDescription`, `createPointLocator`, `createDataSourceRT`, `getEventCodes`
(null) and an empty `addEventTypes`, and that is the whole class. Its published property set is
**identical to `VIRTUAL.DS`**, name for name and in the same order, so it takes the same empty
layout entry: `timePeriod` required, `alarmLevels` and `quantize` under Advanced, nothing to
override. The gateway's own form shows name, XID, polling interval with its unit, and edit
permission — which is what the generic form already renders.

`alarmLevels` comes back `[]` on a created ping source and always will, because there are no event
types to override. It is left under Advanced rather than hidden: `VIRTUAL.DS` is in the same
position, so that is a fact about the two types' event tables and not about this row.

**The locator is two fields, and both of them have a rule.** `PingDataSourceDefinition.validate`:

```java
if (StringUtils.isEmpty(pl.getIpAddress())) {
  result.addContextualMessage("ipAddress", "validate.required");
} else if (pl.getTimeout() <= 0) {
  result.addContextualMessage("timeout", "validate.not0");
}
```

Measured, all six: an empty address is 422 *"Required value"*; an omitted address the same; `-5`,
`0`, `null` and an omitted timeout are each 422 *"Cannot be 0"*. So `required` and `min` here are the
gateway's own rules moved forward to where the operator can see them, not a Cortex invention — which
is the distinction row 11 got wrong on `valuePointer` and had to back out. The `else if` and the
message that says "0" for a negative are **D93** and **D94**.

> **`timeout` needs both halves, and the first version of this row gave it only `min`.** Angular's
> `minValidator` returns `null` — valid — for an empty control, so a floor on its own passes an
> untouched box; the null then lands on the REST model's primitive `int` as 0, which is the value
> the floor exists to refuse. The **add** path survived by luck: `keep()` drops the empty value and
> `save()` spreads the seeded `1000` underneath. The **edit** path did not — `keep()` keeps the
> null, and the operator meets the 422 that `min` was supposed to have moved forward. Row 11 had
> already settled this on `timeoutSeconds`; the rule is now a spec — *a floor above zero is always
> paired with `required`* — and a floor of exactly 0 is exempt, since a null becoming 0 is the floor
> being met.

> **The stack's own form has the pair backwards.** Its ping point form marks *timeout* `required`
> and leaves *ipAddress* unmarked — the reverse of the two rules the validator enforces. This is
> the fourth time (W11, W13, and the SNMP labels) that consulting the webapp alone would have
> produced the wrong form, and the reason the sequence reads the Java first.

**Everything else on the locator is fixed by construction.** `getDataTypeId()` returns
`DataTypes.BINARY` and `PingPointLocatorModel.toVO` copies `ipAddress` and `timeout` and nothing
else, so a submitted data type is never read — measured, a point sent `NUMERIC` saves 201 and reads
back `BINARY`. `isSettable()` returns false and `PingDataSourceRT.setPointValue` is an empty method,
so `settable` and `relinquishable` are hidden; measured, both submitted true read back `false` and
`null`. That `toVO` drops them is D91's shape again, harmless here, and noted rather than re-filed.
The published `settable` description names the locators that ignore the field and leaves `PING.PL`
out of the list — **D95**.

**Why `dataType` gets a default here — and the reason first written down was wrong.** The first
draft of this section said the default was what keeps the blanket `required` on a locator data type
from refusing a new point, since this source has an Add button where `MESH_CONTROLLER.PL` has none.
That sentence concedes the premise that kills it: a disabled control is left out of Angular's
validation, so the blanket rule is inert here whether or not a value is present, and there is
nothing for a default to prevent. Measured afterwards: a `PING.PL` point posted with **no
`dataType` key at all** reads back `BINARY`, and so does one posted `NOT_A_TYPE`, because
`PingPointLocatorModel.toVO` never reads the field. The default's real job is smaller and worth
keeping — it gives the disabled select an item to show, instead of a greyed empty box on a field
whose whole purpose is to tell the operator what the point yields.

**One label, shared.** `humanise` renders `ipAddress` as "Ip address"; the stack's own string is
"IP address", and `POE_LIGHTING.PL` carries the same field, so it went in `FIELD_LABELS` rather than
in this type's layout. `timeout` already read "Timeout (ms)" there, which is right for this type for
a reason worth writing down: the value is passed straight to `InetAddress.isReachable(int)`, whose
argument is milliseconds.

**The hints say what the field names do not.** The address hint says a host name works as well as an
address, because `InetAddress.getByName` resolves either and the label does not admit it — measured,
a point on `example.invalid` saves 201. The timeout hint names the floor rather than the refusal, so
the rule reads as a property of the field instead of an error waiting to happen.

*Verified:* layout, schema and form specs green (112 / 41 / 14) and `tsc -p src/tsconfig.app.json`
exit 0; every rule above measured against 5.1.3 on the local instance and every probe row deleted. The on-screen pass is owed with rows 6–13.

### 15 — `POE_LIGHTING.DS` / `POE_LIGHTING.PL` (done, 2026-09-29)

The first type in the sequence whose gateway code has **no validation at all** — both
`PoeLightingDataSourceDefinition.validate` overloads are empty methods — and the first whose
published schema will take a form straight into an HTTP 500.

**`pointType` decides the rest of the point, and arrives as a bare string.** `getDataTypeId()` is a
switch on it (`CHANNEL_LEVEL` → NUMERIC, `POWER_ON_SETTING` → BINARY), `getConfigurationDescription`
prints it beside the channel, and `PoeLightingPointLocatorModel.toVO` calls
`PointType.valueOf(pointType)` on it unguarded. The schema publishes `{"type": "string"}` with no
enum. Measured: the two constants save 201; an **omitted** value is a **500**; an unknown one is a
400 with no field named; and the enum's own display string "Channel Level" is a 400 as well. So the
two-item list Cortex writes out is not a narrowing of the gateway's choices — it is what stands
between an operator and that 500. **D97**, and the fix is already written in the sibling model in
the same package, which types the field as the enum and says why in a comment.

The gateway's own form solves it differently: it renders `pointType` **read-only**, because nothing
there adds a PoE point by hand. Every point on a real controller is made by discovery —
`PoeLightingService.createDataPoints` creates a Channel Level and a Power-On Setting point for each
of the controller's channels, both settable. That is also where the default `settable: true` comes
from: a lighting channel that cannot be set is a light nobody can switch.

**`dataType` is hidden, which is a first.** Every other locator in the sequence either takes the
submitted type, or fixes it at one value and disables it. Here it is derived from another field on
the same form, and the layout language cannot say that — a disabled box would read `NUMERIC` while
the operator selects Power-on setting. Hiding is safe for the mapper's blanket `required` on a
locator data type, because `build` filters hidden properties out *before* `addControl`: there is no
control to fail validation. Measured, the gateway fills the field either way, and discards a
submitted `ALPHANUMERIC` for the `NUMERIC` the point type implies.

**The token is readable, and the obvious fix would not fix it.** `token` is the controller's bearer
credential — `PoeLightingService.createHeaders` does `setBearerAuth(token)` — published with no
`writeOnly`, so it reads back in the clear. That alone is D79's shape. What makes it **D96** and P1
is the second copy: `getConnectionDescription()` returns `ipAddress + ":" + token`, a `readOnly`
string every client renders in its data source list. Marking the field `writeOnly` would leave the
credential fully readable through the description. Both have to change together.

Cortex cannot mitigate this from its side. A layout may not type a field `password` — `keep()` reads
secrecy off the mapper, so masking without the matching empty-drop would let a cleared box wipe a
stored token, and the read carries it anyway. The field stays a plain box and the finding carries
the weight.

**`connectionTimeoutSeconds` is hidden because nothing reads it**, and the decisive evidence is not
the absence of a reference — it is `PoeLightingService`'s `new RestTemplate()`, built with no
`ClientHttpRequestFactory`, so the only HTTP client the module owns has no configurable timeout for
the field to feed. It is nonetheless declared on the VO with a default of 10, mapped both ways,
written once more by discovery, and **offered to the operator as an editable box in the gateway's
own editor** — which is the part of **D98** worth reading, since anyone checking it will find the
field plainly visible there.

Hiding it does not strand a value set in that editor, but not for the reason first written here. An
earlier draft said "a data source saves with `PATCH`, so hiding the field keeps whatever is stored";
the mechanism Cortex actually relies on is the explicit send — `pick` copies the key off the read
model and `keep` passes it through, because `empty` is `null || undefined || ''` and a stored `0` or
`10` is none of those. The `PATCH` merge is a second belt behind that, not the first.

It is **defaulted although it is hidden**, which was missing until the review and looks redundant
until you line the two tuning fields up: they have the identical three-way split — VO 10, REST model
0, discovery 10 — so an add that dropped the key stored 0, and the next person to open that source
in the gateway's own form read a timeout of 0 where every other source reads 10. Cortex should not
write a value into a field it refuses to show.

**Three defaults for `retries`, and we seed the VO's.** The VO initialises 2, the REST model 0, and
the discovery path sets 3. Measured: a POST omitting the field stores 0, which means the first
failure is the last. The VO's own initialiser is what a form building a fresh source should start
from, so the layout seeds 2 and the disagreement is **D99**.

**Stricter than the gateway — and than its own UI — deliberately.** `ipAddress` and `token` are
`required` and `channelId` has a floor of 1. The gateway checks none of them, and its own editor
leaves the first two optional as well, though it does mark `channelId` required. This is the thing
row 11 got wrong on `valuePointer`, so the justification has to be the mechanism and not the
omission: `HttpHeaders.setBearerAuth` has no null check, so a null token ships `Authorization:
Bearer null` and a blank one ships `Authorization: Bearer `, on every call the module makes. An
empty token is not a configuration that means something else — it is a source whose every request
fails authentication. That is the test `valuePointer` failed, where a write-only point with no
pointer genuinely was a configuration. Channel 0 is a channel no controller has.

> **The lockout risk is closed by a placeholder, which is a dependency rather than a guarantee.**
> `required: ['token']` could have made every discovered row unsaveable from Cortex. It does not,
> because discovery takes the token from `systemSettingsDao.getValue(POE_LIGHTING_TOKEN)` and that
> key's definition supplies the literal default `"your-token-here"` — so a discovered source always
> carries something. If that placeholder is ever dropped, this `required` becomes a lockout on every
> row the gateway made. Written down so the next person to touch either end can see the coupling.

`alarmLevels` is non-empty here — communication failure and device failure. An earlier draft called
this "the first type in the sequence with real event types", which is false: `ModbusDataSourceVO`
declares three and Modbus is row 3, and MQTT, SNMP, BACnet and META each declare at least one. Their
URGENT level is not distinctive either — the two-argument `createEventType` already defaults to
URGENT, and only `IGNORE_SAME_MESSAGE` differs from the default duplicate handling. What the row
actually measured, and all it supports, is that this is the first type **instantiated on the bench
gateway** whose `alarmLevels` reads back non-empty, where VIRTUAL, MESH_CONTROLLER, INTERNAL and
PING all read `[]`.

*Verified:* 112 layout specs green and `tsc -p src/tsconfig.app.json` exit 0; every rule above
measured against 5.1.3 on the local instance, probe rows deleted. The on-screen pass is owed with
rows 6–14.

### 16 — `SCRIPTING.DS` / `SCRIPTING.PL` (done, 2026-09-29)

The mirror of `META`: there the script is on the point and the data source is empty, here the script
is on the **source** and each point is one variable the script writes. So this row carries what row
7 put on `META.PL`, and the point layout is two fields.

**The type cannot be round-tripped, and that is the row's headline.** `ScriptDataSourceModel.toVO`
calls `new ScriptPermissions(scriptPermissions)`, whose constructor does `groups.split(",")` with no
null check. `scriptPermissions` is optional in the schema, so:

| request | result |
|---|---|
| a create that omits the key | **500** |
| the same with `""` | 201 — and the row reads back `scriptPermissions: **null**` |
| `GET` that row and `PATCH` its own body back | **500** |

An edit form reads a row and writes it back. The gateway hands it a null and then crashes on it.
**D101**, and the fix is already written in the sibling `MetaPointLocatorModel`, which null-checks
the same call — which is why row 7 could hide the field and do nothing else.

**`sendEmpty` is back, four commits after it was deleted.** It went this morning because D69 took
its only user, `SNMP.DS.contextName`; it is back this afternoon because `scriptPermissions` is a
worse case of the same shape — there an absent key was a 422, here it is a 500. The deletion was
still right on the evidence available, and the restoration is the argument for keeping the
mechanism: this is a shape the gateway produces more than once.

One thing changed on the way back. The old branch only fired on an **add**, because the only known
case was an add-time 422. Here the null arrives on the **edit** path, from the gateway's own read,
so the check now runs before the add-drop and on both paths. A stored non-empty value still falls
through untouched, which is what keeps `META.PL`'s "a hidden field is carried through a save" true.

> **The deleted spec was wrong, and it is not coming back.** It forbade naming a field in both
> `sendEmpty` and `hidden`, reasoning that "a hidden field has no control to be empty". The plumbing
> says otherwise: `pick` copies every schema property present on the model, and
> `GatewayFormComponent` merges its rendered controls **over** the value it was written rather than
> emitting them alone, so a hidden key reaches `keep()` on both paths. There is now a form spec that
> holds that merge in place, because two layout decisions rest on it.

**`scriptPermissions` stays hidden, for row 7's reason.** It names the groups the script runs *as*,
and asking for none is the right thing for a form reachable from a browser to do. Hiding it and
sending `""` are not in tension: the first is the security decision, the second is the crash.

> **But `""` is not "no groups", and this row said it was.** `new ScriptPermissions("")` splits on
> comma and yields a set holding one empty string. The gateway's own `MetaPointLocatorModel` comment
> calls that *"a group nobody holds"* and tests `isBlank` to avoid it — another check scripting is
> missing. Harmless in itself: `Permissions.permissionContains` returns false on an empty query part,
> so the junk group can never match. The consequence that is **not** harmless, and that nothing here
> recorded until the review found it: since the read never returns the stored groups and `toVO`
> overwrites them from what was sent, **every save through Cortex replaces a scripting source's
> permission groups with `{""}`**. A source configured in the gateway's own webapp loses its groups
> the first time anyone edits it from the platform.
>
> There is no fix on this side — the value cannot be read, so it cannot be sent back — and the
> failure is in the safe direction, a script losing privileges rather than gaining them. It is now
> part of D101, where the gateway can fix it: publish the groups in `fromVO`, or accept an absent key
> as "unchanged" the way `SecretFields.merge` already does for credentials.

**`updateEvent` publishes five values and takes three.** `commonValidation` switches on it and
refuses `NONE` and `CRON` — measured, both 422 *"Invalid value"*. An earlier draft added "although
`META.PL` accepts both on the same enum", which is wrong: META's `updateEvent` is an `ExportCodes`
table (`NONE`, the six time periods, `CRON`) and scripting's is the `ContextUpdateEvent` enum
(`UPDATE`, `CHANGE`, `LOGGED`, `NONE`, `CRON`). Two token names coincide; the value sets and the
mechanisms are different, and META's `contextUpdateEvent` is a third table again. The list is narrowed to the three, and the default carries the VO's `UPDATE`,
which the REST model leaves null and `validate` then refuses. **D102.**

**The rule this form cannot express.** A source that is not polling, has no cron pattern, and has no
context variable flagged for update is refused *"scripting.validate.mustUpdate"* — nothing would
ever run it. That reads three fields, one of them on the points below, and a layout has no word for
it; the `polling` hint says it instead. Same shape as `META.PL`'s context requirement, and handled
the same way.

**Three rules on one field.** `varName` must be present, must be a JavaScript identifier, and must
not collide with another point's name *or with a context variable on the source*. Measured as three
distinct refusals. Only the first is expressible, so the hint carries the other two — they matter
before the operator writes the script that uses the name, not after.

**A second field on this type crashes when absent, and it is the most obvious one.** Open Add, fill
in nothing, Save: the source posts with no `script`, `commonValidation` compiles a null, and the
gateway 500s. Found by row 15/16's review. The fix is *not* `required`, which was the reviewer's
first suggestion and which the measurement refuses — `script: ""` saves 201, so an empty script is a
stub the gateway is happy to store and requiring one would forbid it. It is `sendEmpty`, exactly as
for `scriptPermissions`, which makes two fields on one type with the same shape and settles whether
that mechanism earns its place. **D104**, and like D101 it is a null check the meta module already
has: a meta point posted with no script is a clean 422 *"Required value"*.

> **`sendEmpty` and `defaults` are a pair, which nothing was enforcing.** The review's sharpest
> point. `pick()` copies a key from the model only when the model carries it, and on an add the
> model is `gatewayFormDefaults(type)` — so a `sendEmpty` id that is not also a `defaults` key never
> reaches `keep()` on an add, the branch never fires, and the 500 is back. `scriptPermissions`
> worked only because it happened to be seeded. That coupling is now a spec.
>
> Second-order and latent: `keep()` tests the secret set **before** `sendEmpty`, so a field ever
> published `writeOnly` and also named in `sendEmpty` would be dropped rather than sent blank.
> Nothing is in both today, and the order is the right one — a secret must never be posted empty —
> but the comment now says so.

The rest is ordinary: `script` is a bare string that needs the same textarea `META.PL` needs and is
compiled on every save; `logSize`/`logCount` are primitives on the model that lose the VO's 1.0 and
5 (**D103**); `settable` is hidden because `isSettable()` is a hard false though `toVO` still copies
it; `dataType` is a real choice here, unlike PoE's derived one.

*Verified:* 112 layout and 14 form specs green, and `tsc -p src/tsconfig.app.json` exit 0; every
rule above measured against 5.1.3, probe rows deleted. The on-screen pass is owed with rows
6–15.

### 17 — `SYSTEM_ATTRIBUTES.DS` / `SYSTEM_ATTRIBUTES.PL` (done, 2026-09-29)

The last type the Add menu offers, and the smallest source in the sequence: `SystemAttributesDataSourceVO`
extends `DataSourceVO` directly rather than `PollingDataSourceVO`, so there is not even a poll period
— ten common fields and nothing else. `validate(vo, result)` is an empty method. The layout is `{}`,
the fourth in the sequence, and it means the same thing the other three do: the type was read, and
the renderer is ours rather than the old one.

Everything here is on the point. One point is one value the platform holds, and `attributeType` says
what it does with a value written to it: boolean, analog and alphanumeric hold it, **timer** drops
the point to false and schedules it back to true `timerValue` seconds later.

**The attribute type is a typing decision wearing a label's clothes.** Each of the four has a runtime
that answers a fixed value class — `BooleanAttributeRT` and `TimerAttributeRT` a `BinaryValue`,
`AnalogAttributeRT` a `NumericValue`, `AlphanumericAttributeRT` an `AlphanumericValue` — and
`SystemAttributesDataSourceRT.setPointValue` stores whatever `update()` hands back. So the attribute
type and the point's data type have to agree, or every write stores the wrong class. Both of the
gateway's own front ends know this and carry the same three lists:
`AttributeTypeVO.getAttributeTypes(dataTypeId)` in the Java and `dataTypeChange` in the webapp. The
REST path knows none of it — measured, a NUMERIC point with a `BOOLEAN_ATTRIBUTE` is a 201. **D107.**

The Java helper is the odd part: it is public, correct, and **called from nowhere**. Filing D107
costs the gateway three lines because the rule is already written; it just never reached the
validator. Cortex carries the same table as `gatedOptions` on `attributeType`, by `dataType`.

**MULTISTATE is a data type you cannot finish choosing.** There is no `MultistateAttributeRT` at all,
so no attribute type is valid for it — the webapp falls through to an empty list and the Java helper
to an empty array. `createRuntime` *does* parse a multistate start value, but it hands it to
`SystemAttributesPointLocatorRT.currentValue`, and nothing in the gateway reads that field; only
`VirtualDataSourceRT` reads its own locator's. What a system-attribute point starts on comes from
`addDataPointImpl`, through the attribute runtime's `getStartValue`. So a MULTISTATE point is the
wrong class from its **first sample**, not from its first write. It is dropped from the data-type
list here rather than offered with nothing behind it.

> The "starts correctly, then goes wrong on the first write" reading was written into four places on
> 2026-09-29 and corrected the same day: the dead `currentValue` path is what made `createRuntime`
> look like the seeding path. The same dead path is why D105 first named `BinaryValue.parseBinary`
> where the live call is `Boolean.parseBoolean` — same outcome, different function — and why the
> shared start-value gate's comment claimed one parser for two types that do not share one.

**An unknown attribute type is not refused, it is skipped.** `ExportCodes.getId` answers -1 for a
name it does not carry — the 2026-09-27 note's shape again — and three chains keyed on that id have
no default between them: the validator's `if/else if`, `toVO`'s `switch` and `fromVO`'s. So a point
sent no `attributeType` saves 201, stores none of the four attribute objects, and reads back
`attributeType: null, startValue: null`. It then behaves as a boolean starting at zero, because
`getAttribute()` ends `default -> booleanAttribute`. Three lines above the chain, `dataTypeId` *is*
checked this way. **D105**, and the reason `attributeType` is `required` here rather than merely
defaulted: the gate clears the field when the data type moves under it, and a cleared field must not
be submittable.

**Every message this type can produce names a field the form has not got.** `validate` reports
against the VO's nested paths — `booleanAttribute.startValue`, `timerAttribute.timerValue` — while
the REST model flattens all four attribute objects into `startValue` and `timerValue`. Measured,
both. **D106.** It is the reason the two client-side rules on this type are not a convenience: they
are the only place those rules can be stated where the operator can act on them.

**`timerValue` is the one field in the sequence that may carry neither of its rules.** The gateway
refuses `<= 0`, so it wants `required` and `min: 1`, and it can have neither — for two different
reasons that an earlier version of this section ran together as one.

`min: 1` is refused because a hidden `timerValue` is not empty, it is **zero**. The field is a
`private int` on the model and `fromVO` fills it only in the TIMER branch, so every boolean, analog
and alphanumeric point reads back `timerValue: 0` — measured on four probes. A floor would fail the
form the moment the dialog opened, on a control the gate has hidden, for every point that is not a
timer. Not "once someone types a zero and switches type": immediately, and with nothing on screen to
correct.

`required` is refused because it would be **inert**, not because it would fire. The control always
holds a value — 0 on an edit, 1 from the default on an add — and `Validators.required` treats 0 as a
value, which the layout's own `min` documentation says in as many words. The standing rule that a
`required` gated field can strand the form is real; it is simply not what rules `required` out here.

So the floor is carried by the default and the hint. An operator who deliberately types a zero still
meets the 422, and that 422 is D106.

> Two invariants written earlier in this sequence for unrelated reasons — one from `PING.PL.timeout`,
> one from `HTTP_JSON_RETRIEVER` — both point at this field, and the first draft of this section
> credited them with a single mechanism. The review took it apart: the two rules fail differently,
> and the `min` case is worse than was claimed, not the same.

`settable` stays visible, which no other locator in the last five rows managed: `isSettable()`
answers the stored field and `toVO` copies it, measured honoured. A system attribute nobody can write
is a constant, and that is a legitimate thing to want, so it is a choice rather than a hidden false.

*Verified:* 119 layout, 41 schema, 14 form and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0; every rule above measured against 5.1.3 and every probe row
deleted, the instance back at 12 sources. The on-screen pass is owed with rows 6–16.

### 18 — `THERMOSTAT.DS` / `THERMOSTAT.PL` (done, 2026-09-29)

The first of the provisioned-only tail proper, and the row that showed the tail is not 46 pieces of
work. Measured off the schema document's own `families` map: most of the 46 remaining types share a
handful of shapes, and a large block of them is **field-identical to `MESH_CONTROLLER`** — `address`,
`anchorNode`, `location` over `AbstractDataSourceModel`'s eleven, and a locator that is `attributeId` plus the usual
three. Thermostat is one of that block, chosen as the

> **The counts this paragraph first carried — "ten distinct pairs", "23 identical" — were wrong**, and
> the row-18 review caught it. Two mistakes in the measurement: 14 data source and 9 locator schemas
> in the document compose with `allOf` rather than listing `properties`, so reading `properties`
> directly returned nothing for them; and six data source types have a locator that is not their own
> name with `.PL` on the end, so pairing by name mismatched them. With `allOf` resolved and the
> pairing taken from the Java, the 63 published types have **24 distinct shapes**, the mesh-device
> block is **26 types**, and **21** remain after it. Corrected 2026-09-30; row 19 is built
> on the corrected numbers.
>
> **And the mesh-device block is 27, not 26.** The row-19 review found `CURRENT_SENSOR.DS` missing
> from the family: it is field-identical to the rest (checked key by key against `MESH_CONTROLLER.DS`
> with `allOf` resolved), and was skipped because its *point* form is not the family's. So **20**
> types remain after this block, and re-deriving the shapes off the same resolved schema puts them in
> **7**, not 9:
>
> | shape | types | extra fields over `AbstractDataSourceModel` |
> |---|---|---|
> | mesh node | `BACNET_IP_MESH_NODE`, `BACNET_MSTP_MESH_NODE`, `MESH_EXTENDER_MESH_NODE`, `META_MESH_NODE`, `MODBUS_IP_MESH_NODE`, `MODBUS_SERIAL_MESH_NODE`, `POE_LIGHTING_MESH_NODE`, `SNMP_MESH_NODE`, `STUDENT_ASSET_TAG_MESH_NODE` | `controllerAddress`, `publisherId` |
> | light controller | `LIGHT_CONTROLLER_V4`, `LIGHT_DI_CONTROLLER`, `LIGHT_RELAY_CONTROLLER`, `MOKO_BAND` | `address`, `anchorNode`, `location`, `quantize`, `timePeriod` |
> | asset tag | `ASSET_TRACKING_BAND`, `LED_ASSET_TAG`, `STUDENT_ASSET_TAG` | `address` |
> | Modbus slave | `MODBUS_SLAVE_DEVICE` | `controller`, `deviceDefinition`, `slaveId` |
> | Modbus slave, polling | `MODBUS_SLAVE_DEVICE_POLLING` | the three above plus `quantize`, `timePeriod` |
> | virtual switch | `VIRTUAL_SWITCH` | `grade`, `gradeType`, `quantize`, `timePeriod`, `uid` |
> | OPC | `OPC` | `domain`, `host`, `password`, `quantize`, `server`, `timePeriod`, `user` — deferred by the user |
>
> The first shape is the largest and the cheapest: `VIRTUAL_MESH_NODE.DS` is already laid out and is
> that shape, so the nine are a batch like row 19 rather than nine rows. That leaves the light
> controllers, the asset tags and the two Modbus slaves as the only real design work left, and OPC
> out of scope.
representative because it has nine attributes across three data types with seven of them writable,
where a mesh controller has one binary attribute that is not. Whatever the form has to do, this type
makes it do it.

**The attribute list is read out of the Java enum rather than fetched, and that turned out to be
load-bearing.** The gateway publishes `GET /v2/export-code/sensors/thermostat`, annotated *"Read-only
lookup returning the selectable thermostat attribute names used to populate UI dropdowns"*. It is
`Arrays.stream(ThermostatAttributes.values()).map(Enum::name)`. The code table the API actually
validates against is built from `getAttributeName()`, and for one constant the two differ —
`ENERGY_SAVING` carries the name `ENERGY_SAVING_MODE`. So the endpoint whose job is to fill dropdowns
publishes one value in nine that the same gateway refuses. **D113.** The lookup also gives names
only: no label, no data type, no settable flag, and it exists for about eleven of the 23 types.

**D108 is the row's headline, and it is a P1 that belongs to all 34 mesh locator types.**
`MeshPointLocatorVO.ATTRIBUTE_CODES` is one `public static` field; every subclass reassigns it from
its own static initialiser. Row 13 read that as "the last class to initialise wins", settled at boot.
A static initialiser runs at first class **load**, so it is not settled at boot — and this row
flipped it mid-session on purpose:

| step | measured |
|---|---|
| a thermostat point with `SETPOINT_TEMPERATURE` | 201 |
| a `MESH_CONTROLLER.PL` point with `FAN_SPEED` | 201 — a thermostat attribute on a controller |
| one `MESH_SWITCH.PL` point, which loads that class | 201 |
| **the same thermostat point again** | **422** |
| `STATUS`, `TEMPERATURE` on a thermostat | **422** |
| a `MESH_CONTROLLER.PL` point with `ROOM_NUMBER` | 201 |
| `GET /v2/export-code/sensors/thermostat` | still lists all nine |
| `GET` the points saved before the flip | `attributeId: null`, `configurationDescription: "Unknown"` |

Three failures out of one field: valid input refused, invalid input accepted, and stored points that
stop being readable — and since a read-modify-write then posts `attributeId: null`, renaming such a
point fails too. Which type wins is decided by class-load order, which in production means whichever
mesh device the gateway touched last. **Nothing on this side can do anything about it**, which is
worth saying plainly: the client list keeps the form honest about which nine attributes a thermostat
has, and that is the whole of what it can achieve.

**`settable` is shown read-only, which no previous locator in this sequence has done.** Row 13 hid it
because `MeshControllerPointLocatorVO` can never be settable. Here the VO declares its own `settable`,
`isSettable()` answers it, and the provisioner fills it from the enum — the setpoint, the fan mode,
the lock and three more are writable. So the flag carries information the operator wants. It is
disabled because `ThermostatPointLocatorModel.toVO` builds a fresh VO and copies `attributeId` and
`dataType` alone, so a submitted `true` is dropped — measured 201 reading back `false`. That drop is
also an **erasure**: any REST write of a provisioned point clears the flag, and on a thermostat that
costs the ability to write the setpoint. **D109**, D91's shape with a consequence attached.

Two smaller ones. The code table is built from `ThermostatAttributes` while the provisioner uses
`ThermostatAttributesV1_1` for firmware ≥ 1.1.0, which adds `ECO_SETPOINT_TEMPERATURE` — a point the
gateway creates and then cannot name (**D110**). And `configurationDescription`, which the schema
calls a string "the gateway has already translated", comes back as the raw key for attributes with no
bundle entry — measured `dsEdit.inferrixSensors.attribute.roomNumber`, and the thermostat's `status`
has no entry either (**D111**). `dataType` is not checked against the attribute's own (**D112**).

No `defaults` on the locator, unlike row 13: `provisionedPoints` means there is no Add form, and row
13 could name a default only because that type has exactly one attribute. One of nine would be a
guess.

> **The probing left the team's local instance in the D108 state** — it loaded
> `MeshSwitchPointLocatorVO`, so the shared table is the mesh switch's. No row was changed and the
> inventory is back to 12 sources and 106 points, but mesh attribute names read over REST there are
> currently wrong. This section first said a restart would clear it; **it would not**. The ratchet
> starts again at boot and the next call touching a fresh mesh type moves it, so a restart only
> re-rolls which types are broken. Corrected 2026-09-30, and the findings doc now says so too.

*Verified:* 124 layout, 41 schema, 14 form and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0; every rule above read in the Java first and measured against
5.1.3, probe rows deleted. The on-screen pass is owed with rows 6–17.

### 19 — the 24 remaining mesh device types (done, 2026-09-30)

Batched at the user's direction after row 18 measured the shape. One row rather than 24, because
there was one form to build: the schema document's `families` map puts these 24 — plus the mesh
controller from row 13 and the thermostat from row 18 — on **one data source shape and one locator
shape**, 26 types in all. What differs between them is a list.

Two of the 24 were nearly missed, and the row-18 review is why they were not: **a data source's
locator is not always its own name with `.PL` on the end.** `SENSOR_TAG_DOOR_SENSOR.DS` uses
`SENSOR_TAG_DOOR.PL` and `SENSOR_TAG_STROKE_COUNT.DS` uses `SENSOR_TAG_STROBE_COUNT.PL`. Pairing by
name put both in a group of their own with an empty locator, which is exactly what a wrong
measurement looks like when it is not challenged. The layout keys on what the device publishes, so
those two entries carry the locator's own name.

So the layout gained two factories, `meshDeviceSource` and `meshDevicePoint`, and rows 13 and 18 were
folded into the first of them rather than left as two more copies. Their existing specs passed
unchanged, which is the equivalence proof: the DS entries assert `provisionedPoints`, the read-only
address and the row layout, and none of them moved.

**The attribute lists are 104 items read out of 24 `*Attributes` enums — 114 counting rows 13 and 18
— and the reason they are read rather than fetched got stronger.** Row 18 found the gateway's own
dropdown endpoint publishing `Enum::name` where the API validates `attributeName`, differing for one
thermostat attribute. Across all 26 enums there are four, and two of them carry spaces:

| enum | constant | what the wire takes |
|---|---|---|
| `ThermostatAttributes` | `ENERGY_SAVING` | `ENERGY_SAVING_MODE` |
| `SensorTagPIRAttributes` | `OCCUPANCY` | `OCCUPANCY_STATUS` |
| `SensorTagInjectionMouldCountAttributes` | `INJECTION_MOULD_COUNT` | `INJECTION MOULD COUNT` |
| `SensorTagStrokeCountAttributes` | `STROKE_COUNT` | `STROKE COUNT` |

Measured: `"INJECTION MOULD COUNT"` is a 201 and `"INJECTION_MOULD_COUNT"` is a 422. **D115.**

The stroke counter is the type that cannot decide how it is spelt: the data source is
`SENSOR_TAG_STROKE_COUNT.DS`, its locator is `SENSOR_TAG_STROBE_COUNT.PL`, the enum asks for the key
`...attribute.strokeCount` while the bundle defines `...attribute.strobeCount`, and the wire name is
`STROKE COUNT`. Four spellings of one word, and the mismatched key is why that attribute has no
label of the gateway's own.

The I/O card is worse in a quieter way. Its two digital outputs are `D01_STATUS` and `D02_STATUS`
with a **digit zero**, in the constant and the wire name alike, while their translation keys are
`do1Status`/`do2Status` and the labels read "DO1". Measured: the zero is a 201, the letter O is a
422. Our list carries the zero as the value and the letter in the label, which is the only
combination that is both true and usable. **D117.**

**`settable` is the one real difference between these types, and it was read out of each locator VO
rather than assumed.** Three dispositions:

**The first pass got this rule half right, and the row-19 review caught it.** "Does the VO override
`isSettable()`?" is not the question — the question is whether the flag can ever hold anything but
`false`, and that takes the VO *and* the provisioner. Three types inherit the honest accessor and are
still permanently false, because their attribute enum carries no settable flag at all and no
`Create*VO` ever calls `setSettable`. Corrected dispositions, over all 27:

| disposition | types | why |
|---|---|---|
| hidden | 22 | 19 override `isSettable()` to a hard `false`; `MESH_CONTROLLER`, `PEOPLE_COUNT_CAMERA` and `CURRENT_SENSOR` inherit it but nothing ever sets it |
| read-only | `4DI_2DO_CARD`, `PEOPLE_COUNTER`, `THERMOSTAT`, `VAV_CONTROLLER` | the provisioner calls `setSettable(attribute.isSettable())` over an enum with `true` entries, and `toVO` drops it (D109) |
| editable | `MODBUS_CONTROLLER` | `toVO` copies it — the only one of 27 |

`PEOPLE_COUNT_CAMERA` moved out of read-only on that correction: `PeopleCountCameraAttributes` has no
settable argument in its constructor and `CreatePeopleCountCameraDataSourceVO` never calls
`setSettable`, so a read-only checkbox there would have displayed a permanent `false` as if it were a
reading.

That widens D109 from one type to **six by construction** — the four above plus `MESH_CONTROLLER` and
`PEOPLE_COUNT_CAMERA`, which inherit the same accessor — but it only **loses information on the
four**, because on the other two the erase overwrites `false` with `false`.
`ModbusControllerPointLocatorModel` is the worked example of the fix: it already does what the other
six need.

Twenty of the 130 attributes have no English bundle entry, so `configurationDescription` hands those
back as a raw translation key — the whole VAV controller bar two, the thermostat's `STATUS`, and all
five of the current sensor's all-phase attributes, whose keys are in **no bundle at all** — they
match zero properties files in the whole stack, while the eleven per-phase keys are in both English
and French. **D116**, which is D111 counted properly. Our labels for those twenty are ours, and a
spec fails if one of them ever starts with `dsEdit.`.

**A `CURRENT_SENSOR.PL` was added after the review, and it is the one point in this family with a
field worth editing.** Sixteen attributes over two enums (eleven per-phase, five whole-supply), both
loaded into the same shared `ATTRIBUTE_CODES` table, plus `phaseId` and `ctId`. `phaseId` is routing —
`CurrentSensorDataSourceRT` keys `attributePhaseMap` on it — so it is read-only like `attributeId`.
**`FREQUENCY` is not a measurement. D128, P2.** `CurrentSensorDataSourceRT
.updateCalculatedDataPoints` answers it with `new Random().doubles(49.9, 50.1).findFirst()
.orElse(50)` — a fresh random sample every update, with no input from the device, stored and
historised exactly like the fifteen real readings beside it. A supply that has actually drifted to
48 Hz reports as healthy, and an alarm on the point can only fire by chance. Labelled "Frequency
(simulated)", the same treatment `32_A` got: the value stays because a provisioned point may hold
it, and the label stops it being read as a reading.

**And deleting a phase's last point NPEs the mesh listener. D129, P3.** `powerSensor:83` and
`powerSensorExtended:146` both put the `try` *inside* the `forEach` lambda, so the null
`attributePhaseMap.get(...)` that follows removing a phase's last point throws out of the listener
method entirely, taking the other phases' updates in that frame with it. `provisionedPoints` removes
the Add button but not Delete, so this is reachable from Cortex.

`ctId` is **calibration**, and an installer has to be able to correct it, so it is left editable —
the only editable non-permission field on any provisioned mesh point. The CT list is also reordered:
the enum publishes `120_A` after `1200_A`, which reads as a defect on a picker an installer uses to
say which clamp is on the wire.

> **The first version of this row overstated what `ctId` does, and the row-19a review caught it.**
> `CTConversionUtil.ctConversionTable` has exactly one call site,
> `CurrentSensorDataSourceRT.powerSensor`, inside the `attributeId == CURRENT` branch. Every other
> attribute applies its own `getConversion()` to the device's raw value. So the rating reaches **four
> of the sixteen** — `CURRENT` directly, and `TOTAL_POWER`, `TOTAL_APPARENT_POWER` and `KWH` through
> `PointValueAttributeMap`, which derives all three from `voltage * current`. `PF` is `cos(phase)`
> alone and does not follow it. The row also named a method, `readAttributes`, that does not exist on
> that class. Both corrected in the JSDoc, the hint and the handover doc.
>
> **And `32_A` does not work at all. D125, P1.** `ctConversionTable` has branches for seven ratings
> and none for 32, so it returns its `-1.000` initialiser and the point reports a constant −0.001 A,
> taking the three derived attributes negative with it. `env.properties` ships seven factors and no
> `32A`. `CT_CODES` publishes the value regardless, so `validate` accepts it — and it was the **first
> item** in this row's picker. It is kept in the list, because a stored point may already hold it and
> a dropped value renders as a blank box, but relabelled "32 A (not supported)" with the consequence
> spelled out in the hint. (Same method, smaller: the 500 A code default is 14.929 where
> `env.properties` ships 21.173, so an install missing that property reads ~30% low.) Nothing on the gateway checks that the attribute and
the phase agree: `CurrentSensorDataSourceDefinition.validate` checks the four locator fields one at a
time against their own code tables and never against each other, so a whole-supply attribute on
`PHASE_2` saves and is then simply never routed. The hint says so, because a read-only field cannot.

*Measured against the live gateway, source `ZZ_CS_PROBE` and three points, all deleted after:* the
source POSTs 201 with `address`/`anchorNode`/`location` and nothing else; `TOTAL_POWER` on `PHASE_2`
is a **201** (D119); a `ctId` of `99_A` is a **422** naming `ctId`, so the rating is genuinely
checked; `settable: true` saves and reads back **false**, which is why it is hidden; `dataType: IMAGE`
is a 201, the same as everywhere else in this family; and a `PUT` changing `ctId` from `100_A` to
`500_A` **round-trips**, so the one editable field is editable from our dialog. `TOTAL_POWER` and
`KWH` both read back `configurationDescription` as their raw translation key, confirming the five new
D116 rows.

**Two of these 27 types cannot hold a point at all, which the row-19 review's count question turned
up.** `DustbinLevelSensorDataSourceDefinition` and `SoapDispenserSensorDataSourceDefinition` both
open their point `validate` with `if (!(dsvo instanceof DistanceSensorDataSourceVO))`, naming the
distance sensor's VO; both types extend `MeshDataSourceVO` directly and are not subclasses of it, so
the condition is always true and the refusal is always added. Measured: the source POSTs 201 and a
point on it is a **422** on `dataSourceId`. `PaperTowelLevelSensorDataSourceDefinition`, the third
copy of the same file, names its own VO and its point POSTs 201 — the control. **D120, P1.** Our
layout for the two is correct and inert; nothing on this side can make a point exist.

**D108 was caught in the act on 2026-09-30, which is worth recording because it changes how every
measurement in these rows should be read.** The identical request — a `CURRENT_SENSOR.PL` point with
`attributeId: "CURRENT"` — returned **201** earlier in the session and **422 "Invalid value"** later,
with no restart and no code change between them. Probing the same source further showed `HEARTBEAT`
and `BROADCASTING_ENABLED` accepted and everything else refused, which is `WristBandAttributes`
exactly: the row-21 probes had created and deleted a `MOKO_BAND.PL` point, initialising
`MokoBandPointLocatorVO` and reassigning the one shared `ATTRIBUTE_CODES` static. A current sensor on
that instance can now hold only a wristband's four attributes.

So a 422 on an `attributeId` anywhere in these rows means "not in whichever table is loaded right
now", not "not a valid attribute of this type". Every 422 relied on above was taken with a positive
control in the same run — D121's `STATUS` 201 against `DIM_VALUE` 422, D123's `DIM_VALUE` 201 against
three 422s, D120's refusal naming `dataSourceId` rather than `attributeId` — which is what keeps
those findings sound. It also means **nothing in this family can be regression-tested against a live
gateway** until D108 is fixed, which is the strongest argument for fixing it.

**What this row does not fix, and cannot:** D108. All 27 of these types share the one mutable
`ATTRIBUTE_CODES` static, so on any given gateway most of them cannot validate their own attributes.
The lists here are right about what each device reports; whether the gateway will accept one depends
on which mesh class loaded last.

*Verified:* 131 layout, 41 schema, 14 form and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0. Every attribute list extracted from the Java and two of the
surprising ones checked live both ways; the current sensor's source, its three probe points and every
earlier probe row deleted, the instance back at 12 sources and 106 points. The `settable` disposition
of all 27 types re-derived from the provisioner as well as the VO after the row-19 review, which
moved two types and corrected D109. The on-screen pass is owed with rows 6–19a.

### 20 — the ten mirrored mesh node types (done, 2026-09-30)

The largest batch left, and the cheapest: one shape for the sources, one for the points, and
`VIRTUAL_MESH_NODE` was already laid out as both. It was refactored into the two new factories rather
than left as a third copy, the same move rows 18 and 19 made with the mesh controller and thermostat.

A mesh node is not a device the gateway talks to. It is another gateway's data source, mirrored onto
this one over the mesh. `controllerAddress` is the mesh controller it hangs off and `publisherId` is
the publisher whose points it carries; **the pair is the match key**, tested by every one of the ten
runtimes as `model.getNodeAddress() == vo.getControllerAddress() && model.getControllerReportingData()
.getPublisherId() == vo.getPublisherId()` before a frame is accepted. Both read-only for that reason,
and paired on one row because they are one fact.

**Ten sources, nine locators.** `MODBUS_IP_MESH_NODE.DS` and `MODBUS_SERIAL_MESH_NODE.DS` share
`MODBUS_MESH_NODE.PL` — the same "locator is not the source's name with `.PL` appended" shape that
hid two members of the mesh device family until the row-18 review. Checked from the Java, not by
name, as that review required.

**This row tried to reverse a row-12 decision and was wrong. The reversal is withdrawn.**

The attempt: `VIRTUAL_MESH_NODE.PL` had `settable` read-only on the reasoning that the radio decides
it, and row 20 made it editable, arguing that every `Create*MeshNode*VO` hardcodes
`setSettable(false)`, so if this form could not change the flag nothing could.

**That argument was false, and the row-20 review took it apart.** The `Create*VO` path creates only
the heartbeat point. The mirrored points are created by the *runtime*, and eight of the ten RTs
override `createDataPoint` with `locatorVO.setSettable(data.isSettable())` — the radio deciding it,
which is exactly what row 12 said. Measured: of 57 live `*_MESH_NODE.PL` points, **18 carry
`settable: true`**, and every one is a digital output. "A DO is writable because it is a DO" was
right.

**Editable was destructive as well as wrong.** The dialog posts the whole `pointLocator` and `toVO`
copies the field, so a save with the box unticked overwrites a flag the radio set. Measured on a
probe point: `PUT` with `settable: false` on a point reading back `true` answers **200** and reads
back **false**, permanently — `MeshControllerNodesDataSourceRT.dataPointDoseNotExist` only re-creates
an `attributeId` with no point at all, so nothing restores it. And the flag is not only a UI gate:
`BACnetPublishedPoint.getObjectType(dataTypeId, isSettable)` picks the BACnet object type the gateway
republishes to third-party BMS clients from it, and `CpmUtility` and `ScriptDataSourceRT` gate
scripted writes on it. One mis-tick would have changed what a third-party BMS sees.

`settable` is read-only again, shown rather than hidden because the flag is worth reading, with a
hint naming the radio as its source and the BACnet republish as its other consumer.

What the reversal correctly took with it, and which stays: the spec *"gives a provisioned source
nothing for an operator to fill in"* asserted that a provisioned locator has **no** editable field.
That rule was already false when it was written — `MODBUS_CONTROLLER.PL` leaves `settable` editable
on a provisioned source — and it is the wrong rule anyway, because `provisionedPoints` removes the
Add button, so there is no form to be empty. It was replaced with the rule that does have to hold: a
provisioned locator never marks a field `required` that it also hides or disables, since a disabled
control is left out of Angular's validation entirely and there is no Add path to seed a default from.
The new version checks every provisioned type rather than one.

**One of these ten layouts is probably dead today, and that is D127's third consequence.** Both
mesh extender model types exist and are distinct — `MESH_EXTENDER_MESH_NODE.PL` and
`MESH_EXTENDER.PL` — and the mapping registers on `fromClass()`. Since the provisioner builds a
`MeshExtenderPointLocatorVO`, a provisioned mesh extender mesh node's points serialise as
`MESH_EXTENDER.PL`, and Cortex keys the locator form on `pointLocator.modelType`. So those points
open the mesh *device* form and `GATEWAY_FORM_LAYOUTS['MESH_EXTENDER_MESH_NODE.PL']` is never
reached. The layout stays: it becomes correct the moment the gateway is fixed, and one unused entry
costs nothing.

**Three findings filed from this row**: D126 and D127 on the two types that do not create their
points like the other eight, and **D130 (P2, latent)** on `getDataQueue`, which covers 10 of
`AttributeDataType`'s 43 constants with no `default:` — so for the other 33 the gateway sends a write
frame carrying a header and no value. Worth stating carefully, because it is tempting to claim this
row mitigated it and it did not: `isSettable()` is a sound universal gate, but on this family the
flag is set straight off the reported frame next to `type`, with nothing correlating the two, and one
of the initiators that passes the gate is a **BACnet WriteProperty from a third-party BMS** — which
the same flag put in the output object class. Making `settable` read-only removes the platform as a
*source* of the flag, which is worth doing on its own, and changes nothing about D130. It is latent
rather than live only because every `settable: true` point today is `BOOL`, which the switch covers. `CreateStudentAssetTagMeshNodeVO` calls `setAttributeId` twice, the second time with
`attribute.getType().getDataTypeId()` — a data type id — so every student asset tag mesh node point
is stored under the wrong attribute id; it also never sets `type`, which is a non-null contract on
that locator. `CreateMeshExtenderMeshNodeDataSourceVO` builds a `MeshExtenderPointLocatorVO`, the
mesh *device* locator, onto a mesh *node* source.

*Measured against 5.1.3, every probe row deleted:* the source POSTs 201 with `controllerAddress` and
`publisherId` alone; `publisherId: 0` is a **422** naming `publisherId`, as `validate` says; a point
with `settable: true` saves and reads back **true**, and a `PUT` preserves it — which is what
distinguishes this family from the mesh device family, where the same field reads back false, and
which is also why an editable checkbox here was dangerous rather than merely useless.
`configurationDescription` reads back `"Unknown"` for an attribute id the gateway has no name for,
which is why it stays hidden.

*Verified:* 133 layout, 41 schema, 14 form and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0. The on-screen pass is owed with rows 6–19a.

### 21 — the four light controllers (done, 2026-09-30)

`LIGHT_CONTROLLER_V4`, `LIGHT_DI_CONTROLLER`, `LIGHT_RELAY_CONTROLLER` and `MOKO_BAND`. The band is a
wristband rather than a light, but it is commissioned by the same light-commissioning run and
publishes the same two schemas, so it belongs in the row.

The point form is the mesh device family's exactly, so `meshDevicePoint` serves all four unchanged.
The source is the mesh device shape plus `quantize` and `timePeriod`, so `lightControllerSource`
reuses `meshDeviceSource` and pins the poll period under the address row. Neither polling field needs
anything else: the mapper already seeds every `timePeriod` with five minutes, and `quantize` carries a
schema description it renders as a hint.

**Two of the four were briefly made `editable`, and that was the row-20 mistake starting again.**
`LightControllerV4PointLocatorVO` and `LightRelayControllerPointLocatorVO` both inherit the honest
accessor and both models' `toVO` copies `settable`, so an edit sticks — which is exactly what made
the mesh node version destructive. Corrected the same day, before the row-21 review ran:

- `settable` here comes from `attribute.isSettable()`, a constant on the attribute enum. It describes
  what the attribute **is**, not what this particular device supports, so there is no case where an
  operator knows better. `Upgrade5` re-derives `DI_STATUS`'s flag from the enum the same way, which
  is the gateway's own team treating it as enum-derived.
- The flag is not only a UI gate. `BACnetPublishedPoint.getObjectType(dataTypeId, isSettable)` picks
  the BACnet object type the gateway republishes to third-party BMS clients, and `CpmUtility` and
  `ScriptDataSourceRT` gate scripted writes on it. An operator contradicting the enum changes what
  someone else's system sees.

So both are `readonly` — but read-only in a **different sense** from the four mesh device types, and
the factory now distinguishes them. Those four are `readonly-erased`: `toVO` drops the field, so a
save erases it (D109), and their hint says so. These two round-trip it correctly. Same control, two
different truths behind it, and the hint that claims the wrong one is a lie either way.

`MODBUS_CONTROLLER` remains the **only** `editable` in the family, and it earns it: its attribute
enum carries no settable argument and no provisioner, upgrade or event listener writes the flag for
that type, so the form is the only source there is. The Modbus *slave* types are not like it —
`ModbusControllerQueriesDaoEventListener` sets their flag from `attribute.isWriteable()`, so they
will be read-only when row 23 lays them out. Recorded here so that row does not have to rediscover it.

The other two light controllers override `isSettable()` to a hard `false` and stay hidden.

**Four findings, all the gateway's, all filed and all measured.**

- **D121 (P2)** — `RelayControllerAttributes.DIM_VALUE` declares `attributeName` `"STATUS"`. A fifth
  instance of D115, and the worst, because `STATUS` is a real attribute name on the thermostat and
  several sensor tags. Measured: `STATUS` is a 201 whose `configurationDescription` reads *"Dim
  Value"*; `DIM_VALUE` is a 422. Our list carries `STATUS` as the value under the label "Dim value".
- **D122 (P3)** — `RelayControllerAttributes.DI_STATUS` points at the *lux value's* translation key,
  so the gateway labels two different attributes "Lux Value". Measured: a `DI_STATUS` point reads
  back `configurationDescription: "Lux Value"`. Ours says "DI status" — the one place in the file
  where our label is deliberately not the gateway's, because two identical entries in one picker is
  worse than one deviation.
- **D123 (P1)** — a V4 node's attributes depend on its firmware: `LedControllerVersionHandler
  Definition` picks one of three enums (7, 8 or 11 attributes), while the locator VO's static
  initialiser loads only the first. So the gateway provisions points its own validator refuses.
  Measured: `DIM_VALUE` 201, `PIR_TRIGGER_COUNT` / `SWITCH_STATUS` / `BURN_HOURS` each 422. The ids
  collide too — `PIR_TRIGGER_COUNT` is 8 in the 1.3 enum and 9 in the 2.0 one, where 8 is
  `SWITCH_STATUS` — so a firmware upgrade silently re-points stored points. Our list is the seven
  that work; a point provisioned on newer firmware renders with an empty attribute box, which is the
  gateway's gap showing through rather than ours.
- **D124 (P2)** — `LightControllerV4PointLocatorVO.isSettable()` answers `super.isSettable() ||
  DIM_VALUE.isSettable()` for the dim value, and the right-hand side is a constant `true`. Measured:
  POSTing `settable: false` on a `DIM_VALUE` point reads back `true`. The field stays editable,
  because it is a real choice for the other six, and the hint says the dim value is writable whatever
  the box shows.

*Measured against 5.1.3, every probe row deleted:* all four sources POST 201 with the five fields;
`LIGHT_RELAY_CONTROLLER` with `settable: true` reads back **true**, `LIGHT_DI_CONTROLLER` and
`MOKO_BAND` read back **false** — which is the disposition rule holding on the wire.

*Verified:* 133 layout, 41 schema, 14 form and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0. The on-screen pass is owed with rows 6–20.

### 22 — the three asset tags (done, 2026-09-30)

`ASSET_TRACKING_BAND`, `LED_ASSET_TAG` and `STUDENT_ASSET_TAG`. The smallest row left, and the first
since row 19 that turned up no new gateway defect.

The source is `address` over the common eleven and **nothing else** — not `anchorNode`, not
`location` — so it cannot reuse `meshDeviceSource` and gets a three-line `assetTagSource` instead. A
tag is carried rather than installed: it has no zone to record and is never a mesh anchor. The point
form is the mesh device family's exactly, so `meshDevicePoint` serves all three unchanged.

**The `settable` provenance was checked before the layout was written this time**, which is the
lesson rows 20 and 21 paid for. `LED_ASSET_TAG` is the only one where the flag is real:
`LedAssetTagPointLocatorVO.isSettable()` returns the stored field honestly, and `CreateLedAssetTagVO`
sets it with `attribute.getAttributeName().equals(LedAssetTagAttributes.LED_STATUS.getAttributeName())`
— an LED is the one thing on a tag you can write to. But `LedAssetTagPointLocatorModel.toVO` copies
`attributeId` and `dataType` alone, so a save through this dialog erases it. That makes it
`readonly-erased`, the plainest case of D109 in the file. The other two override `isSettable()` to a
hard `false` and are hidden.

`STUDENT_ASSET_TAG` publishes a second locator, `STUDENT_ASSET_TAG_MESH_NODE.PL`, which belongs to
the mirrored-node family and is laid out in row 20. This row is the device's own.

Two more D116 rows: `dsEdit.inferrixSensors.attribute.charging` (the tracking band) and
`dsEdit.inferrixSensors.attribute.ledAssetTag.ledStatus` (the LED tag) have no English entry. The
tracking band's is worth a note — the *wristband*'s `CHARGING` uses a different key,
`…attribute.wristBand.charging`, which does exist, so this is a near-miss of the same shape as
`strokeCount`/`strobeCount` rather than a plain omission.

*Measured against 5.1.3, every probe row deleted:* all three sources POST 201 carrying `address` and
none of `anchorNode`, `location`, `quantize` or `timePeriod`; a `LED_ASSET_TAG` point submitted with
`settable: true` reads back **false**, confirming D109 on the wire for this type; and `LED_STATUS`
reads back `configurationDescription: "dsEdit.inferrixSensors.attribute.ledAssetTag.ledStatus"`, the
raw key, confirming its D116 row.

> **One probe of mine proved nothing and is recorded as such.** A follow-up `CHARGING` point on the
> tracking band came back 422, which looks like evidence and is not: the LED and student tag probes
> had instantiated their own locator classes in between, so the shared `ATTRIBUTE_CODES` table had
> moved. It is exactly the trap the D108 note at the top of the handover doc describes, walked into
> by probing without a positive control in the same run. The tracking band's missing label stands on
> the bundle grep, which is direct; the wire confirmation is simply not available.

*Verified:* 133 layout, 41 schema, 14 form and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0. The on-screen pass is owed with rows 6–21.

### 23 — the two Modbus slave device shapes (done, 2026-09-30)

`MODBUS_SLAVE_DEVICE` and `MODBUS_SLAVE_DEVICE_POLLING`, which differ by `quantize` and a required
`timePeriod` and nothing else. Their two locators are identical apart from their class names, so this
row is two shared layout objects rather than four.

Neither source type is in the Add menu — both definitions answer `isEnabled() == false`, so
`DatasourceService.getDefinitions` filters them out. The only thing that makes one is
`ModbusControllerQueriesDaoEventListener.handleDaoEvent`, which creates the source *and every point it
will ever have* when a Modbus query mapping gains a slave. Every value on both forms was therefore
assigned by the gateway, which is why nothing on either is editable.

**This row needed a new layout key, and that is a deviation from "a layout only ever hides, narrows,
relabels, moves, locks, requires, hints or floors" that is worth stating plainly.** The reason is
D131: `ModbusControllerSlaveDeviceDataSourceModel.toVO` never sets `controllerId` — the line is in the
file, commented out, and the polling model never had it — while `BasicVOModel.toVO` builds a fresh VO
and `DatasourceService.update` replaces rather than merges. `controllerId` is in the serialised blob,
so **any** write orphans the device from its controller, including a `PATCH` that changes only the
name. Per-field `readonly` cannot help: a disabled control still round-trips on a save, and the loss
is server-side regardless of what we send.

So `unsavable` was added — a locale key that makes the dialog open read-only with the reason printed
above the form. It is not a stronger `readonly`; it is for a type whose own REST surface destroys the
row on write, and a spec asserts it stays on exactly those two. It reuses the treatment an event
handler that runs commands already gets (`readonly: this.readonly || runsCommands`, `readonlyNote`),
so the mechanism is precedented even though the flag is new. Enabling and disabling still work from
the table, because `/v2/data-source/enable-disable/{xid}` is its own route and never builds a model.

**Four things were checked before trusting the new key, because a flag that only looks like it
disables a form is worse than no flag.** The Save button is genuinely gone —
`@if (!data.readonly)` wraps it and Cancel becomes Close, so there is no submit path, not merely a
discouraged one. The key can never be device-controlled: `gatewayFormLayout` reads the table through
`Object.prototype.hasOwnProperty.call`, so a gateway naming its model type `constructor` gets
`undefined` rather than a function, and the value handed to `translate.instant` is always a constant
from this repository. The note renders through interpolation, so it is `textContent` and cannot carry
markup. And a hidden field survives a save: controls are built only for `shown`, which excludes
`layout.hidden`, so a hidden id never appears in `form.value`, never reaches `keep()`, and the stored
model spread underneath it is what goes back — which is what makes hiding `controlCommand` in row 24
safe rather than destructive.

The points are a different matter and are left savable: their `toVO` copies all five fields it
carries, which is the D109 pattern *not* happening. Every locator field is still read-only, because
all five are assigned from the register map — the attribute id and point number index a map that
lives on the controller, the interval is the provisioning query's, the data type is the attribute's,
and `settable` is `attribute.isWriteable()`. `relinquishable` is hidden, being the one field `toVO`
does not carry.

`deviceAttributeId` stays a bare number. The gateway's own
`/v2/modbus/queries/device-types/attribute/{deviceType}` answers `attributeType` to `name` and the
locator stores the **id**, so there is no route by which we could label it — D133, which also answers
500 for every device type on the instance we have.

*Measured against 5.1.3:* the two 500s and the empty `modbusDeviceDetails` table are live
(`GET /v2/modbus/device` → `{"items":[],"total":0}`). **D131 and D132 are static findings and are
written down as such** — there is no Modbus controller and no slave device on the instance, so
nothing could be round-tripped. The path is a commented-out assignment, a fresh VO and a replacing
update, which is unambiguous to read, but reading is not measuring and the doc says which it is.

*Verified:* 136 layout, 41 schema, 14 form, 13 model and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0. The on-screen pass is owed with rows 6–22.

### 24 — the virtual switch (done, 2026-09-30)

The last of the 148 model types, and the one that turned out not to be a data source at all.

The record lives in the `virtualSwitches` table.
`VirtualSwitchService.createDataSourceAndDatapoint` makes a data source and one `Command` point to
mirror it, and `updateDataSource` rewrites that source from the record whenever the switch is
edited — so `uid`, `gradeType` and `grade` are copies, and an edit through the data source form is
overwritten the next time anyone saves the switch. They are shown and locked, with hints saying
where the real field is.

They are worth showing because they are the entire behaviour. `VirtualSwitchDataSourceRT.setPointValue`
broadcasts with `vo.getGradeType().value()` and `vo.getGrade()` — which level of the location
hierarchy, and which id within it. The source is the address.

`quantize` and `timePeriod` are hidden. The schema composes `AbstractPollingDataSourceModel`
through a `@Schema(allOf = ...)` annotation, but the model extends `AbstractDataSourceModel` and the
VO extends `DataSourceVO` — neither is the polling type, there is no setter for either field, and
`FAIL_ON_UNKNOWN_PROPERTIES` is off, so both are dropped in silence. `timePeriod` is marked
**required** in the component the annotation pulls in, so a form that showed it would demand a value
that goes nowhere. D138.

The grade type list is written out from the enum because its last two constants,
`NULL` (wire value 255) and `NULL_ZERO` (0), share one translation key. Labelling both "None" the
way the gateway does would put two identical entries in one picker while the choice between them
changes what goes out on the mesh. "None (255)" and "None (0)" are ours — the second label deviation
in the whole exercise, after `DI_STATUS`. D136.

**The point form is inert, and the layout says so by hiding its only editable field.**
`VirtualSwitchPointLocatorModel.toVO` hardcodes `setSettable(true)` and
`setDataTypeId(DataTypes.MULTISTATE)`, so the two writable fields the schema publishes are read and
thrown away (D139) — both are shown locked, because both are true statements about the point.
`controlCommand` is the third, and a search for `getControlCommand` across the whole gateway, tests
excluded, returns two hits, both inside the REST model's own `fromVO` and `toVO`. Nothing reads it:
the command issued comes from the set *value*, which `setPointValue` switches on to pick a
brightness (D140). Offering an operator a command that is never issued is worse than showing
nothing, so it is hidden — safe because the dialog spreads the stored model before the rendered
fields, so a value a point already holds is carried through the save untouched.

*Verified:* 139 layout, 41 schema, 14 form, 13 model and 17 service specs green, and
`tsc -p src/tsconfig.app.json` exit 0. Nothing was written to the live gateway for this row: the
whole of it is readable from the Java and the published schema, and there is no virtual switch on
the instance to round-trip. The on-screen pass is owed with rows 6-23.

### Saving a point that has nothing to save (decided 2026-09-30)

An open question since row 12, closed with a measurement.

Cortex used to `PUT` every point update carrying the whole `pointLocator`. That is what makes the
gateway's D109 bite: five locator models' `toVO` builds a fresh VO and copies `attributeId` and
`dataType` alone, so the `settable` their own provisioner set is erased by the act of saving. `PATCH`
looked unavailable because it refuses any body carrying a `pointLocator` outright — Jackson's
`readerForUpdating` cannot merge into a polymorphic member (D73).

The way through is that the two facts fit together rather than conflicting. **Measured on 5.1.3
against a live `VIRTUAL_MESH_NODE.PL` point:** `PATCH {"name": "HEARTBEAT"}` answers 200 and the
stored locator reads back byte-identical, every field included. A locator nobody sends is a locator
nobody touches.

So `saveDataPoint` now patches when the body has no `pointLocator` and puts when it has one, and
`editPoint` leaves the locator out when the form offered **no editable field** — read off the layout,
not by diffing values. The layout is a statement about what the form offered, so a field that was
never editable cannot have been edited and omitting it can lose nothing. Diffing would also have
caught a form the operator opened and closed unchanged, at the price of dropping a real edit whenever
the comparison was wrong; that trade was offered and declined.

This reaches all five D109 types (`4DI_2DO_CARD.PL`, `LED_ASSET_TAG.PL`, `PEOPLE_COUNTER.PL`,
`THERMOSTAT.PL`, `VAV_CONTROLLER.PL`) plus the rest of the mesh family, whose locators are fully
locked. A spec asserts every one of the five keeps all five of its fields locked or hidden, because
making one editable would quietly put the locator back in the body and bring the erase with it.

**It does not reach `INTERNAL.PL`,** and that is worth saying so the claim is not read too widely.
That type leaves `monitorId` editable, so its locator is still sent and D84's reset of `dataType`
still happens on any save. The rule is per type, not per edit: a type with one editable locator field
sends the whole locator however little the operator changed.

Nothing here fixes D109 or D84 — both are the gateway's `toVO`. What it does is stop Cortex being a
source of either on the types where it had nothing to gain by sending the locator at all.

## Per-type components

Settled 2026-09-25, after the question was raised directly: **is one renderer for 148 model types
the right shape?**

Measured on the live schema document: 148 model types, of which 124 are data sources and point
locators — and those 124 have only **40 distinct field sets** between them. 96 of the 124 share a
field set with another type. A component per type would be ~296 files, ~190 of them duplicating a
sibling, and it would move the field list out of the gateway's schema and into Cortex, where a
gateway that adds a field silently disagrees with the form. `gateway-form.component.ts` has zero
references to `modelType`: the per-type knowledge is a lookup table, not a switch.

**But a descriptor cannot express behaviour**, and some types need it — `attractionPointXid` has
to query the gateway for numeric points and offer them. So the shape is ThingsBoard's own:
`WidgetTypeDescriptor` carries both `settingsForm?: FormProperty[]` (schema-driven, the default)
and `settingsDirective?: string` (a named per-type component), with 95 hand-written settings
components sitting beside the generic form. A widget opts into one only when it needs one.

**Decision.** Generic renderer stays the default. A type that needs behaviour gets a component
extending `GatewayFormComponent`, which inherits its template, its form group and its layout
handling and adds only what the descriptor cannot say.

**Built 2026-09-25, with the one consumer that justified it.**
`VirtualPointFormComponent extends GatewayFormComponent` is 60 lines: it asks the gateway for
every numeric point and supplies them as the option list for `attractionPointXid`, which was a
text box an operator had to paste an xid into. The base gained one hook —
`protected runtimeOptions()`, consulted on every layout pass beside the existing `gatedOptions`,
so a list arriving after the form is on screen turns a text box into a select without disturbing
anything the operator has typed.

The dialog chooses the component with a `@switch` on the locator's model type rather than a
registry: there is one entry, and a component created through `ngComponentOutlet` would need its
value binding wired by hand. That becomes a registry if the list outgrows a screenful.

**Verified.** Numeric / Attractor on a `VIRTUAL.PL` point offers 27 options on gateway 155 —
exactly what `GET /v2/data-point?eq(dataType,NUMERIC)` returns — sorted by name, and the payload
carries the **xid** (`internal_num_mailing_lists`), not the label. The save was captured at the
wire and blocked; a REST read confirms the point is still `BINARY`/`NO_CHANGE` with
`attractionPointXid: null`.

The gateway's own picker is worse than it looks, and W14 records it: its query is
`'?eq(dataTypeId,' + typeId` with no closing paren, so it returns *every* point on the gateway.
Closing the paren would return none — `dataTypeId` is not a filterable property; `dataType` is,
by name.

**The fourth, 2026-09-27: `MetaPointFormComponent`**, and the first whose list does not go on a
property of the model. A meta point's `context` is an array of `{xid, variableName, contextUpdate}`
delegated whole to `tb-dynamic-form`, so the field that needs the list is one level down. The hook
took a second key shape rather than a second hook: `runtimeOptions()` may now answer
`'context.xid'`, and the renderer puts that list on the array property's own `properties`, which is
what `tb-dynamic-form` clones for each row. Every row gets the select, rows added afterwards
included, and the base component still knows nothing about how a row is drawn.

Applied once and guarded on the list's identity, because `delegatedProperties[id]` has to keep its
reference between change-detection passes or the form inside it is rebuilt under the operator. That
guard is the part worth a test, and it has one.

Every data type, unlike `VIRTUAL.PL`'s numeric-only attraction target: a script reads a binary
point as usefully as a numeric one.

### The tree did not compile for three commits, and the checks in this document did not notice

Found by row 14's review, 2026-09-29. `363d3e15fd` removed `keep()`'s third parameter and fixed the
call at `gateway-model-dialog.component.ts:209` but not the one at `:216`, which passes a locator
layout. From that commit through `7fe38586ef` — row 14, row 15 and two doc commits — `ng build`
would have failed. `344b7b47d5` repaired it by accident, restoring the third parameter for
`SCRIPTING.DS.scriptPermissions`.

Two things let it through, and both are worth fixing rather than noting:

**The type check being run was not a type check.** Every "tsc clean" in this document was
`npx tsc -p tsconfig.app.json --noEmit` run from `ui-ngx/`. There is no `ui-ngx/tsconfig.app.json` —
`angular.json` points `architect.build.options.tsConfig` at **`src/tsconfig.app.json`**. The command
answered `error TS5058: The specified path does not exist`, and the `grep -iE "inferrix|gateway"`
filter wrapped around it swallowed that line along with everything else. A command that cannot fail
visibly is not a check. **The invocation is `npx tsc -p src/tsconfig.app.json --noEmit`, and its
exit status is the answer.**

**Per-file karma does not cover for it.** `ng test --include='<one spec>'` compiles only that spec's
import closure. No gateway spec imports `gateway-model-dialog.component.ts` — it has no spec of its
own — so 104 green specs said nothing about the file the build was failing on.

## Our own known gaps

Cortex-side, not the gateway's. Neither is worth a fix inside a per-type row; both want the on-screen
pass that rows 6-11 are already owed.

- **Saving a provisioned point through our own dialog erases `settable` on five mesh types.**
  `GatewayModelDialogComponent.save` always sends the whole `pointLocator`, and `saveDataPoint` uses
  `PUT` once the point has an `xid`, so the write goes through a `toVO` that drops the flag (D109).
  Renaming a thermostat point turns its setpoint read-only on the gateway. The disabled control is
  not at fault — `getRawValue()` re-sends the value it was given — so the fix is in the dialog:
  either omit `pointLocator` when nothing in it changed, or use the `PATCH` route, which the service
  already has and which the gateway accepts for `{name}` alone. **Not done**, because it changes the
  save path for all 148 model types and wants deciding rather than sneaking into a review commit.

- **The hint on a disabled toggle may have no reachable tooltip.** `settable` is the first field this
  work has marked `readonly` that renders as a boolean, and a boolean is a `mat-slide-toggle` whose
  hint rides `[tb-hint-tooltip-icon]` on the projected label — not the `.tb-gateway-form-hint`
  `mat-icon` the `pointer-events: auto` rule targets. If Material kills pointer events on the
  disabled toggle the way it does on a disabled form field, the hint is unreadable and `readonly`
  buys nothing over `hidden` for those five types. Unverified: it needs the on-screen pass, and it is
  the first thing to look at when that pass happens.

- **A failed schema read leaves every Add form empty, with nothing said.** `load()` fires the schema
  request and the type request independently and swallows both errors on purpose — the list is worth
  showing even when the second call fails, and the comment says so. But the type picker is populated by
  the *other* call, so if only the schema read fails (an older gateway with no `/v2/model-schemas`, an
  under-privileged token) Add still opens, with the identity fields and nothing else, and a save is a
  422. Raised by the row-11 review as the first default whose presence depends on that race: no poll
  period is seeded either. Wants an inline warning on the page rather than a guard on the button, since
  the same emptiness affects an edit.

## Handed over

**Closed by the gateway on 2026-09-29, in stack `eb391cb75` (5.1.3).** Every finding D65-D88 is
resolved or answered in one cut; each of the six specs carries a Resolution section. Three fixes
went into core rather than onto the type that exposed them (D65, D81, D86), which is the right call
and the reason this feature files findings at all. What changed on this side is recorded under
[What the 5.1.3 cut changed here](#what-the-513-cut-changed-here). Three of the findings were mine
and wrong, or stale, and are recorded there too. D73 is deliberately not fixed and D89 is new.

- **D65 (P2)** — `DataTypes.CODES.getId(null)` answers `-1`, and **53 locator models** convert
  `dataType` through it with no guard. A locator posted without one is accepted 201 and stores `-1`,
  which reads back as `null`. Same family as D54/D56/D59/D63, one level further out: the absent key
  lands on a code table rather than on a primitive, so `validate` never sees it.
- **D66 (P3)** — `META.PL.scriptEngine` has exactly one legal value and is still not defaultable, by
  the same mechanism. Cortex therefore hides the field *and sends it*, because absent is not
  `JAVASCRIPT`.
- **D67 (P3, cosmetic)** — four `META.PL` option labels register message keys `i18n_en.properties`
  does not carry, so the gateway's own form cannot label them. One of the four is the `NONE`/context
  equivalence: the file has `dsEdit.meta.event.context` where the code asks for
  `dsEdit.meta.event.none`.

  Written up in `Inferrix-stack/docs/specs/2026-09-27-absent-code-table-value-is-minus-one.md`.

- **D68 (P0, data loss)** — `PUT /v2/data-source/{xid}` builds a fresh VO from the body alone, so a
  read-modify-write erases every `writeOnly` field. Since D64 made the four SNMP credentials
  `writeOnly`, the `GET` cannot return them and the `PUT` requires them: **no client can perform the
  round trip an edit form is without destroying the credential**, and the response looks like a
  successful save. `PATCH` merges against the stored model and is the only safe verb.
- **D69 (P2)** — `contextName` and `engineId` disagree about what "not set" means on v3: absent is
  refused for the first and fine for the second, blank is the other way round. Neither is marked
  `required`.
- **D70 (P2)** — a v3 source with `authProtocol: MD5` and no passphrase is accepted 201. It cannot
  authenticate, and with D64 in place the operator cannot see that the value is missing.

  Written up in `Inferrix-stack/docs/specs/2026-09-27-put-erases-every-writeonly-field.md`.

- **D71 (P1)** — `MQTT.DS.qosType`, `MQTT.PL`'s three enums and `brokerUri` (absent *or* blank) each
  answer **500** rather than a message: four `Enum.valueOf(null)` calls in `toVO`, which runs before
  `validate`, and a `new URI(null)` / `getScheme().hashCode()` in `validateURI`.
- **D72 (P3, cosmetic)** — the MQTT QoS and payload-format lists register `mqtt.QosType.*` and
  `mqtt.topicType.*` message keys that `i18n_en.properties` does not carry, so every one of those
  dropdowns is unlabelled in the gateway's own UI. Same family as D67.
- **D73 (P1)** — `PATCH` cannot read a body with a polymorphic member, so it refuses every
  read-modify-write of a data point or a publisher. Together with D68 that leaves **no verb** for
  editing an MQTT sender without erasing its credential.

  Written up in `Inferrix-stack/docs/specs/2026-09-28-mqtt-rest-surface.md`.

- **D74 (P1)** — an `HTTP_RECEIVER.DS` body omitting `ipWhiteList` or `deviceIdWhiteList` answers
  **500**: `validate` iterates each array and `toVO` copies a null model field across. The VO has the
  defaults (`*.*.*.*`, `*`) and the model cannot reach them.
- **D75 (P2)** — an **empty** whitelist saves 201 and then rejects every request, because both
  matchers answer false for a zero-length array. Reachable by deleting rows in a form, with nothing on
  the data source to say what happened.

  Written up in `Inferrix-stack/docs/specs/2026-09-28-http-receiver-whitelists.md`.

- **D76 (P2)** — `HttpJsonRetrieverPointLocatorVO.isSettable()` returns a hard `false`, so
  `RuntimeManagerImpl` refuses every write and the type's whole set-point path — `setPointUrl`,
  `setPointName`, `settable`, `setPointValue`, the `SET_POINT_FAILURE` event — is unreachable. A
  submitted `settable: true` saves 201 and reads back false.
- **D77 (P3)** — `ignoreIfMissing` is declared, serialised, mapped and read by nothing. The
  event-suppressing behaviour the gateway's own help text describes never happens.
- **D78 (P2)** — `timeoutSeconds <= 0` adds its message under the property name `updatePeriods`, a
  field this model does not have, so no form can show the refusal against the field that caused it.
- **D79 (P2, security)** — `bearerToken` is not `writeOnly`: the gateway returns the token verbatim
  from every read of the data source, list endpoint included.
- **D80 (P2)** — 15 of the 18 message keys this type uses are missing, including every point's
  configuration description and the validation message for a bad JSON Pointer.
- **D81 (P2)** — **no** polling data source validates its poll period through REST. A body with a
  period unit and no count saves 201 with a zero period; measured on `HTTP_JSON_RETRIEVER.DS` and
  `MODBUS_IP.DS`. `PollingDataSourceVO.validate` has the guard and is unreachable, and
  `PollingDataSourceDefinition` is extended by one type in the tree.

- **D82 (P2)** — an unsupported data type is accepted and then fails on every poll. Nothing validates
  `dataType` against what the locator's own runtime can make: `HTTP_JSON_RETRIEVER.PL`, `SNMP.PL` and
  `META.PL` all end their conversion on a throw, so an IMAGE point saves 201 and reads nothing for
  ever. Per-locator rather than blanket — `HTTP_RECEIVER.PL` really does support an image.

  Written up in `Inferrix-stack/docs/specs/2026-09-28-http-json-retriever.md`.

- **D83 (P2)** — `ValueMonitorModel` publishes a monitor's translated name and current value and **not
  its id**, so the 98-row `GET /v2/stack-monitor` cannot drive a picker for `INTERNAL.PL.monitorId` or
  help write the `createPointsPattern` that matches ids. One field on one model.
- **D84 (P1)** — `InternalPointLocatorModel.toVO` builds a fresh VO and copies only `monitorId`, so a
  submitted `dataType` is dropped and the VO's NUMERIC kept. A text-valued monitor cannot be configured
  through REST, and any save of an existing ALPHANUMERIC internal point resets it to NUMERIC.
- **D85 (P2)** — a `monitorId` no monitor answers to saves 201. The validator's `getMonitor` returns
  null for an unknown id instead of throwing, so its `catch` never runs; the point is then inert for
  ever.
- **D86 (P2)** — a duplicate `xid` answers **500** on both `POST /v2/data-source` and
  `POST /v2/data-point`. General to every type.
- **D87 (P3)** — `internal.missingMonitor`, `validate.invalidRegex` and the three
  `dsEdit.internal.autoCreate.names.*` keys appear in no `.properties` file at all. The autoCreate keys
  are stored as auto-created points' **names**.

  Written up in `Inferrix-stack/docs/specs/2026-09-28-internal-monitoring-source.md`.

- **D88 (P3)** — `PointLocatorModel.settable`'s `@Schema` description lists the locators that override
  `isSettable()` to false and omits `INTERNAL.PL`, which does. One sentence.

  Written up in `Inferrix-stack/docs/specs/2026-09-28-internal-monitoring-source.md`.

- **D90 (P1, superseded by D108)** — `MeshPointLocatorVO.ATTRIBUTE_CODES` is one `public static`
  field that 34 mesh locator subclasses reassign from their own static initialisers, so every mesh
  type validates its attribute against whichever class loaded last. Measured: a `MESH_CONTROLLER.PL`
  point accepts `attributeId: "BATTERY"`, which `MeshControllerAttributes` does not declare. D108
  keeps the finding and corrects its scope: the table changes at runtime, not only at boot.
- **D91 (P3)** — `MeshControllerPointLocatorModel.toVO` drops `settable` and `relinquishable` while
  the read reports them, the shape D84 closed on `INTERNAL.PL`.
- **D92 (P3, withdrawn 2026-09-29)** — filed as "a client cannot tell which data source types it is
  meant to offer". Wrong: the endpoint it named, `/v2/data-source-definitions`, answers 404, and the
  real one — `/v2/data-source-types`, which our own Add menu reads — is already filtered by
  `DatasourceService.getDefinitions` on `isEnabled()` and returns **16**. I read the flag in the
  definition classes and inferred the listing instead of calling it. What survives is smaller: a
  `MESH_CONTROLLER.DS` created by a direct POST, bypassing the menu, is accepted and can never hold
  a point.

  Both written up in `Inferrix-stack/docs/specs/2026-09-29-mesh-controller-rest-surface.md`, where
  D92 now carries its correction.

**Filed 2026-09-29, from row 14.** All three small, in
`Inferrix-stack/docs/specs/2026-09-29-ping-rest-surface.md`.

- **D93 (P3)** — `PingDataSourceDefinition.validate` uses `else if`, so a point with both fields
  wrong is refused for the address only and the timeout is not checked until the address is fixed.
- **D94 (P3)** — the timeout check is `<= 0` but its message key is `validate.not0`, so `-5` is
  refused with "Cannot be 0". `validate.greaterThanZero` already exists and is used for the same
  shape of check on `updatePeriods`.
- **D95 (P3)** — the published `settable` description lists the locators that ignore the field
  (`MESH_SWITCH.PL`, `MESH_UART.PL`, `INTERNAL.PL`) and omits `PING.PL`, which also overrides
  `isSettable()` to false. The list is the only place the wire says this, so an incomplete one is
  worse than none.

**Filed 2026-09-29, from row 15.** In
`Inferrix-stack/docs/specs/2026-09-29-poe-lighting-rest-surface.md`.

- **D96 (P1)** — the PoE controller's bearer `token` is published without `writeOnly` and reads back
  in the clear, *and* `getConnectionDescription()` returns `ipAddress + ":" + token`, so marking the
  field `writeOnly` would leave the credential readable through a `readOnly` display string. Both
  have to change together.
- **D97 (P2)** — `pointType` is a bare string with `valueOf` called on it: omitted is a **500**,
  unknown is a 400 naming no field. The sibling mesh-node model already types it as the enum and
  documents why.
- **D98 (P2)** — `connectionTimeoutSeconds` is stored and mapped and read by nothing.
- **D99 (P3)** — neither `validate` overload has a body, and `retries` has three different defaults
  (VO 2, REST model 0, discovery 3).
- **D100 (P3)** — the create response reports `settable: false` for a point whose stored locator is
  `settable: true`; a GET immediately after disagrees with it. Both halves measured here: the create
  response carried top-level `"settable": false` beside `pointLocator.settable: true`, and
  `GET /v2/data-point/{xid}` on the same row answered `settable: true`. (Row 15/16's review saw the
  first half and flagged that it had not run the second; it had been run.)

**Filed 2026-09-29, from row 16.** In
`Inferrix-stack/docs/specs/2026-09-29-scripting-rest-surface.md`.

- **D101 (P1)** — `ScriptDataSourceModel.toVO` calls `new ScriptPermissions(scriptPermissions)`
  unguarded and that constructor splits the string, so an absent key is an HTTP 500 — including on a
  `PATCH` of a row's own body, because the read hands back `null`. `MetaPointLocatorModel` already
  guards the same call; two other callers do not.
- **D102 (P2)** — `updateEvent` publishes five enum values and `commonValidation` accepts three.
- **D103 (P3)** — `logSize` and `logCount` are primitives on the REST model, so a create that omits
  them stores 0 and 0 instead of the VO's 1.0 and 5.
- **D104 (P1)** — `commonValidation` hands `service.compile(vo.getScript(), false)` a null when
  `script` is omitted, which is a 500 in the Nashorn source constructor. A **blank** script is legal
  and stored, so this is a missing null check rather than a missing requirement — the same one as
  D101, twelve lines up the same method, and one the meta module already has.

- **D105 (P2)** — an omitted or unknown `attributeType` on `SYSTEM_ATTRIBUTES.PL` skips the point
  validator rather than failing it: `ExportCodes.getId` answers -1 and neither the validator's
  `if/else if` nor `toVO`'s `switch` has a default. Measured 201, reading back `attributeType: null,
  startValue: null` — a point that behaves as a boolean starting at zero. `dataTypeId` is checked
  this way three lines above.

- **D106 (P2)** — every refusal the point locator's own rules produce names a VO path the REST model
  flattened away: `booleanAttribute.startValue` and `timerAttribute.timerValue` against a model
  carrying `startValue` and `timerValue`, plus `dataTypeId` against `dataType`. Measured. A client
  cannot place any of them on the field that is wrong. The source-type check is the one exception —
  `dataSourceId` is a real model field.

- **D107 (P2)** — the REST path accepts attribute-type / data-type pairings both of the gateway's own
  front ends forbid, and the pairing decides the value class the runtime stores. Measured 201 for
  NUMERIC with a `BOOLEAN_ATTRIBUTE` and for MULTISTATE, which has no valid attribute type at all.
  `AttributeTypeVO.getAttributeTypes(dataTypeId)` already encodes the rule and has zero callers.

- **D115 (P2)** — `Enum::name` versus `attributeName` differs on four of the 27 mesh attribute
  enums, not one, and two of the four wire names contain **spaces**: `INJECTION MOULD COUNT` and
  `STROKE COUNT`. Measured 201 for the spaced value and 422 for the underscored one the endpoint
  publishes. No client can derive those values from anything the API exposes.

- **D116 (P3)** — 20 of the 130 mesh attribute descriptions have no English bundle entry, so
  `configurationDescription` returns the raw key against a schema documenting it as pre-translated.
  The VAV controller loses seven of its nine; the thermostat loses `STATUS`; the current sensor loses
  all five all-phase attributes, whose keys exist in no bundle at all — zero matches across every
  properties file in the stack, where the eleven per-phase keys are in both English and French.
  `dsEdit.inferrixSensors.currentSensor.phaseAll` is missing the same way, so the "All phases" label
  is ours by necessity too. D111 counted properly.

- **D120 (P1)** — `DUSTBIN_LEVEL_SENSOR` and `SOAP_DISPENSER_SENSOR` can never hold a data point:
  their definitions' point `validate` tests `dsvo instanceof DistanceSensorDataSourceVO` and neither
  VO is one. Measured 422 on `dataSourceId` for both, 201 for the paper towel sensor whose copy of
  the same file names its own VO. Two words in two files.

- **D118 (P2)** — the same three level sensors resolve `attributeId` against
  `DistanceSensorPointLocatorVO.ATTRIBUTE_CODES` in `toVO`, although each has its own attributes enum
  and its own static initialiser. Under D108 that initialises the wrong class and leaves the shared
  table holding the distance sensor's three attributes, which contain none of the three types'
  distinguishing ones. Driving D108's ratchet rather than suffering it.

- **D119 (P3)** — nothing cross-checks a current sensor's attribute against its phase.
  `CurrentSensorDataSourceDefinition.validate` checks the four locator fields one at a time and never
  against each other, so a whole-supply attribute on `PHASE_2` saves (measured 201) and is then never
  routed. A point that reports nothing looks like a dead sensor.

- **D117 (P3)** — the I/O card's digital outputs are `D01_STATUS`/`D02_STATUS` with a digit zero,
  while their translation keys and labels say DO1/DO2. Measured: the letter O is a 422. Reads like a
  typo and will be "corrected" eventually, which would break stored points.

- **D109 (P2), widened 2026-09-30, corrected same day** — six types by construction, four with a
  consequence. `4DI_2DO_CARD`, `PEOPLE_COUNTER`, `THERMOSTAT`, `VAV_CONTROLLER`, `MESH_CONTROLLER`
  and `PEOPLE_COUNT_CAMERA` all inherit a `settable` that answers the stored field and all drop it in
  `toVO`. On the first four the provisioner writes a real value from the attribute enum, so the
  erase loses information; on the last two nothing ever writes anything but `false`, so it is a
  no-op. `MODBUS_CONTROLLER` is the only one of 27 that copies it, and is the worked example of the
  fix.

- **D114 (P2)** — an analog system attribute accepts a non-numeric `startValue` (measured 201) and
  `AnalogAttributeRT.getStartValue()` then does a bare `Double.parseDouble` when the point starts.
  The guarded version of that parse exists in `createRuntime`, on the branch nothing calls.

- **D108 (P1, supersedes D90)** — `MeshPointLocatorVO.ATTRIBUTE_CODES` is one mutable `public
  static` field shared by 34 subclasses, each reassigning it in a static initialiser that runs once,
  at first class load, so the table is a one-way ratchet: it moves each time a mesh locator class is
  first instantiated and settles only once all 34 have been. Valid attributes are therefore
  refused, foreign ones accepted, and stored points read back `attributeId: null`. Measured by
  flipping it mid-session; the gateway's own lookup endpoint contradicts the validator throughout.

- **D109 (P2)** — `ThermostatPointLocatorModel.toVO` builds a fresh VO and never copies `settable`,
  which the provisioner sets from the enum for seven of the nine attributes. So a REST write of a
  provisioned point **erases** it and the setpoint stops being writable. Measured 201 reading back
  `false`. D91's shape, with a consequence.

- **D110 (P2)** — the code table is built from `ThermostatAttributes` while the version handler
  provisions from `ThermostatAttributesV1_1` on firmware ≥ 1.1.0, so a v1.1 thermostat's
  `ECO_SETPOINT_TEMPERATURE` point can be created by the gateway and then neither named nor written
  over REST.

- **D113 (P2)** — `ThermostatAttributeResource` returns `Enum::name` while the API validates against
  `getAttributeName()`; they differ for `ENERGY_SAVING` / `ENERGY_SAVING_MODE`, so the endpoint whose
  documented job is filling UI dropdowns publishes a value the same gateway refuses. Worth auditing
  the other ~11 `/v2/export-code/sensors/*` resources, which are written the same way.

- **D111 (P3)** — `configurationDescription` is documented as pre-translated and returns the raw key
  when the bundle has no entry. Measured `dsEdit.inferrixSensors.attribute.roomNumber`; the
  thermostat's `status` key has no entry either.

- **D112 (P3)** — an attribute's data type is not checked against the attribute's own. Measured:
  `TEMPERATURE` (NUMERIC in the enum) saves 201 with `dataType: "BINARY"`.

- **W11 (P1)** — a multistate virtual point cannot be configured at all. The template switches on
  `'MULTISTATE'`, a case its own dropdown can never emit (it emits `INCREMENT_MULTISTATE`), so the
  value list and roll flag never render and the point saves with an empty value set. Cortex uses
  the value the device defines, so this combination works here.
- **W12 (P3)** — `app-datapoint-properties` is referenced by no template; every point's retention,
  logging and text-renderer settings are unreachable from any data source form.
- **W13 (P3)** — the attractor form labels `maxChange` "Minimum Change" while the brownian form
  labels the same field "Maximum Change". `AnalogAttractorChangeRT:52` clamps to ±`maxChange`, so
  it is a ceiling and the brownian wording is right.
- **W14 (P2)** — the attraction point picker offers every point on the gateway. Its query is
  `'?eq(dataTypeId,' + typeId` with no closing paren, so the gateway ignores it; closing it would
  return nothing, because `dataTypeId` is not filterable and `dataType` is. Measured live: 100
  unfiltered, 0 well-formed, 27 with `eq(dataType,NUMERIC)`.

- **W15 (P2)** — the Modbus character-encoding picker offers `ASCII` and `RTU`. `RTU` is a Modbus
  *serial framing* mode, pasted in with its `modbusSerial.encoding.*` translation keys;
  `Charset.forName("RTU")` throws, so the option cannot work.
- **W16 (P1)** — switching a register point to a coil range greys the data-type picker out but
  keeps its value, so the point is stored as NUMERIC and `createBaseLocator()` throws
  `IllegalDataTypeException` afterwards. Cortex gates the list instead.
- **W17 (P2)** — the two I/O log inputs on the Modbus/IP form carry no `[(ngModel)]` at all, so
  `ioLogFileSizeMBytes` and `maxHistoricalIOLogs` are unsettable from that form.
- **W18 (P2)** — the Modbus **serial** form has the same two unbound I/O log inputs as W17, so the
  defect is the pair of forms rather than one of them.
- **W19 (P3)** — its six serial dropdowns render `Enum::name` straight from
  `/v2/modbus/attributes/serial/*`, so the operator picks between `DATA_BITS_5` and `DATA_BITS_8`,
  and between `RTSCTS` and `XONXOFF`. The enums carry `MessageTranslation` descriptions written
  for exactly this, and nothing reads them.

- **W23 (P2)** — the BACnet point form labels `remoteDeviceInstanceNumber` by concatenating
  `substring()` slices of an HTTP-receiver key and an SNMP key. It reads correctly only by
  coincidence of those two strings' current wording.
- **W24 (P2)** — `writePriority` is labelled with the *write permission* key, so the BACnet point
  form shows two fields called "Write Permission".
- **W25 (P2)** — the BACnet data-type picker offers all five types for every property, although
  the route it already calls reports which ones each property supports.
- **W26 (P2)** — the JSON retriever's point form marks `valueFormat`, `timePointer` and `timeFormat`
  required, and the data source form marks `setPointUrl` required. `validate` requires none of the
  four, and requiring them blocks the ordinary case: a numeric document with no timestamp.
- **W27 (P1)** — the same form's "Settable" toggle is bound to `pointLocator.setPointName`, writing a
  boolean into the string field that names the JSON key a write would post. There is no control for
  `settable` at all, and any point saved through that toggle carries `setPointName: true`.
- **W28 (P2)** — the internal monitoring source's form delegates to the shared **mesh-node** sensor
  form, so it offers `address`, `anchorNode`, `location` and `zone` — fields `INTERNAL.DS` does not have
  — and neither the poll period nor `createPointsPattern`, which are the only two it does. The gateway
  ignores the extra keys rather than refusing them, so the form saves and configures nothing.

Stack-side findings go to `Inferrix-stack/docs/specs/` instead.

> **2026-09-27: every item this work filed from types 5 to 8 is closed on `inferrix-stack-v5.1.x`** —
> D54-D64 and A20-A24, each with a resolution note in its own document, plus D57's A22 and META's A23
> answered. Four of those notes correct something this side got wrong; the corrections are recorded
> in the per-type sections above rather than here. The earlier Modbus items (D38-D41, A14-A19) were
> already closed on 2026-09-25, except **D48 and D50, which are still open**. Nothing below has been
> rewritten — the measurements are what they were, and the resolutions say so where they differ.
>
> **None of it is deployed yet.** Everything above was measured against 5.1.1 on
> `Inferrix Gateway 155`; the fixes are on a branch. Two on-screen passes are owed once a gateway
> carrying them is reachable: an MS/TP point (type 6) and an SNMP point (type 8).

From type 2, in
`2026-09-25-mesh-node-provisioned-rows.md`:

- **D38 (P2)** — every provisioned mesh node point is named `nullDO 2 - Statusnull`. The
  unguarded `prefix + name + suffix` was fixed in `c6d7d43d5`, but the rows written while it was
  live were never repaired, and the name that is wrong is the *data point's* rather than the
  *published point's* — so a third site assembles it the same way and that commit did not reach
  it.
- **A14 (P3)** — `attributeId`, `type` and `settable` on `VIRTUAL_MESH_NODE.PL` carry no
  `description`, so a schema-driven client shows them unlabelled. `type` would be better as an
  `enum`: `AttributeDataType` declares all 42 values and `toVO` calls `valueOf` on it unguarded.

From type 3, in `2026-09-25-modbus-locator-derivations.md`:

- **D39 (P2)** — `ModbusPointLocatorVO.getDataTypeId()` reads `modbusDataType` without reading
  `range`, so a coil point carrying a numeric type is stored as NUMERIC and then throws when
  modbus4j builds its locator. `validate()` does not catch it: it only rejects `rangeId == -1` and
  `modbusDataTypeId == -1`, and both resolve.
- **A15 (P3)** — `MODBUS.PL.bit` is published as `{"type": "string", "format": "byte"}` — springdoc
  rendering a Java `byte` as base64 — where the wire format is an integer, and with no
  `minimum`/`maximum` although `ModbusUtils.validateBit` throws outside 0-15.
- **A16 (P3)** — `modbusDataType` is a bare string with 32 legal values, while `range` and
  `writeType` on the same model both carry `allowableValues`.

From type 5, in `2026-09-26-bacnet-locator-model-defaults.md`:

- **D54 (P1)** — `BACnetPointLocatorModel.multiplier` has no initialiser where the VO starts at
  1.0, so a point saved without one is stored with 0 and reports 0 for ever. 201 Created, no
  warning. `ModbusPointLocatorModel` carries the initialiser on the model and is unaffected.
- **D55 (P1)** — an absent `propertyIdentifierId` is a 500: `toVO` calls
  `PropertyIdentifier.forName(null).intValue()` before `validate()` runs. Same defect class as
  D40 on Modbus serial.
- **D56 (P2)** — `covSubscriptionTimeoutMinutes` has the same missing initialiser, so the VO's 60
  is unreachable through REST and a caller who never set the field is refused on it.
- **A20 (P3)** — `LocalDeviceConfigModel` writes `"type"` twice into the same JSON object: the
  `@JsonTypeInfo` discriminator and an explicit `@JsonProperty` field.
- **A21 (P3)** — `objectTypeId`, `propertyIdentifierId` and `writePriority` carry no
  `allowableValues` or bounds, although the first is a closed set the gateway itself serves and
  the last is validated 1-16.

From type 4, in `2026-09-25-modbus-serial-enum-nulls.md`:

- **D40 (P1)** — omitting any of `encoding`, `parity`, `dataBits`, `stopBits`, `flowControlIn` or
  `flowControlOut` on a `MODBUS_SERIAL.DS` POST answers **500**, not a validation error:
  `toVO` calls `Enum.valueOf`/`fromName` on the null before `validate()` runs, which makes all six
  `validate.required` branches in `ModbusSerialDataSourceDefinition` unreachable through REST.
  Measured live on 5.1.0. A bogus *value* answers 400 correctly — only an absent one is a 500.
- **D41 (P3)** — `ModbusSerialDataSourceVO`'s constructor defaults five of the six line settings
  and leaves `encoding` null, so the field its own `validate()` asks for is the one the VO never
  fills in.
- **A17 (P3)** — `commPortId`, `baudRate` and the five enum-backed line settings carry no
  `description` and no `allowableValues`, although `encoding` beside them declares both.
  `/v2/utilities/gw/serial-ports` is what a client would need to fill the first of them, and is what
  Cortex now uses — the route is in the allowlist as of 2026-09-30, so A17 is worked around rather
  than fixed: the enum-backed settings still carry no `allowableValues`, and their lists are written
  out in the layout by hand.
- **A18 (P3)** — that route's summary reads "Gets all the **unused** serial ports", while
  `UtilityService.getSerialPorts` calls `getAllSerialPorts()`. The behaviour is the useful one —
  a port already bound to a data source still appears, so an edit form can show it — and the
  summary is what is wrong.
- **A19 (P3)** — `FlowControl`, `DataBits`, `StopBits` and `Parity` each carry a
  `MessageTranslation` (`dsEdit.serial.flow.rtsCts`, `dsEdit.serial.dataBits8`, …) for which no
  `.properties` entry exists anywhere in the repository, so every one resolves to its own key.

From type 6, in `2026-09-26-bacnet-mstp-has-no-point-locator-type.md`:

- **D57 (P0)** — `BACNET_MSTP.DS` is the one type of sixteen whose `/v2/data-source-types` entry has
  a null `pointLocatorType`, and any `POST /v2/data-point` on such a data source carrying a locator
  answers **500** rather than a validation result. A BACnet MS/TP data source can be created and can
  never be given a point. Isolated to the locator-type check by five probes of one body.
- **A22** — is an MS/TP data source *meant* to carry points? If the null is deliberate the bug is
  only the 500, and Cortex should say so on the data source rather than offer an Add point button.

From type 7, in `2026-09-26-meta-script-permissions-are-caller-supplied.md`:

- **D58 (P0, security)** — `META.PL.scriptPermissions` is taken from the request body and becomes
  the permission holder the script runs as, with no check that the caller holds it and an empty
  `validate()`. That holder is what decides whether the script engine is confined. Reachable through
  `/v2/data-point`, which is not a script route, so it is past every exclusion Cortex makes by name.
  Measured with a group that exists nowhere, on a disabled point, so the probe could not escalate
  anything.
- **D59 (P2)** — `logSize`, `logCount` and `contextUpdateEvent` are the same bare-primitive defaults
  as D54/D56; `context` and `variableName` cannot be defaulted by a client at all.
- **A23** — is `META.PL` meant to be writable through the v2 data-point route, or is scripting an
  administrator-only surface that needs a permission of its own?

From type 8, in `2026-09-26-snmp-rest-surface.md`:

- **D60 (P1)** — an absent `authProtocol` or `privProtocol` is a 500 on *every* version, thrown while
  building the response from the already-written row: `ReverseEnumMap.get` calls
  `Objects.requireNonNull`. The data source is created, then cannot be read back by any client, and
  `DELETE` also 500s while still deleting it.
- **D61 (P1)** — SNMPv3 cannot be configured at all. `SnmpSettings.getSnmpVersionId("v3")` sets its
  local to 3 and the second switch maps only 0, 1 and 2, so it returns -1 and `validate` rejects the
  version. `case 2: return v3` is dead code. Omitting `snmpVersion` NPEs in the same method.
- **D62 (P1)** — `GET /v2/data-source` omits every `SNMP.DS` row, although each is readable by xid.
  A client that enumerates data sources — every UI, including the stack's own — cannot see, edit or
  delete one. The row still polls when enabled.
- **D63 (P2)** — `multiplicand` and `setType` are silent-zero defaults on `SnmpPointLocatorModel`.
  `getId(null)` returns **-1** rather than 0, so a point with no set type reports itself settable
  with a type nothing answers to.
- **D64 (P2, security)** — `readCommunity`, `writeCommunity`, `authPassphrase` and `privPassphrase`
  carry no `writeOnly`, so they render in the clear and come back in full on read, while every other
  credential in the document is marked. Marking them is the whole fix; Cortex's mapper already
  password-types a `writeOnly` field and already treats empty as "unchanged".
- **A24** — a locator's data type is validated under the property name `dataTypeId` while the model,
  the schema and the body all call it `dataType`, so a per-field message cannot be attached.

## Open decisions

Three calls that change the work and cannot be read out of the code. All have been taken the way
the recommendation says, and all are reversible.

1. **The 106 fields the stack hides.** Hide them too — exact parity, and Cortex can no longer
   configure things the stack's UI cannot reach — or keep them behind an **Advanced** expander,
   where the common case is clean but nothing is lost? *Taken: expander.* Several of those fields
   are real (`alarmLevels`, `ioLogFileSizeMBytes`), and a gateway is not always reachable through
   its own UI.
2. **The 13 types with no stack form.** Leave them on today's generic schema form, or hide them
   from the type picker for parity? *Taken: leave them.* Removing a working form to match a UI
   that never had one is a loss.
3. **What a closed `visibleWhen` gate should do with its value** (raised by the row-11 review,
   2026-09-28). A gate hides the row and keeps the control, so the value is still submitted — which
   means a credential typed while the gate was open is stored after it closes, in a field the gateway
   hands back verbatim to every reader of the row. Clear it on close instead? *Taken: keep it.* The
   alternative is worse in the case that matters more: a form where turning a switch off **destroys a
   stored credential** — an operator disabling certificate auth on an MQTT source to test something
   would lose the private key, silently, and `PATCH` would not be able to give it back. Keeping the
   value is also what makes a gate reversible: `META.PL`'s cron pattern survives a trip through another
   update event. The exposure this leaves is a value the operator typed themselves, unused by the
   gateway while the switch is off; the real problem in front of it is D79, the field being readable at
   all. Revisit if a gate ever hides something the operator did not enter.

## Superseded

The original phase list (G7.2 pipeline, G7.2b conditional fields, G7.3–G7.4 layouts by protocol
family, G7.5 the point form, G7.6 labels) cut the work by theme across all 63 types at once. It is
replaced by the per-type **Sequence** above, which finishes one data source and its points before
starting the next. Two pieces of it survive as cross-cutting work with no natural home in a single
type: the dictionary proxy allowlist (was G7.6) and an extractor that diffs the stack webapp per
release to catch fields it has gained (was G7.2). Neither is started.

**G7.1 shipped as planned** (`da58696668`): `alarmLevels` and `quantize` grouped under Advanced,
`timePeriod` labelled "Polling interval" with "Period"/"Unit" children, and 16 label overrides.
