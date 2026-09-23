// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { GatewaySchemaDocument } from '@shared/models/inferrix-gateway-schema.models';

/**
 * An event detector: the rule that turns a point's value into an event.
 *
 * `sourceId` is the **xid of the data point** it watches, which is why detectors are edited from a
 * point rather than from a list of their own — a detector with no point is not a thing the gateway
 * can store.
 */
export interface GatewayEventDetector {
  id?: number;
  xid?: string;
  name?: string;
  /** Jackson's discriminator, e.g. `BINARY_STATE`. Also the schema's key in the detector family. */
  detectorType?: string;
  sourceId?: string;
  alarmLevel?: string;
  rtnApplicable?: boolean;
  /** Xids of the handlers this detector invokes. */
  handlerXids?: string[];
  [property: string]: any;
}

/**
 * An event handler: what happens when a detector trips.
 *
 * Four types exist and the set is closed — EMAIL, SMS, SET_POINT, PROCESS are core-owned with none
 * contributed by protocol modules, unlike data sources. Cortex offers three of them; see
 * {@link gatewayHandlerRunsCommands} for why the fourth is not one this UI creates.
 */
export interface GatewayEventHandler {
  id?: number;
  xid?: string;
  name?: string;
  /** Jackson's discriminator, e.g. `EMAIL`. Also the schema's key in the handler family. */
  handlerType?: string;
  disabled?: boolean;
  eventTypes?: any[];
  [property: string]: any;
}

/**
 * An alert list — **not** a list of alarms.
 *
 * It is a notification-routing preference: who is told, and when they are not to be told. The
 * raised records are {@link GatewayEventInstance}, on a different screen. Naming both "alerts" in
 * the UI confuses operators permanently, so the two are called Alert routing and Event log.
 */
export interface GatewayAlertList {
  id?: number;
  xid?: string;
  name?: string;
  /** The lowest alarm level this list is told about. */
  receiveAlarmAlerts?: string;
  readPermissions?: string;
  editPermissions?: string;
  recipients?: any[];
  /**
   * A 672-slot do-not-disturb week (7 days x 96 quarter-hours). Carried through a save untouched:
   * nothing in this UI edits it, and a save that dropped it would silently make a list that was
   * quiet at night start paging people at 03:00.
   */
  inactiveSchedule?: any;
  [property: string]: any;
}

/** One raised event, as the gateway's event log serves it. */
export interface GatewayEventInstance {
  id: number;
  eventType?: any;
  activeTimestamp?: number;
  acknowledgedByUsername?: string;
  acknowledgedTimestamp?: number;
  rtnApplicable?: boolean;
  rtnTimestamp?: number;
  rtnCause?: string;
  alarmLevel?: string;
  /**
   * Already a plain translated string on the wire, despite the schema declaring an object — the
   * gateway serialises `MessageTranslation` through `translate(...)`.
   */
  message?: string;
  rtnMessage?: string;
}

/** `{type, name}` as `GET /v2/event-detector-type/{dataType}` serves it. */
export interface GatewayTypeOption {
  type: string;
  name?: string;
}

/**
 * Alarm levels, in the order the gateway declares them.
 *
 * Taken from the enum the schema carries on `alarmLevel`, but kept here as well because the events
 * log needs to colour a level without having loaded a schema document.
 */
export const GATEWAY_ALARM_LEVELS = ['NONE', 'INFORMATION', 'WARNING', 'URGENT', 'CRITICAL',
  'EMERGENCY', 'DO_NOT_LOG', 'IGNORE'];

export type GatewayAlarmTone = 'none' | 'info' | 'warn' | 'error';

/**
 * How loudly to paint an alarm level.
 *
 * `DO_NOT_LOG` and `IGNORE` are not severities at all — they are instructions to the gateway about
 * what *not* to record — so painting them as alarms would invent an incident out of a setting.
 */
export const gatewayAlarmTone = (level: string): GatewayAlarmTone => {
  switch (level) {
    case 'CRITICAL':
    case 'EMERGENCY':
      return 'error';
    case 'WARNING':
    case 'URGENT':
      return 'warn';
    case 'INFORMATION':
      return 'info';
    default:
      return 'none';
  }
};

/**
 * A handler whose configuration is a command line the gateway runs.
 *
 * `PROCESS_HANDLER` carries `activeProcessCommand` and `inactiveProcessCommand`, and the gateway
 * hands both to `Runtime.getRuntime().exec` when the handler fires. Creating one is therefore
 * **remote code execution on the gateway host**, expressed as an ordinary text field in an
 * ordinary schema-driven form.
 *
 * That was survivable while the gateway refused the call: until 2026-09-23 a handler could only be
 * created by a full gateway administrator, and the platform's service account is deliberately not
 * one. Stack fix D16 widened handler creation to the gateway-configuration permission — the exact
 * credential Cortex holds — and the stack's own release notes say what that means: "this permission
 * now carries the ability to run commands on the gateway host. It is no longer meaningfully less
 * than administrator."
 *
 * So the restraint has to live here now. Cortex already excludes `/v2/script*` from the proxy for
 * this reason (`InferrixGatewayRoutes`, INFERRIX.md 11.7); a command handler is the same hazard
 * arriving through a route that is allowed for four other reasons. Offering it would make the RCE
 * decision that section says must be taken deliberately, by accident and in a dropdown.
 *
 * Existing ones stay **visible and deletable** — hiding a row that is on the device would make the
 * list lie about what the gateway will do, and deleting one only ever reduces what it can run.
 * They open read-only.
 */
