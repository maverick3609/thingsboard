// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Authority } from '@shared/models/authority.enum';
import { DeviceInfo, DeviceInfoFilter } from '@shared/models/device.models';
import { CustomerId } from '@shared/models/id/customer-id';
import { DeviceId } from '@shared/models/id/device-id';
import { escapeCell } from '@shared/models/inferrix-controller.models';

/** The device profile adoption creates; see InferrixGatewayAdoptionService. */
export const INFERRIX_GATEWAY_PROFILE = 'Inferrix Gateway';

/**
 * A gateway that has provisioned itself over MQTT but that the platform has not adopted.
 *
 * `reportedAddress` is the gateway's own claim, published under its own attribute key, and it is
 * used to prefill the adopt form — never to reach the device. The platform reaches a gateway only
 * by the address an operator confirmed at adoption.
 */
export interface PendingGateway {
  deviceId: DeviceId;
  name: string;
  reportedAddress?: string;
  createdTime: number;
}

export interface AdoptGatewayRequest {
  deviceName?: string;
  address: string;
  port?: number;
  clientId: string;
  clientSecret: string;
  /** Only ever set deliberately: it overrides the changed-certificate refusal on re-adoption. */
  acceptDifferentGateway?: boolean;
}

/**
 * Where the platform reaches an already-adopted gateway.
 *
 * No credential, in either direction. The platform holds this gateway's API token sealed and
 * spends it against the new address itself, so a renumbered subnet or a new VPN is a change of
 * address rather than a re-adoption — which is the only other way to say it, and needs a secret
 * Cortex never gives back.
 */
export interface ChangeGatewayConnectionRequest {
  address: string;
  port?: number;
  /** Only ever set deliberately: it overrides the changed-certificate refusal. */
  acceptDifferentGateway?: boolean;
}

/** What the platform recorded about how to reach a gateway, as the Details tab shows it. */
export interface GatewayConnection {
  address?: string;
  port?: number;
  certFingerprint?: string;
  /** The gateway's own claim, published over MQTT under its own key. Never used to reach it. */
  reportedAddress?: string;
}

/**
 * The other direction: where the gateway dials the platform's MQTT broker.
 *
 * {@link GatewayConnection} is how the platform reaches the gateway; this is how the gateway
 * reaches the platform, and the two move for the same reasons — a renumbered subnet or a new VPN
 * breaks both. Held by the gateway rather than by the platform, so it is read and written through
 * the proxy at `/v2/platform-integration/mqtt-configuration`.
 *
 * `userPassword` and `privateKey` are write-only on the gateway and never come back from a read,
 * so an empty one has to mean "unchanged" — see {@link GATEWAY_MQTT_SECRET_FIELDS}.
 */
export interface GatewayMqttConfiguration {
  brokerUri?: string;
  clientId?: string;
  userName?: string;
  userPassword?: string;
  topicFilters?: string;
  autoReconnect?: boolean;
  cleanSession?: boolean;
  keepAliveInterval?: number;
  connectionTimeout?: number;
  /**
   * Carried through a save untouched, and deliberately not offered.
   *
   * The gateway forces `ATLEAST_ONCE` on the running client whatever this says — ThingsBoard caps
   * at QoS 1 — so a control here would be one that changes nothing. It still has to be *sent*:
   * the gateway resolves it with `QosType.valueOf`, which throws on a missing value.
   */
  qosType?: string;
  x509CaCrt?: string;
  awsIot?: boolean;
  privateKey?: string;
  x509ClientCrt?: string;
}

/**
 * Fields the gateway accepts and never returns.
 *
 * Sending an empty one would erase the stored credential, so they are dropped from a save instead.
 * The gateway carries the stored value forward for exactly these two (`SecretFields.merge`), which
 * also means there is no way to *clear* one from here — the same trade the schema-driven forms
 * make for every other gateway secret.
 */
export const GATEWAY_MQTT_SECRET_FIELDS = ['userPassword', 'privateKey'];

/**
 * Read through ThingsBoard's own attribute API rather than an endpoint of ours.
 *
 * These three are already served to anyone holding READ_ATTRIBUTES on the device — the adoption
 * endpoint says so in as many words — and the one server-scope value that is *not* readable, the
 * sealed credential, is not among them. A bespoke read would add a second way to ask the same
 * question and no privacy.
 */
export const GATEWAY_CONNECTION_KEYS =
  ['gwManagementAddress', 'gwManagementPort', 'gwCertFingerprint'];

/**
 * The gateway's own key, in CLIENT_SCOPE because the device writes it.
 *
 * Shown next to the platform's address rather than instead of it: the two disagreeing is exactly
 * what an operator is looking at after a network change, and it is the value they would then type
 * into the form. It is never used to reach the gateway — the device does not get to choose where
 * the platform sends its credential.
 */
export const GATEWAY_REPORTED_KEYS = ['managementAddress'];

/**
 * Attribute rows to a connection.
 *
 * The port arrives as a number from the platform's own attribute API, but a gateway adopted before
 * the port was stored has none at all — so absent stays absent rather than becoming a 0 the form
 * would then offer to save.
 */
