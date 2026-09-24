// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { DeviceInfo } from '@shared/models/device.models';
import { HasUUID } from '@shared/models/id/has-uuid';
import { LogicProgram } from '@shared/models/inferrix-logic.models';

/** The device profile the platform creates on first adoption; see InferrixAdoptionService. */
export const INFERRIX_CONTROLLER_PROFILE = 'Inferrix Controller';

/**
 * A controller seen announcing itself but not yet adopted.
 *
 * Everything except `ip` is unauthenticated device-supplied data — the announce carries no
 * credential of any kind, by design, since it has to work before a broker or password exists.
 * `ip` is the address the platform actually saw the connection come from.
 */
export interface DiscoveredController {
  uid: string;
  ip: string;
  identity: {[key: string]: any};
  firstSeenTs: number;
  lastSeenTs: number;
  announceCount: number;
  assignedTenantId?: string;
}

/**
 * Escapes a value for an entities-table cell.
 *
 * ThingsBoard inserts cell content with `bypassSecurityTrustHtml`, and everything a controller
 * reports about itself is written by the controller: adopted controllers publish their identity
 * over MQTT, and discovery sightings arrive over an unauthenticated plain-TCP announce that anyone
 * on the network can send. Without this, a crafted `location` or `uid` would run as script in the
 * operator's browser.
 */