export const gatewayHandlerRunsCommands = (handlerType: string): boolean =>
  handlerType === 'PROCESS_HANDLER';

/**
 * The handler types this gateway can actually be asked for.
 *
 * Read from the schema document rather than from `GET /v2/event-handler-types`. That endpoint does
 * work — verified live, it answers `{"items":[{"type":"EMAIL_HANDLER","name":"Email"},…],"total":4}`
 * — and it is authoritative about which handlers the gateway *supports*. The schema families answer
 * a different question: which handlers there is a form for. Since a type with no schema entry opens
 * an empty dialog, the form-renderable set is the one worth offering, and it is a subset of what
 * the type endpoint lists. Detectors need both (the offered list is per data type), so
 * `gatewayDetectorTypes` intersects; handlers have no such per-point narrowing, so one read does.
 */
export const gatewayHandlerTypes = (doc: GatewaySchemaDocument): GatewayTypeOption[] =>
  Object.keys(doc?.families?.eventHandler ?? {})
    .filter(type => !gatewayHandlerRunsCommands(type))
    .sort()
    .map(type => ({type}));

/**
 * Detector types valid for one data type, narrowed to those the gateway published a schema for.
 *
 * The gateway decides which detectors suit a point (`supportedDataTypes` behind
 * `/v2/event-detector-type/{dataType}`); the schema decides which of those can be rendered. A type
 * in the first set and not the second would open a dialog with no fields.
 */
export const gatewayDetectorTypes = (doc: GatewaySchemaDocument,
                                     offered: GatewayTypeOption[]): GatewayTypeOption[] => {
  const renderable = doc?.families?.eventDetector ?? {};
  return (offered ?? []).filter(option => option?.type && renderable[option.type]);
};

/**
 * Fields a detector form must not own.
 *
 * `sourceId` is the point the panel is already scoped to; `dataPoint` is the gateway's resolved
 * copy of that whole point, which would render as a second data-point form inside this one; and
 * `sourceTypeName` is set by the gateway, not by the operator.
 */
export const DETECTOR_STRUCTURAL_FIELDS = ['sourceId', 'dataPoint', 'sourceTypeName', 'detectorType'];

/** Fields a handler form must not own: the discriminator picks the form itself. */
export const HANDLER_STRUCTURAL_FIELDS = ['handlerType'];

/** One person, address or list that a handler or an alert list notifies. */
export interface GatewayRecipient {
  recipientType?: string;
  [property: string]: any;
}

/**
 * The five recipient kinds, and the single field each one carries its value in.
 *
 * Closed and core-owned — `RecipientListEntryType` in the stack — which is why this is a table
 * here rather than something read from the gateway. It has to be: the schema declares
 * `RecipientEntryModel` as `{recipientType}` with a discriminator and ships none of the subtypes,
 * so a schema-driven form would render the discriminator and silently drop the address.
 */
export const GATEWAY_RECIPIENT_TYPES: {type: string; field: string}[] = [
  {type: 'EMAIL_ADDRESS', field: 'address'},
  {type: 'PHONE_NUMBER', field: 'number'},
  {type: 'USER_EMAIL_ADDRESS', field: 'username'},
  {type: 'USER_PHONE_NUMBER', field: 'username'},
  {type: 'ALERT_LIST', field: 'xid'}
];

export const recipientValueField = (recipientType: string): string | null =>
  GATEWAY_RECIPIENT_TYPES.find(entry => entry.type === recipientType)?.field ?? null;

export const recipientValue = (recipient: GatewayRecipient): string => {
  const field = recipientValueField(recipient?.recipientType);
  return field ? (recipient[field] ?? '') : '';
};

/**
 * Rebuilds a recipient around a type and a value.
 *
 * Built fresh rather than mutated, because the value field differs per type: editing an
 * EMAIL_ADDRESS into a PHONE_NUMBER by assignment would leave `address` behind beside the new
 * `number`, and the gateway deserialises by discriminator — so the stale field would either be
 * rejected or quietly kept as the truth.
 */
export const buildRecipient = (recipientType: string, value: string): GatewayRecipient => {
  const field = recipientValueField(recipientType);
  return field ? {recipientType, [field]: value} : {recipientType};
};