export const gatewayConnectionOf = (attributes: {key: string; value: any}[],
                                    reported?: {key: string; value: any}[]): GatewayConnection => {
  const values = new Map((attributes ?? []).map(attribute => [attribute.key, attribute.value]));
  const port = Number(values.get('gwManagementPort'));
  return {
    address: values.get('gwManagementAddress') || undefined,
    port: Number.isFinite(port) && port > 0 ? port : undefined,
    certFingerprint: values.get('gwCertFingerprint') || undefined,
    reportedAddress: (reported ?? []).find(a => a.key === 'managementAddress')?.value || undefined
  };
};

/**
 * Why the platform can or cannot reach a gateway.
 *
 * Mirrors {@code InferrixGatewayReachability} exactly. Kept as a union rather than an enum so an
 * unknown value from a newer backend is a type error here and a handled case at runtime, instead
 * of a silent undefined.
 */
export type GatewayReachabilityReason =
  'OK' | 'UNAUTHORIZED' | 'FORBIDDEN' | 'HTTP_ERROR' | 'NO_ADDRESS' | 'BAD_ADDRESS'
  | 'NO_CREDENTIAL' | 'NO_SEALING_KEY' | 'CERTIFICATE_CHANGED' | 'UNREACHABLE';

export interface GatewayReachability {
  reachable: boolean;
  reason: GatewayReachabilityReason;
  message: string;
  checkedAt: number;
  stackVersion?: string;
}

export type GatewayReachabilityTone = 'ok' | 'warn' | 'error';

/**
 * How loudly to paint a reachability reason.
 *
 * Three of these are deliberately *not* errors. `FORBIDDEN` is the expected steady state until
 * stack ask A10 lands — the service account is deliberately non-administrator, so the
 * platform-link routes answer 403 while everything else works, and red would send operators
 * chasing a healthy device. `NO_ADDRESS` and `NO_CREDENTIAL` mean the gateway was never adopted,
 * which is a thing to finish rather than a fault.
 *
 * Everything else is an error, including a reason this build has never heard of: the backend may
 * add one, and an unknown failure is still a failure.
 */
export const gatewayReachabilityTone = (reason: GatewayReachabilityReason): GatewayReachabilityTone => {
  switch (reason) {
    case 'OK':
      return 'ok';
    case 'FORBIDDEN':
    case 'NO_ADDRESS':
    case 'NO_CREDENTIAL':
      return 'warn';
    default:
      return 'error';
  }
};

/** Translation key for a reason, so the operator reads a sentence rather than a constant. */
export const gatewayReachabilityLabel = (reason: GatewayReachabilityReason): string =>
  `inferrix.gateway.reachability-${(reason ?? 'UNREACHABLE').toLowerCase().replace(/_/g, '-')}`;

/** A gateway as the table renders it. */
export interface GatewayInfo extends DeviceInfo {
  reportedAddress?: string;
  stackVersion?: string;
}

/**
 * How each device-reported column is rendered.
 *
 * Exported as data, not written inline in the resolver, so one test can assert that *every* column
 * escapes. ThingsBoard inserts cell content with `bypassSecurityTrustHtml` and the address here is
 * published by the device over MQTT, so an unescaped column is script in an operator's browser.
 */
export const GATEWAY_COLUMN_VALUES: {[column: string]: (row: GatewayInfo) => string} = {
  name: row => escapeCell(row.name),
  label: row => escapeCell(row.label),
  reportedAddress: row => escapeCell(row.reportedAddress),
  stackVersion: row => escapeCell(row.stackVersion)
};

export interface GatewayTableAccess {
  filter: DeviceInfoFilter;
  readonly: boolean;
  canAdopt: boolean;
}

/**
 * What this user may see and do with gateways.
 *
 * A customer user has no access to the tenant-wide device listing, so the filter carries their
 * customer id and ThingsBoard's own query object picks the right URL.
 *
 * Lives here rather than in the resolver only so it can be tested: a resolver spec drags in the
 * `DialogService` ↔ `EntityLimitExceededDialogComponent` circular import that crashes the karma
 * bundle. Defaults to read-only, so an authority added later cannot silently gain write.
 */
export const gatewayTableAccess = (authority: Authority, customerId: string): GatewayTableAccess => {
  const filter: DeviceInfoFilter = {type: INFERRIX_GATEWAY_PROFILE};
  if (authority === Authority.CUSTOMER_USER) {
    filter.customerId = new CustomerId(customerId);
  }
  const isTenantAdmin = authority === Authority.TENANT_ADMIN;
  return {filter, readonly: !isTenantAdmin, canAdopt: isTenantAdmin};
};

/**
 * Whether a pending row can be adopted without the operator typing an address first.
 *
 * A gateway that has not reported one is still listed — the operator can see it on their own
 * network — but the row opens the form rather than pretending it can adopt straight away.
 */
export const isAdoptable = (pending: PendingGateway): boolean =>
  !!pending?.reportedAddress && pending.reportedAddress.trim().length > 0;