export const escapeCell = (value: any): string => value === undefined || value === null ? '' :
  String(value).replace(/[&<>"']/g, character =>
    ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;'})[character]);

/**
 * A discovered sighting as an entities-table row. The list is not a platform entity — nothing has
 * been created yet — so the row carries the controller's own uid as its id.
 */
export interface DiscoveredControllerRow extends DiscoveredController {
  id: HasUUID;
}

export interface AdoptControllerRequest {
  uid?: string;
  host?: string;
  password?: string;
  name?: string;
  location?: string;
  label?: string;
}

/**
 * The attribute keys the identity block is built from.
 *
 * Named explicitly rather than reading every attribute the device has: the platform's own
 * server-scope attributes on a controller include its sealed bearer token and password, and there
 * is no reason to ship those to a browser even sealed.
 */
export const CONTROLLER_ATTRIBUTE_KEYS = [
  'lastActivityTime',
  'uid', 'ip', 'model', 'fw', 'icc', 'mac', 'location', 'name',
  'controllerUid', 'controllerIp'
];

/**
 * An adopted controller: the platform's own device record, plus the identity the device publishes
 * about itself.
 *
 * Extends {@link DeviceInfo} rather than restating it, so the entities table gets `id`, `name`,
 * `createdTime` and the rest of the device fields for free and the row is a real device everywhere
 * it is handed to a ThingsBoard component.
 */
export interface ControllerInfo extends DeviceInfo {
  // `active` is inherited from DeviceInfo: it is the platform's own connectivity state, which is
  // authoritative and needs no attribute read.
  lastActivityTs?: number;
  uid?: string;
  ip?: string;
  model?: string;
  fw?: string;
  icc?: number;
  mac?: string;
  location?: string;
  deploymentName?: string;
}

/** GET /api/v1/health on the device. */
export interface ControllerHealth {
  uptime_ms?: number;
  fault?: boolean;
  scan?: {last_ms?: number; max_ms?: number; overruns?: number};
  mqtt?: {state?: number | string; coalesced?: number};
  [key: string]: any;
}

export type PointQuality = 'good' | 'comm_fail' | 'never' | 'stale';

/** One entry of GET /api/v1/points. */
export interface ControllerPoint {
  id: number;
  type: string;
  bus?: number;
  unit?: number;
  v: boolean | number;
  q: PointQuality;
  age_ms: number;
  n: string;
}

/** The source classes `POST /api/v1/points/{id}` accepts; every other class is read-only. */
export const WRITABLE_POINT_TYPES = ['do', 'ao', 'rtu'];

/**
 * The body of a point write.
 *
 * The device parses `v` as a bool or a 32-bit integer only; a fractional value has to travel as the
 * raw float32 bit pattern in `v_bits`, the same convention as `deadband_bits`.
 */
export const pointWriteBody = (type: string, value: boolean | number): {[key: string]: any} =>
  typeof value === 'boolean' || (Number.isInteger(value) && value >= -2147483648 && value <= 2147483647)
    ? {type, v: value}
    : {type, v_bits: floatToBits(value)};

/**
 * A reported firmware version, or null when the report carries none.
 *
 * Before firmware 0.1.15 the MQTT and discovery announces sent a hard-coded numeric `fw: 0` (notes
 * §14) while `/api/v1/info` told the truth, so a stored `0` means "unknown", not "version 0".
 */
export const firmwareVersionOf = (fw: any): string =>
  typeof fw === 'string' && fw.trim() ? fw : null;

/**
 * Whether two versions name the same release. MCUboot ranks images by major.minor.revision and
 * ignores the build number, so this does too.
 */
export const sameFirmwareRelease = (a: string, b: string): boolean => {
  const release = (version: string) => /^\d+\.\d+\.\d+/.exec(version ?? '')?.[0];
  return !!release(a) && release(a) === release(b);
};

export const pointQualityColor = (quality: PointQuality): string => {
  switch (quality) {
    case 'good':
      return '#198038';
    case 'stale':
      return '#b28600';
    default:
      return '#da1e28';
  }
};

/**
 * Declarative description of one editable device setting.
 *
 * The controller's settings endpoints are all the same shape — GET a flat JSON object, edit it,
 * PUT it back — so they are described rather than hand-written five times over. Anything that is
 * NOT that shape (the network confirm-or-revert flow, the peer table) stays a real component.
 */
export interface ControllerSettingField {
  key: string;
  label: string;
  type: 'text' | 'number' | 'boolean' | 'password' | 'textarea' | 'select' | 'flags';
  hint?: string;
  required?: boolean;
  min?: number;
  max?: number;
  maxLength?: number;
  pattern?: RegExp;
  options?: {value: any; label: string}[];
  /** For `flags`: the named bits of a bitmask, rendered as checkboxes. */
  bits?: {value: number; label: string}[];
  /** Shown but not sent back; the device masks secrets it will not return. */
  writeOnly?: boolean;
  /** The record's key. Editable when adding, frozen when editing — changing it forks the record. */
  keyField?: boolean;
  /** Held on the device as a raw IEEE-754 f32 bit pattern but edited as a decimal. */
  float32Bits?: boolean;
  /**
   * The field holds another section's key, so it is picked from that section's records rather than
   * typed. A wrong id here is only caught at Apply, by which point the operator has left the field.
   */
  optionsFrom?: ControllerRefSection;
  /**
   * The platform picks this record's id: the lowest free one in the section.
   *
   * Only ever on a `keyField`. An id is a number with no meaning of its own — nothing reads it but
   * the records that point at it — so typing one is a chance to collide with an existing record
   * (which silently overwrites it, since a write is an upsert) or to pick one that is over the
   * section's range. Neither mistake tells the operator anything at the time it is made.
   */
  autoId?: boolean;
  /**
   * Sent with its default and never shown.
   *
   * For a field the firmware stores and never reads. It cannot be dropped — a record write is a
   * full record — but an input for it is an input whose only possible effect is to be wrong.
   */
  hidden?: boolean;
  /**
   * Prefilled when adding a record. Every config field is required on a full-record write, so a
   * field with an obvious value (no flags, no scaling, QoS 0) needs one or the operator has to type
   * it — and a bitmask rendered as checkboxes has no input to type into at all.
   */
  defaultValue?: any;
}

export interface ControllerSettingsForm {
  path: string;
  titleKey: string;
  fields: ControllerSettingField[];
}

// ---------------------------------------------------------------------------
// Firmware and logic uploads
// ---------------------------------------------------------------------------

export type ControllerUploadKind = 'FIRMWARE' | 'LOGIC';

/**
 * Mirrors `InferrixUploadService.Kind`. Checked here only so an operator who picked the wrong file
 * is told before it is uploaded; the platform enforces the same limits and is the authority.
 */
export const CONTROLLER_UPLOAD_LIMITS: {[kind: string]: number} = {
  FIRMWARE: 1024 * 1024,
  LOGIC: 128 * 1024
};

/**
 * Progress of one artifact being written to a controller.
 *
 * The platform does the chunking, so this is polled rather than watched: a signed image is several
 * hundred requests against a microcontroller and the run takes minutes.
 */
export interface ControllerUploadStatus {
  jobId: string;
  kind: ControllerUploadKind;
  state: 'RUNNING' | 'DONE' | 'FAILED';
  sent: number;
  total: number;
  message?: string;
  /** From the device's apply response — "reboot" for both kinds today. */
  activation?: string;
  /** Firmware only: the version in the image's MCUboot header, as `major.minor.revision+build`. */
  imageVersion?: string;
}

/**
 * Progress of the job that gives a controller's own inputs and outputs their points and publish
 * policies. `DRAFT` fills the draft for review; `APPLY` is adoption's, for a controller that has never
 * been configured, and applies the result. `message` is the platform's reason for a skip or failure.
 */
export interface ControllerProvisionStatus {
  jobId: string;
  mode: 'DRAFT' | 'APPLY';
  state: 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED';
  added: number;
  total: number;
  message?: string;
  iccVersion?: number;
  activation?: string;
}

/** POST /api/inferrix/controllers/{deviceId}/attest. */
export interface ControllerAttestation {
  verified: boolean;
  uid: string;
  certFingerprint: string;
  reason?: string;
}

/** One entry of GET/PUT /api/v1/peers — the address half of the peer table. */
export interface ControllerPeer {
  uid: string;
  ip: string;
  port?: number;
}

// ---------------------------------------------------------------------------
// Config plane
// ---------------------------------------------------------------------------

/**
 * One editable section of the controller's compiled config (its "ICC").
 *
 * The six sections are six lists of flat records with one key field each, so they are described
 * here and rendered by one table and one dialog rather than six of each. `readSection` and
 * `crudPath` differ for exactly one section — the publish policies are read as `policies` and
 * written to `mqtt-policies` — which is why they are separate fields.
 */
export interface ControllerConfigSection {
  key: string;
  titleKey: string;
  readSection: string;
  crudPath: string;
  idField: string;
  /** Record keys shown as table columns, in order. The key field is always first. */
  columns: string[];
  fields: ControllerSettingField[];
  /** What the device refuses to hold more of; shown before the operator hits 409. */
  limit: number;
}

/**
 * The sections a field can pick an id out of.
 *
 * Every one of these is a number the operator used to type. The firmware resolves each against the
 * named section at Apply and rejects the whole draft when one does not resolve, so a typo here
 * costs a round trip to the device and a verifier error that names a class of record rather than a
 * field.
 */
export type ControllerRefSection = 'buses' | 'queries' | 'points' | 'scalings';

/** §5.1 source classes. */
export const POINT_SOURCES = [
  {value: 0, label: 'Local DI'},
  {value: 1, label: 'Local DO'},
  {value: 2, label: 'Local AI'},
  {value: 3, label: 'Local AO'},
  {value: 4, label: 'Modbus RTU'},
  {value: 5, label: 'Wirepas'},
  {value: 6, label: 'System register'},
  {value: 7, label: 'Peer controller'}
];

/** `POINT_SOURCES` value for a point read over Modbus RTU, the one source that names a query. */
export const RTU_POINT_SOURCE = 4;

/** The on-board DI, DO, AI and AO sources: no float format, and DI/DO take no scaling (firmware 0.1.16). */
export const LOCAL_POINT_SOURCES = [0, 1, 2, 3];

/** `POINT_SOURCES` value for a point read from another controller. Its `offset` is the peer id. */
export const PEER_POINT_SOURCE = 7;

/** The highest peer id `check_peers` accepts, and so the highest `offset` a peer point may carry. */
export const MAX_PEER_ID = 3;

/**
 * The most local channels of one kind a channel picker will build a list for.
 *
 * The board reports its own `io` counts and the real ones are single digits, so this is not a
 * product limit -- it is the bound on a number that arrives from the device. Without it a board
 * answering `"di": 1000000000` would have the browser allocate a billion-entry option list.
 */
export const MAX_LOCAL_CHANNELS = 256;

/**
 * Which key of the controller's reported `io` object bounds each local source's channel index.
 *
 * `icc_verify` refuses a local point whose `source_ref` is `>= io->di` (or `dout`, `ai`, `ao` for
 * the other three), so these are the counts that decide what the channel picker may offer. The
 * names are the firmware's own, which is why `do` rather than `dout` — that is the key `/api/v1/info`
 * reports, and `InferrixProvisionService.KIND_KEYS` reads the same four.
 */
export const LOCAL_SOURCE_IO_KEY: {[source: number]: string} = {0: 'di', 1: 'do', 2: 'ai', 3: 'ao'};

/** What a channel of each local source is called on the board's own silkscreen. */
export const LOCAL_SOURCE_CHANNEL_PREFIX: {[source: number]: string} =
  {0: 'DI', 1: 'DO', 2: 'AI', 3: 'AO'};

export const FLOAT_DATA_FORMATS = [4, 5];
export const NO_SCALING = 65535;

/**
 * Whether a config point carries a float: scaled, or read as one. The firmware's own `point_is_real`,
 * and what decides how its value's bits are read — by MQTT, by REST and by a logic tag bound to it.
 */
export const isRealPoint = (point: {scaling_idx?: number; data_format?: number}): boolean =>
  Number(point.scaling_idx) !== NO_SCALING || FLOAT_DATA_FORMATS.includes(Number(point.data_format));

/**
 * Baud rates offered for an RS-485 bus.
 *
 * The firmware imposes no list — it passes `baud` straight to the UART — so this is the standard
 * ladder rather than a constraint, and it exists because a mistyped rate is a bus that never
 * answers and reports nothing more specific than a timeout. 19200 is the devicetree default the
 * board falls back to when a bus record carries no baud.
 */
/**
 * The only five the verifier accepts.
 *
 * `check_referential_integrity()` switches on `b.baud` and returns `ICC_BAD_BUS` for anything else,
 * so the slower standard rates — 1200, 2400, 4800 — are not "unusual but allowed", they are a draft
 * the controller refuses without naming the field.
 */
export const BUS_BAUD_RATES = [9600, 19200, 38400, 57600, 115200]
  .map(baud => ({value: baud, label: String(baud)}));

/**
 * The `framing` byte: low nibble parity, high nibble stop bits (`rtu_poller.c`).
 *
 * The verifier treats the byte as opaque and the poller maps anything it does not recognise onto
 * 8E1, so a wrong value here is not rejected — it silently runs at the Modbus default and the bus
 * either works or does not. These six are every combination the poller actually decodes.
 */
export const BUS_FRAMINGS = [
  {value: 0x01, label: '8E1 \u2014 even parity, 1 stop bit (Modbus default)'},
  {value: 0x00, label: '8N1 \u2014 no parity, 1 stop bit'},
  {value: 0x02, label: '8O1 \u2014 odd parity, 1 stop bit'},
  {value: 0x11, label: '8E2 \u2014 even parity, 2 stop bits'},
  {value: 0x10, label: '8N2 \u2014 no parity, 2 stop bits'},
  {value: 0x12, label: '8O2 \u2014 odd parity, 2 stop bits'}
];

/**
 * The four function codes the RTU poller implements; the verifier rejects anything else outright
 * (`icc_verify.c`: `q.function < 1 || q.function > 4`).
 *
 * Two of them are read-only on the wire, which is a rule the operator cannot see from the number:
 * a writable point may only sit on FC1 or FC3, and one on FC2 or FC4 fails the whole draft.
 */
/**
 * The two QoS levels the controller accepts.
 *
 * `icc_verify` rejects a policy with `qos > 1`, so QoS 2 is not offered: the firmware has no
 * exactly-once path. The number stays in the label because it is what every MQTT tool shows, but
 * the words are what say which one an operator wants.
 */
export const MQTT_QOS_LEVELS = [
  {value: 0, label: 'At most once (QoS 0)'},
  {value: 1, label: 'At least once (QoS 1)'}
];

export const MODBUS_FUNCTIONS = [
  {value: 3, label: 'FC3 \u2014 Read holding registers'},
  {value: 4, label: 'FC4 \u2014 Read input registers'},
  {value: 1, label: 'FC1 \u2014 Read coils'},
  {value: 2, label: 'FC2 \u2014 Read discrete inputs'}
];

/** Function codes whose objects are bits rather than registers, and whose points must be `Bit`. */
export const COIL_FUNCTIONS = [1, 2];

/** Function codes that are read-only on the wire: a writable point on one fails the draft. */
export const READ_ONLY_FUNCTIONS = [2, 4];

/** Modbus's own per-request ceilings, which the verifier enforces as `count`'s maximum. */
export const MAX_COUNT_BITS = 2000;
export const MAX_COUNT_REGISTERS = 125;

/** The verifier's floors for a query's timing (`icc_verify.c` rule 2). */
export const MIN_POLL_INTERVAL_MS = 100;
export const MIN_QUERY_TIMEOUT_MS = 10;

/** How many registers one data format occupies inside a query window. */
export const FORMAT_WIDTH_REGISTERS: {[format: number]: number} = {
  0: 1, 1: 1, 2: 2, 3: 2, 4: 2, 5: 2, 6: 1
};

/** §5.2 data formats. */
export const DATA_FORMATS = [
  {value: 0, label: 'U16'},
  {value: 1, label: 'S16'},
  {value: 2, label: 'U32'},
  {value: 3, label: 'S32'},
  {value: 4, label: 'Float (ABCD)'},
  {value: 5, label: 'Float (CDAB)'},
  {value: 6, label: 'Bit'}
];

/**
 * What one verifier rejection means, and what to go and change.
 *
 * The device answers an Apply with a single enum name and nothing else — not the record, not the
 * field, not the rule. That is all the firmware has: `icc_verify()` returns the first violation it
 * finds and the REST layer hands the name straight back. So the name has to be turned into
 * something actionable here, and `fix` is the part that matters: every line is a rule from
 * `lib/icc/icc_verify.c`, written as the thing to check rather than the thing that is wrong.
 *
 * Kept as data rather than one long translated paragraph per error so the list renders as a list —
 * an operator reads down it and stops at the line that describes their draft.
 */
export interface IccVerifyError {
  /** One sentence naming the defect. */
  what: string;
  /** What to check, most likely cause first. */
  fix: string[];
}

export const ICC_VERIFY_ERRORS: {[name: string]: IccVerifyError} = {
  ICC_LIMITS: {
    what: 'The draft holds more records than the controller can store.',
    fix: ['Each section shows its own limit beside its record count: 2 buses, 64 queries, '
      + '1024 points, 64 scalings, 1024 publish policies, 4 peers.',
      'Delete what the draft no longer uses, then apply again.']
  },
  ICC_TOO_SHORT: {
    what: 'The compiled configuration is malformed.',
    fix: ['Nothing in the draft causes this. Discard the draft and rebuild it, and report it if '
      + 'a rebuilt draft fails the same way.']
  },
  ICC_BAD_MAGIC: {
    what: 'The compiled configuration is malformed.',
    fix: ['Nothing in the draft causes this. Discard the draft and rebuild it, and report it if '
      + 'a rebuilt draft fails the same way.']
  },
  ICC_BAD_SECTIONS: {
    what: 'The compiled configuration is malformed.',
    fix: ['Nothing in the draft causes this. Discard the draft and rebuild it, and report it if '
      + 'a rebuilt draft fails the same way.']
  },
  ICC_BAD_CRC: {
    what: 'The compiled configuration failed its own checksum.',
    fix: ['Apply again — a single corrupted transfer fails this way and the next one usually '
      + 'succeeds.',
      'If it keeps failing, discard the draft and rebuild it.']
  },
  ICC_BAD_FORMAT_VER: {
    what: 'The controller\'s firmware is older than the configuration format this draft needs.',
    fix: ['Update the firmware from the Software tab.',
      'Peer records and publish policies are the two features that raise the format version; a '
      + 'draft without them will apply to older firmware.']
  },
  ICC_RUNTIME_TOO_OLD: {
    what: 'The controller\'s firmware is too old for something in this configuration.',
    fix: ['Update the firmware from the Software tab.']
  },
  ICC_WRONG_PROFILE: {
    what: 'This configuration was built for a different kind of device.',
    fix: ['A configuration saved from another controller model does not fit this one. Apply a '
      + 'template taken from a controller of the same model, or build the draft here.']
  },
  ICC_BAD_FLAGS: {
    what: 'A record carries a flag this firmware does not know.',
    fix: ['Update the firmware from the Software tab: the draft uses a flag a newer release added.']
  },
  ICC_BAD_BUS: {
    what: 'A bus record is invalid, or two buses share an id.',
    fix: ['Baud rate must be one of 9600, 19200, 38400, 57600 or 115200. No other rate is '
      + 'accepted, including the slower standard ones.',
      'Open Buses and check that no two rows have the same Bus id.',
      'This board has one RS-485 port, which is bus 0. A second bus record compiles but has no '
      + 'hardware behind it, and every query on it will time out.']
  },
  ICC_BAD_QUERY: {
    what: 'A query is invalid, or names a bus that is not in the draft.',
    fix: ['Bus: every query\'s bus must exist in the Buses section.',
      'Function code: only FC1, FC2, FC3 and FC4 are supported.',
      'Count: 1 to 125 registers for FC3 and FC4, 1 to 2000 bits for FC1 and FC2.',
      'Window: first register + count must not pass 65536.',
      'Poll interval: at least 100 ms. Timeout: at least 10 ms.',
      'Query id: no two queries may share one.']
  },
  ICC_BAD_POINT: {
    what: 'A point is invalid for the source it reads from.',
    fix: ['Modbus points: the data format must match the query\'s function code — Bit on FC1 and '
      + 'FC2, a register format on FC3 and FC4.',
      'Modbus points: Offset plus the format\'s width must fit inside the query\'s count. A '
      + '32-bit or float format occupies two registers.',
      'Writable Modbus points must sit on FC1 or FC3. FC2 and FC4 are read-only on the wire.',
      'Writable is only valid on a local DO, a local AO, a Modbus point or a peer point.',
      'Local points: the channel number must exist on this board.',
      'Point id: no two points may share one.']
  },
  ICC_BAD_SCALING: {
    what: 'A point names a scaling that is not in the draft.',
    fix: ['Open Scalings and check the scaling ids that exist, then reopen the point and pick one '
      + 'of them.',
      'A point that needs no conversion should be set to No scaling, not to a spare id.']
  },
  ICC_BAD_EXPORT: {
    what: 'A point is exported in a way the firmware cannot serve.',
    fix: ['Two exports claim overlapping registers, or an export is marked writable on a point '
      + 'that is not writable.']
  },
  ICC_BUS_OVERSUBSCRIBED: {
    what: 'The queries on one bus ask for more time than its slowest poll interval allows.',
    fix: ['The budget is the SMALLEST poll interval on the bus, not each query\'s own. Raising '
      + 'one query\'s interval changes nothing while a faster query is still on the bus.',
      'Cost per query is (frame time + timeout) x (1 + retries), summed over every query on the '
      + 'bus. The timeout dominates: at 9600 baud a 2-register read is about 24 ms of frame '
      + 'against a 200 ms timeout.',
      'Cheapest fixes in order: cut the timeouts, drop retries on tolerant points, raise every '
      + 'interval on the bus, merge adjacent registers into one query.',
      'The full arithmetic is in docs/features/controller-modbus-bus-budget.md.']
  },
  ICC_BAD_POLICY: {
    what: 'A publish policy is invalid.',
    fix: ['Trigger: at least one of Interval, On change or On poll must be ticked. Retained on its '
      + 'own is a modifier, not a trigger.',
      'Publish interval must be 1 or more when Interval is ticked, and exactly 0 when it is not.',
      'On poll only works on a Modbus point.',
      'Deadband must be zero or positive, and a real number.',
      'At most one policy per point.']
  },
  ICC_BAD_PEER: {
    what: 'A peer point names a peer that is not in the peer table.',
    fix: ['Open Peers and check which peer ids exist, then reopen the point.',
      'A peer id must be 0 to 3, and its UID must be the peer controller\'s own 24-character '
      + 'hex UID.']
  },
  ICC_BAD_LOCAL_POINT: {
    what: 'A point on this board\'s own inputs or outputs is invalid.',
    fix: ['Local DI and DO points take no scaling — set Scaling to No scaling.',
      'No local point may use a float format; those are for Modbus registers.',
      'A scaling used by a local AO needs a non-zero multiplier.']
  }
};

/**
 * What the controller said when it refused a record, or a point write.
 *
 * <p>Firmware 0.1.15 names the field it could not accept. Before that every rejection on this
 * surface was a bare 400 with an empty body, so a nine-field point record that the device disliked
 * surfaced as "Save failed" and the only way forward was changing one thing at a time. Older
 * firmware still answers with nothing, which is why the generic message survives as the fallback.
 */
export const CONTROLLER_RECORD_ERRORS: {[name: string]: string} = {
  bad_field: 'The controller would not accept one of the fields.',
  missing_body: 'The controller received no record at all.',
  bad_id: 'That record id is not one the controller can use.',
  unknown_point: 'There is no point with that id in the active configuration.',
  type_mismatch: 'That point is not of the kind this write is for.',
  not_writable: 'That point is not marked writable.',
  read_only: 'That point maps to a Modbus object that cannot be written.',
  wbox_full: 'The controller is still working through queued writes; try again shortly.',
  queue_full: 'The controller\'s Modbus write queue is full; try again shortly.',
  busy: 'The controller is already running a probe; try again shortly.',
  malformed: 'The controller could not read that request.',
  bad_request: 'The controller rejected the request.',
  out_of_range: 'That value is outside what the output can produce (0 to 4095 counts, after its scaling).',
  bad_scaling: 'The point\'s scaling has a zero multiplier or divisor, so no value can be converted for it.'
};

/** The device's own words for a refused record, naming the field when the firmware gives one. */
export const controllerRecordError = (error: any, fallback: string): string => {
  const name = error?.error?.error;
  const field = error?.error?.field;
  if (!name) {
    return fallback;
  }
  const described = CONTROLLER_RECORD_ERRORS[name] ?? name;
  return field ? `${described} (${field})` : described;
};

/** The deadband is held as the raw 32 bits of an f32, never as a decimal (§6.3). */
export const floatToBits = (value: number): number => {
  const view = new DataView(new ArrayBuffer(4));
  view.setFloat32(0, value);
  return view.getUint32(0);
};

export const bitsToFloat = (bits: number): number => {
  const view = new DataView(new ArrayBuffer(4));
  view.setUint32(0, bits >>> 0);
  return view.getFloat32(0);
};

const HEX_24 = /^[0-9a-f]{24}$/;

/** The id field each referenced section is keyed by. */
export const REF_SECTION_ID_FIELD: {[section: string]: string} = {
  buses: 'bus_id', queries: 'query_id', points: 'point_id', scalings: 'idx'
};

/**
 * How one referenced record reads in a picker.
 *
 * Each is described by what it *is* rather than by its number, because the number is the thing the
 * operator cannot check. A query has no name of its own, so it is described by what it polls; a
 * scaling by the arithmetic it performs; a point has a name and the id still shows, because that
 * is what the device stores and what the other sections refer to it by.
 */
export function refOptionLabel(kind: ControllerRefSection, record: any): string {
  switch (kind) {
    case 'buses': {
      const framing = BUS_FRAMINGS.find(option => option.value === Number(record.framing));
      // Just the mnemonic: the picker is a line, not a legend.
      const shape = framing ? framing.label.split(' ')[0] : `framing ${record.framing}`;
      return `#${record.bus_id} \u00b7 ${record.baud} baud \u00b7 ${shape}`;
    }
    case 'queries': {
      const from = Number(record.start_reg);
      const to = from + Number(record.count) - 1;
      const fc = MODBUS_FUNCTIONS.find(option => option.value === Number(record.function));
      const code = fc ? fc.label.split(' ')[0] : `FC${record.function}`;
      return `#${record.query_id} \u00b7 unit ${record.unit_id} \u00b7 ${code} \u00b7 reg ${from}-${to}`;
    }
    case 'scalings': {
      const offset = Number(record.offset);
      const sign = offset < 0 ? '\u2212' : '+';
      return `#${record.idx} \u00b7 \u00d7${record.multiplier} \u00f7${record.divisor} `
        + `${sign}${Math.abs(offset)}`;
    }
    default:
      return `#${record.point_id} \u00b7 ${record.name ?? ''}`.trim();
  }
}

export const CONTROLLER_CONFIG_SECTIONS: ControllerConfigSection[] = [
  {
    key: 'buses', titleKey: 'inferrix.section-buses', readSection: 'buses', crudPath: 'buses',
    idField: 'bus_id', limit: 2,
    columns: ['bus_id', 'mode', 'baud', 'framing'],
    fields: [
      {key: 'bus_id', label: 'inferrix.bus-id', type: 'number', min: 0, max: 255,
        required: true, keyField: true, autoId: true, hint: 'inferrix.bus-id-hint'},
      {key: 'mode', label: 'inferrix.bus-mode', type: 'select', required: true,
        defaultValue: 0, options: [{value: 0, label: 'Modbus RTU'}]},
      {key: 'baud', label: 'inferrix.bus-baud', type: 'select', required: true,
        defaultValue: 9600, options: BUS_BAUD_RATES},
      {key: 'framing', label: 'inferrix.bus-framing', type: 'select', required: true,
        defaultValue: 1, options: BUS_FRAMINGS, hint: 'inferrix.bus-framing-hint'}
    ]
  },
  {
    key: 'queries', titleKey: 'inferrix.section-queries', readSection: 'queries', crudPath: 'queries',
    idField: 'query_id', limit: 64,
    columns: ['query_id', 'bus_id', 'unit_id', 'function', 'start_reg', 'count', 'interval_ms'],
    fields: [
      {key: 'query_id', label: 'inferrix.query-id', type: 'number', min: 0, max: 65535,
        required: true, keyField: true, autoId: true},
      {key: 'bus_id', label: 'inferrix.bus-id', type: 'number', min: 0, max: 255, required: true,
        optionsFrom: 'buses'},
      {key: 'unit_id', label: 'inferrix.unit-id', type: 'number', min: 0, max: 255, required: true,
        hint: 'inferrix.unit-id-hint'},
      {key: 'function', label: 'inferrix.function-code', type: 'select', required: true,
        defaultValue: 3, options: MODBUS_FUNCTIONS, hint: 'inferrix.function-code-hint'},
      // Stored and never read: `icc_build` writes the byte, nothing in the firmware looks at it
      // and the verifier does not check it. Sent as 0 rather than typed.
      {key: 'flags', label: 'inferrix.flags', type: 'number', min: 0, max: 255, required: true,
        defaultValue: 0, hidden: true},
      {key: 'start_reg', label: 'inferrix.start-register', type: 'number', min: 0, max: 65535,
        required: true, hint: 'inferrix.start-register-hint'},
      {key: 'count', label: 'inferrix.register-count', type: 'number', min: 1, max: 2000,
        required: true, defaultValue: 1, hint: 'inferrix.register-count-hint'},
      {key: 'interval_ms', label: 'inferrix.poll-interval', type: 'number', min: 100,
        max: 4294967295, required: true, defaultValue: 2000, hint: 'inferrix.poll-interval-hint'},
      {key: 'timeout_ms', label: 'inferrix.timeout', type: 'number', min: 10, max: 65535,
        required: true, defaultValue: 200, hint: 'inferrix.timeout-hint'},
      {key: 'retries', label: 'inferrix.retries', type: 'number', min: 0, max: 255, required: true,
        defaultValue: 1, hint: 'inferrix.retries-hint'}
    ]
  },
  {
    key: 'points', titleKey: 'inferrix.section-points', readSection: 'points', crudPath: 'points',
    idField: 'point_id', limit: 1024,
    columns: ['point_id', 'name', 'source', 'data_format', 'source_ref', 'offset', 'scaling_idx'],
    fields: [
      {key: 'point_id', label: 'inferrix.point-id', type: 'number', min: 0, max: 65535,
        required: true, keyField: true, autoId: true, hint: 'inferrix.point-id-hint'},
      {key: 'name', label: 'inferrix.point-name', type: 'text', maxLength: 15, required: true},
      {key: 'source', label: 'inferrix.point-source', type: 'select', options: POINT_SOURCES,
        required: true},
      {key: 'source_ref', label: 'inferrix.source-ref', type: 'number', min: 0, max: 65535,
        required: true, hint: 'inferrix.source-ref-hint', optionsFrom: 'queries'},
      {key: 'data_format', label: 'inferrix.data-format', type: 'select', options: DATA_FORMATS,
        required: true, hint: 'inferrix.data-format-hint'},
      {key: 'offset', label: 'inferrix.point-offset', type: 'number', min: 0, max: 65535,
        required: true, hint: 'inferrix.point-offset-hint'},
      {key: 'scaling_idx', label: 'inferrix.scaling-idx', type: 'number', min: 0, max: 65535,
        required: true, defaultValue: NO_SCALING, hint: 'inferrix.scaling-idx-hint',
        optionsFrom: 'scalings'},
      {key: 'flags', label: 'inferrix.flags', type: 'flags', required: true, defaultValue: 0,
        bits: [{value: 1, label: 'inferrix.flag-writable'}, {value: 2, label: 'inferrix.flag-refresh'}]},
      {key: 'refresh_s', label: 'inferrix.refresh-interval', type: 'number', min: 0, max: 65535,
        required: true, defaultValue: 0}
    ]
  },
  {
    key: 'scalings', titleKey: 'inferrix.section-scalings', readSection: 'scalings',
    crudPath: 'scalings', idField: 'idx', limit: 64,
    columns: ['idx', 'multiplier', 'divisor', 'offset'],
    fields: [
      {key: 'idx', label: 'inferrix.scaling-id', type: 'number', min: 0, max: 65534,
        required: true, keyField: true, autoId: true, hint: 'inferrix.scaling-id-hint'},
      {key: 'multiplier', label: 'inferrix.multiplier', type: 'number', required: true,
        defaultValue: 1},
      {key: 'divisor', label: 'inferrix.divisor', type: 'number', required: true,
        defaultValue: 1, hint: 'inferrix.divisor-hint'},
      {key: 'offset', label: 'inferrix.scaling-offset', type: 'number', required: true,
        defaultValue: 0}
    ]
  },
  {
    key: 'mqtt-policies', titleKey: 'inferrix.section-policies', readSection: 'policies',
    crudPath: 'mqtt-policies', idField: 'point_id', limit: 1024,
    columns: ['point_id', 'trigger', 'qos', 'interval_s', 'deadband_bits'],
    fields: [
      // Not autoId: a policy's key is the point it publishes, so it is chosen rather than
      // allocated. The picker offers only points that have no policy yet -- the device allows one
      // each, and a second would be refused at Apply as ICC_BAD_POLICY.
      {key: 'point_id', label: 'inferrix.point-id', type: 'number', min: 0, max: 65535,
        required: true, keyField: true, optionsFrom: 'points'},
      {key: 'trigger', label: 'inferrix.trigger', type: 'flags', required: true, defaultValue: 2,
        hint: 'inferrix.trigger-hint',
        bits: [
          {value: 1, label: 'inferrix.trigger-interval'},
          {value: 2, label: 'inferrix.trigger-on-change'},
          {value: 4, label: 'inferrix.trigger-on-poll'},
          {value: 8, label: 'inferrix.trigger-retained'}
        ]},
      {key: 'qos', label: 'inferrix.qos', type: 'select', required: true, defaultValue: 0,
        options: MQTT_QOS_LEVELS, hint: 'inferrix.qos-hint'},
      {key: 'interval_s', label: 'inferrix.publish-interval', type: 'number', min: 0, max: 65535,
        required: true, defaultValue: 0, hint: 'inferrix.publish-interval-hint'},
      {key: 'deadband_bits', label: 'inferrix.deadband', type: 'number', min: 0,
        required: true, float32Bits: true, defaultValue: 0, hint: 'inferrix.deadband-hint'}
    ]
  },
  {
    key: 'peers', titleKey: 'inferrix.section-peers', readSection: 'peers', crudPath: 'peers',
    idField: 'peer_id', limit: 4,
    columns: ['peer_id', 'uid'],
    fields: [
      {key: 'peer_id', label: 'inferrix.peer-id', type: 'number', min: 0, max: 3,
        required: true, keyField: true, autoId: true},
      {key: 'uid', label: 'inferrix.peer-uid', type: 'text', maxLength: 24, required: true,
        pattern: HEX_24, hint: 'inferrix.peer-uid-hint'}
    ]
  }
];

/**
 * The id the platform gives a new record: the lowest the section does not already hold.
 *
 * Lowest free rather than one past the highest, so ids stay dense and reusable after a delete —
 * which matters for buses (two) and peers (four), where "one past the highest" would run out with
 * free slots still in the table. Points need no ordering help: the device inserts a point into its
 * sorted position, so the verifier's strictly-ascending rule holds whichever free id is used.
 *
 * Returns null when the section is full, which is the caller's signal to refuse the add rather
 * than send a record the device would reject with a 409.
 */
export const nextFreeId = (section: ControllerConfigSection, taken: number[]): number => {
  const used = new Set((taken ?? []).map(Number));
  const field = section.fields.find(candidate => candidate.key === section.idField);
  const ceiling = field?.max ?? 65535;
  for (let id = field?.min ?? 0; id <= ceiling; id++) {
    if (!used.has(id)) {
      return id;
    }
  }
  return null;
};

/**
 * A saved controller configuration, as the picker lists it.
 *
 * Config plane records and/or a logic program, and nothing else: network addressing, MQTT, identity
 * and the ownership password are per-controller and never travel in a template.
 */
export interface ControllerTemplateSummary {
  id: string;
  createdTime: number;
  name: string;
  sourceName?: string;
  recordCount: number;
  hasLogic: boolean;
}

/** The same template with its payload: section key -> that section's records, as the device serves them. */
export interface ControllerTemplate extends ControllerTemplateSummary {
  config?: {[sectionKey: string]: any[]};
  logic?: LogicProgram;
}

/**
 * The order a template's sections are written back in.
 *
 * A record that names another section's record is rejected on the spot, so the named section has to
 * exist first: queries name a bus, points name a query and a scaling, policies name a point. Apply
 * verifies the whole draft again at the end, but a write that fails here fails one record at a time
 * and would leave the operator guessing.
 */
export const CONTROLLER_TEMPLATE_WRITE_ORDER = ['buses', 'queries', 'scalings', 'points',
  'mqtt-policies', 'peers'];
