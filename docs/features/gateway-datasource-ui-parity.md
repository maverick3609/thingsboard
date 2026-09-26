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
| 6 | `BACNET_MSTP.DS` | `BACNET_MSTP.PL` | next; `pointLocatorType` still null on 5.1.1 — falls back to a sibling point. Needs the serial-port picker deferred from 4 |
| 7 | `META.DS` | `META.PL` | |
| 8 | `SNMP.DS` | `SNMP.PL` | |
| 9 | `MQTT.DS` | `MQTT.PL` | `writeOnly` secrets; empty must mean "unchanged" |
| 10 | `HTTP_RECEIVER.DS` | `HTTP_RECEIVER.PL` | "HTTP" is two types on this stack; both are in scope |
| 11 | `HTTP_JSON_RETRIEVER.DS` | `HTTP_JSON_RETRIEVER.PL` | the other half of 10 |
| 12 | `INTERNAL.DS` | `INTERNAL.PL` | 1 live; deferred behind the protocols above |
| 13 | `MESH_CONTROLLER.DS` | — | 1 live |
| … | the remaining ~40 | | mostly four to eight fields |
| last | the 13 types with no stack form | | left on the generic schema form — see Open decisions |

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

**`commPortId` is still a text box, and that is a gap.** The gateway does publish its ports, at
`GET /v2/utilities/gw/serial-ports`, and the stack's own form fills a dropdown from it. That route
is not in `InferrixGatewayRoutes`, and adding it is a platform release — so this type ships with
the operator typing the port name their gateway reports. It is one allowlist line, one service
method and a subclass of `GatewayFormComponent` in the shape `VirtualPointFormComponent` already
has, and **BACnet MS/TP needs the same picker**, so it belongs with type 6 rather than with a
release of its own. Recorded here rather than guessed at: a port name this platform invented
would be worse than an empty box.

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

Stack-side findings go to `Inferrix-stack/docs/specs/` instead. From type 2, in
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
  `/v2/utilities/gw/serial-ports` is what a client would need to fill the first of them.
- **A18 (P3)** — that route's summary reads "Gets all the **unused** serial ports", while
  `UtilityService.getSerialPorts` calls `getAllSerialPorts()`. The behaviour is the useful one —
  a port already bound to a data source still appears, so an edit form can show it — and the
  summary is what is wrong.
- **A19 (P3)** — `FlowControl`, `DataBits`, `StopBits` and `Parity` each carry a
  `MessageTranslation` (`dsEdit.serial.flow.rtsCts`, `dsEdit.serial.dataBits8`, …) for which no
  `.properties` entry exists anywhere in the repository, so every one resolves to its own key.

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
