// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { PageData } from '@shared/models/page/page-data';
import { PageLink } from '@shared/models/page/page-link';

/**
 * How the gateway answers a list endpoint.
 *
 * Note what is missing: there is no `hasMore`, and `total` counts every row matching the filter
 * *ignoring* limit and offset. So paging is driven from `total` — a short page means the end of
 * the filtered set, never the end of the data.
 */
export interface GatewayPage<T> {
  items: T[];
  total: number;
}

/**
 * Paging, sorting and filtering as the platform accepts them.
 *
 * Deliberately TB's own `PageLink` vocabulary rather than the gateway's. The gateway has no paging
 * parameters at all: its list endpoints read the raw query string as an RQL expression, on every
 * verb. The platform refuses to forward one and constructs the RQL itself from these fields, so
 * nothing an operator types reaches the device's query parser.
 */
export interface GatewayListQuery {
  pageSize?: number;
  page?: number;
  textSearch?: string;
  sortProperty?: string;
  sortOrder?: 'ASC' | 'DESC';
  /** An exact-match filter. Only ever set by this code, never by a form. */
  filterField?: string;
  filterValue?: string;
}

/**
 * Everything the platform will put on a proxied call's query string.
 *
 * The two booleans are not paging. `enable-disable` on the gateway declares ordinary request
 * parameters and parses no RQL at all, so what it needs cannot be expressed as a filter term — and
 * a boolean is the only extra type the platform will carry, because there are two of them and
 * neither can carry a metacharacter.
 */
export interface GatewayProxyParams extends GatewayListQuery {
  enabled?: boolean;
  restart?: boolean;
}

/** Fields every stack model carries, in the order the gateway serialises them. */
interface GatewayModel {
  id?: number;
  xid?: string;
  name?: string;
  /** Jackson's type discriminator, e.g. `MODBUS_IP.DS`. It is also the schema's key. */
  modelType?: string;
}

export interface GatewayDataSource extends GatewayModel {
  enabled?: boolean;
  editPermission?: string;
  [property: string]: any;
}

export interface GatewayPointLocator extends GatewayModel {
  dataType?: string;
  settable?: boolean;
  [property: string]: any;
}

export interface GatewayDataPoint extends GatewayModel {
  enabled?: boolean;
  deviceName?: string;
  dataSourceXid?: string;
  dataSourceName?: string;
  settable?: boolean;
  pointLocator?: GatewayPointLocator;
  [property: string]: any;
}

/** What `GET /v2/point-value/latest/{xid}` answers, of which only the newest row is read. */
export interface GatewayPointValue {
  value?: any;
  timestamp?: number;
  annotation?: string;
  rendered?: string;
}

/**
 * Identity and discriminator fields, which a schema-driven form must not render.
 *
 * They are in the schema because they are in the model, but `id` is a surrogate key, `xid` and
 * `name` are edited as the row's identity, and `modelType` selects the form itself — an input that
 * changed it would ask the form to become a different form while being filled in.
 */
export const GATEWAY_IDENTITY_FIELDS = ['id', 'xid', 'name', 'modelType'];

/**
 * A data source type as `/v2/data-source-types` reports it.
 *
 * `type` is already the full Jackson discriminator (`MODBUS_IP.DS`) — nothing appends a suffix to
 * it. `pointLocatorType` is the type a point on such a data source must carry, and **it is the only
 * place that pairing is published**: the naming looks like a rename and is not one, since
 * `MODBUS_IP.DS` and `MODBUS_SERIAL.DS` both take `MODBUS.PL` and no `MODBUS_IP.PL` exists.
 *
 * Nullable, and one real type returns null (`BACNET_MSTP.DS` on stack 5.1.0), so a caller must have
 * an answer for "the gateway did not say".
 */
export interface GatewayDataSourceType {
  type: string;
  name?: string;
  pointLocatorType?: string | null;
}

/**
 * Turns a gateway page into the page shape TB's tables expect.
 *
 * `hasNext` is computed from `total`, which is why the gateway's total mattering more than the page
 * length is worth stating twice: a filtered page that comes back short is not the last page.
 */
export const gatewayPageToPageData = <T>(page: GatewayPage<T>, pageLink: PageLink): PageData<T> => {
  const items = Array.isArray(page?.items) ? page.items : [];
  const total = Number.isFinite(page?.total) ? page.total : items.length;
  const pageSize = pageLink?.pageSize > 0 ? pageLink.pageSize : items.length || 1;
  const current = pageLink?.page ?? 0;
  return {
    data: items,
    totalPages: Math.ceil(total / pageSize),
    totalElements: total,
    hasNext: (current + 1) * pageSize < total
  };
};

/**
 * The gateway's own words for a failure, where it sent any.
 *
 * Three speakers can answer one of these calls and only one of them is ThingsBoard. The gateway
 * refuses in its own shape — `{code, name, cause, localizedMessage}` — and `localizedMessage` is
 * already translated into the gateway's language, so it is surfaced rather than re-worded. A
 * validation failure is a 422 carrying `result.message[]` of `{level, message, property}`, whose
 * `property` names the field that was rejected; those are joined rather than dropped, because
 * "Validation failed" tells an operator nothing about which of forty Modbus fields is wrong.
 */
export const gatewayErrorMessage = (error: any): string => {
  const body = error?.error;
  const validation = body?.result?.message;
  if (Array.isArray(validation) && validation.length) {
    return validation
      .map((item: any) => item?.property ? `${item.property}: ${item.message}` : item?.message)
      .filter((text: string) => !!text)
      .join('; ');
  }
  return body?.localizedMessage || body?.message || error?.message || 'Request failed';
};

/** A TB page link as the platform's typed query parameters. */
export const gatewayQueryFromPageLink = (pageLink: PageLink): GatewayListQuery => ({
  pageSize: pageLink?.pageSize,
  page: pageLink?.page,
  textSearch: pageLink?.textSearch ?? undefined,
  sortProperty: pageLink?.sortOrder?.property,
  sortOrder: pageLink?.sortOrder?.direction as 'ASC' | 'DESC'
});
