///
/// Copyright © 2016-2026 The Inferrix Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { DeviceInfo } from '@shared/models/device.models';
import { HasUUID } from '@shared/models/id/has-uuid';

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

/** §6.4 verifier rejections, so an apply failure reads as something other than a number. */
export const ICC_VERIFY_ERRORS: {[name: string]: string} = {
  ICC_LIMITS: 'The draft is over a count or size limit.',
  ICC_TOO_SHORT: 'The compiled config is malformed.',
  ICC_BAD_MAGIC: 'The compiled config is malformed.',
  ICC_BAD_FORMAT_VER: 'The controller firmware is too old for this config format.',
  ICC_BAD_FLAGS: 'A record carries a flag the firmware does not know.',
  ICC_BAD_CRC: 'The compiled config failed its checksum.',
  ICC_RUNTIME_TOO_OLD: 'The controller firmware is too old for this config.',
  ICC_WRONG_PROFILE: 'This config was built for a different device profile.',
  ICC_BAD_SECTIONS: 'The compiled config is malformed.',
  ICC_BAD_BUS: 'A query references a bus that does not exist.',
  ICC_BAD_QUERY: 'A point references a query that does not exist, or a query is invalid.',
  ICC_BAD_POINT: 'A point record is invalid for its source class.',
  ICC_BAD_EXPORT: 'A point is exported in a way the firmware cannot serve.',
  ICC_BAD_SCALING: 'A point references a scaling that does not exist.',
  ICC_BUS_OVERSUBSCRIBED: 'The queries on one bus ask for more traffic than its baud rate allows.',
  ICC_BAD_POLICY: 'A publish policy is invalid — check the trigger bits against interval_s.',
  ICC_BAD_PEER: 'A peer-source point references a peer_id that is not in the peer table.'
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
  malformed: 'The controller could not read that request.'
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

export const CONTROLLER_CONFIG_SECTIONS: ControllerConfigSection[] = [
  {
    key: 'buses', titleKey: 'inferrix.section-buses', readSection: 'buses', crudPath: 'buses',
    idField: 'bus_id', limit: 2,
    columns: ['bus_id', 'mode', 'baud', 'framing'],
    fields: [
      {key: 'bus_id', label: 'inferrix.bus-id', type: 'number', min: 0, max: 255,
        required: true, keyField: true},
      {key: 'mode', label: 'inferrix.bus-mode', type: 'select', required: true,
        defaultValue: 0, options: [{value: 0, label: 'Modbus RTU'}]},
      {key: 'baud', label: 'inferrix.bus-baud', type: 'number', min: 0, max: 4294967295,
        required: true},
      {key: 'framing', label: 'inferrix.bus-framing', type: 'number', min: 0, max: 255,
        required: true, hint: 'inferrix.bus-framing-hint'}
    ]
  },
  {
    key: 'queries', titleKey: 'inferrix.section-queries', readSection: 'queries', crudPath: 'queries',
    idField: 'query_id', limit: 64,
    columns: ['query_id', 'bus_id', 'unit_id', 'function', 'start_reg', 'count', 'interval_ms'],
    fields: [
      {key: 'query_id', label: 'inferrix.query-id', type: 'number', min: 0, max: 65535,
        required: true, keyField: true},
      {key: 'bus_id', label: 'inferrix.bus-id', type: 'number', min: 0, max: 255, required: true},
      {key: 'unit_id', label: 'inferrix.unit-id', type: 'number', min: 0, max: 255, required: true},
      {key: 'function', label: 'inferrix.function-code', type: 'number', min: 0, max: 255,
        required: true},
      {key: 'flags', label: 'inferrix.flags', type: 'number', min: 0, max: 255, required: true,
        defaultValue: 0},
      {key: 'start_reg', label: 'inferrix.start-register', type: 'number', min: 0, max: 65535,
        required: true},
      {key: 'count', label: 'inferrix.register-count', type: 'number', min: 0, max: 65535,
        required: true},
      {key: 'interval_ms', label: 'inferrix.poll-interval', type: 'number', min: 0,
        max: 4294967295, required: true},
      {key: 'timeout_ms', label: 'inferrix.timeout', type: 'number', min: 0, max: 65535,
        required: true},
      {key: 'retries', label: 'inferrix.retries', type: 'number', min: 0, max: 255, required: true}
    ]
  },
  {
    key: 'points', titleKey: 'inferrix.section-points', readSection: 'points', crudPath: 'points',
    idField: 'point_id', limit: 1024,
    columns: ['point_id', 'name', 'source', 'data_format', 'source_ref', 'offset', 'scaling_idx'],
    fields: [
      {key: 'point_id', label: 'inferrix.point-id', type: 'number', min: 0, max: 65535,
        required: true, keyField: true, hint: 'inferrix.point-id-hint'},
      {key: 'name', label: 'inferrix.point-name', type: 'text', maxLength: 15, required: true},
      {key: 'source', label: 'inferrix.point-source', type: 'select', options: POINT_SOURCES,
        required: true},
      {key: 'data_format', label: 'inferrix.data-format', type: 'select', options: DATA_FORMATS,
        required: true},
      {key: 'source_ref', label: 'inferrix.source-ref', type: 'number', min: 0, max: 65535,
        required: true, hint: 'inferrix.source-ref-hint'},
      {key: 'offset', label: 'inferrix.point-offset', type: 'number', min: 0, max: 65535,
        required: true, hint: 'inferrix.point-offset-hint'},
      {key: 'scaling_idx', label: 'inferrix.scaling-idx', type: 'number', min: 0, max: 65535,
        required: true, defaultValue: 65535, hint: 'inferrix.scaling-idx-hint'},
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
        required: true, keyField: true, hint: 'inferrix.scaling-id-hint'},
      {key: 'multiplier', label: 'inferrix.multiplier', type: 'number', required: true},
      {key: 'divisor', label: 'inferrix.divisor', type: 'number', required: true,
        hint: 'inferrix.divisor-hint'},
      {key: 'offset', label: 'inferrix.scaling-offset', type: 'number', required: true,
        defaultValue: 0}
    ]
  },
  {
    key: 'mqtt-policies', titleKey: 'inferrix.section-policies', readSection: 'policies',
    crudPath: 'mqtt-policies', idField: 'point_id', limit: 1024,
    columns: ['point_id', 'trigger', 'qos', 'interval_s', 'deadband_bits'],
    fields: [
      {key: 'point_id', label: 'inferrix.point-id', type: 'number', min: 0, max: 65535,
        required: true, keyField: true},
      {key: 'trigger', label: 'inferrix.trigger', type: 'flags', required: true, defaultValue: 0,
        bits: [
          {value: 1, label: 'inferrix.trigger-interval'},
          {value: 2, label: 'inferrix.trigger-on-change'},
          {value: 4, label: 'inferrix.trigger-on-poll'},
          {value: 8, label: 'inferrix.trigger-retained'}
        ]},
      {key: 'qos', label: 'inferrix.qos', type: 'select', required: true, defaultValue: 0,
        options: [{value: 0, label: '0'}, {value: 1, label: '1'}]},
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
        required: true, keyField: true},
      {key: 'uid', label: 'inferrix.peer-uid', type: 'text', maxLength: 24, required: true,
        pattern: HEX_24, hint: 'inferrix.peer-uid-hint'}
    ]
  }
];
