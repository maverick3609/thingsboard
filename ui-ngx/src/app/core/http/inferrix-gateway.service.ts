// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, shareReplay } from 'rxjs/operators';
import { defaultHttpOptionsFromConfig, QueryParams, RequestConfig } from '@core/http/http-utils';
import { Device } from '@shared/models/device.models';
import { AdoptGatewayRequest, ChangeGatewayConnectionRequest, GatewayMqttConfiguration,
  GatewayReachability, PendingGateway } from '@shared/models/inferrix-gateway.models';
import { GatewaySchemaDocument } from '@shared/models/inferrix-gateway-schema.models';
import { GatewayBacnetLocalDevice, GatewayBacnetObjectProperties, GatewayBacnetObjectType,
  GatewayDataPoint, GatewayDataSource, GatewayDataSourceType, GatewayDeviceProfile,
  GatewayListQuery, GatewayPage, GatewayPointValue,
  GatewayProxyParams } from '@shared/models/inferrix-gateway-data.models';
import { GatewayPublishedPoint, GatewayPublisher,
  GatewayPublisherType } from '@shared/models/inferrix-gateway-publisher.models';
import { GatewayAlertList, GatewayEventDetector, GatewayEventHandler, GatewayEventInstance,
  GatewayTypeOption } from '@shared/models/inferrix-gateway-event.models';
import { GatewayCalendarRuleSet, GatewaySchedule,
  gatewayWeek } from '@shared/models/inferrix-gateway-schedule.models';
import { GatewayAbout, GatewayLanguage, GatewayMonitorValue, GatewayNetworkInterface,
  GatewaySystemSettings } from '@shared/models/inferrix-gateway-system.models';

/**
 * HTTP client for /api/inferrix/gateways.
 *
 * Every call to a gateway goes through the platform, never the browser. A gateway serves a
 * certificate no public CA can issue for — it lives on a LAN or a VPN under an address no
 * certificate can assert — so the platform holds the pinned fingerprint, and it holds the API
 * token that is exchanged for a short-lived JWT. The browser handles none of them.
 */
/** Matches `InferrixGatewaySchemaService.CACHE_TTL` on the platform side. */
const SCHEMA_CACHE_MS = 30 * 60 * 1000;

@Injectable({
  providedIn: 'root'
})
export class InferrixGatewayService {

  /** Per-gateway schema documents, see {@link getSchemas}. */
  private readonly schemaCache =
    new Map<string, {at: number; document$: Observable<GatewaySchemaDocument>}>();

  constructor(private http: HttpClient) {}

  /** Gateways that have provisioned themselves over MQTT and have no sealed address yet. */
  public getPendingGateways(config?: RequestConfig): Observable<PendingGateway[]> {
    return this.http.get<PendingGateway[]>('/api/inferrix/gateways/pending',
      defaultHttpOptionsFromConfig(config));
  }

  /**
   * Both halves of the credential are issued on the gateway and pasted in — the platform mints
   * nothing. It seals them, so neither half comes back out of any API afterwards.
   */
  public adoptGateway(request: AdoptGatewayRequest, config?: RequestConfig): Observable<Device> {
    return this.http.post<Device>('/api/inferrix/gateways/adopt', request,
      defaultHttpOptionsFromConfig(config));
  }

  /**
   * Points an adopted gateway at a new address.
   *
   * The credential is not asked for and not sent: the platform already holds this gateway's sealed
   * API token and spends it against the new address to prove the move. Nothing is stored until it
   * does, so a failure here leaves the gateway reachable where it was.
   */
  public changeConnection(deviceId: string, request: ChangeGatewayConnectionRequest,
                          config?: RequestConfig): Observable<void> {
    return this.http.post<void>(`/api/inferrix/gateways/${deviceId}/connection`, request,
      defaultHttpOptionsFromConfig(config));
  }

  /**
   * Never errors: the endpoint turns every failure into a reason, because a probe that throws
   * makes the one question it is asked unanswerable.
   */
  public getReachability(deviceId: string, config?: RequestConfig): Observable<GatewayReachability> {
    return this.http.get<GatewayReachability>(`/api/inferrix/gateways/${deviceId}/reachability`,
      defaultHttpOptionsFromConfig(config));
  }

  /**
   * The gateway's own description of every model type it supports.
   *
   * The WHOLE document, never a slice: nested types are `$ref`s into its own `components.schemas`,
   * so a slice would hold dangling references. Cached platform-side per device for 30 minutes — it
   * changes only when the gateway's build changes.
   */
  /**
   * The gateway's whole schema document, fetched at most once per gateway per cache window.
   *
   * **The caching is not a micro-optimisation.** A real 5.1.0 document is 531 KB — 63 data source
   * types, 61 locators, 178 shared components — and four panels want it: data sources, data points,
   * event handlers and the detectors dialog. ThingsBoard ships with `HTTP_COMPRESSION_ENABLED`
   * defaulting to **false**, so without this a tenant administrator opening one gateway's details
   * page downloads and parses two megabytes of JSON to render four forms.
   *
   * The window matches `InferrixGatewaySchemaService.CACHE_TTL` on the platform side deliberately.
   * Within it the platform would answer from its own cache with the same bytes, so holding them here
   * adds no staleness that was not already there — it only stops the browser asking for what it was
   * about to be told again. A gateway upgraded mid-window shows its old forms until the window
   * passes or the page is reloaded, which is the same behaviour the platform cache already has.
   *
   * `shareReplay` rather than a stored value: several panels activate at once on a details page, and
   * a plain flag would let all four fire before the first response arrived.
   */
  public getSchemas(deviceId: string, config?: RequestConfig): Observable<GatewaySchemaDocument> {
    const cached = this.schemaCache.get(deviceId);
    if (cached && Date.now() - cached.at < SCHEMA_CACHE_MS) {
      return cached.document$;
    }
    const document$ = this.http.get<GatewaySchemaDocument>(
      `/api/inferrix/gateways/${deviceId}/schemas`, defaultHttpOptionsFromConfig(config)).pipe(
        // A failure must not be cached as though it were a document, or one unreachable moment
        // would blank every form on this gateway for the rest of the window.
        catchError(error => {
          this.schemaCache.delete(deviceId);
          return throwError(() => error);
        }),
        shareReplay({bufferSize: 1, refCount: false}));
    this.schemaCache.set(deviceId, {at: Date.now(), document$});
    return document$;
  }

  /**
   * One allowlisted call to the gateway's own REST API.
   *
   * `path` is a resource path with no `/rest` prefix and **no query string**. The gateway parses a
   * raw query string as RQL on every verb, so the platform refuses to forward one: paging, sorting
   * and filtering travel as the typed `query` fields below, and the platform builds the RQL.
   *
   * Errors default to `ignoreErrors`, because every caller of this method renders the failure in
   * the panel or dialog that asked for it. The global toast would be a second report of the same
   * thing, and a worse one: the platform relays the gateway's own error body verbatim, which has
   * no `message` field, so the toast falls through to the status line and reads "500: OK".
   */
  public proxy<T>(deviceId: string, method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
                  path: string, body?: any, config?: RequestConfig,
                  params?: GatewayProxyParams): Observable<T> {
    const url = `/api/inferrix/gateways/${deviceId}/proxy${path}`;
    // The query goes through `queryParams` rather than over the returned options, because the
    // options carry an InterceptorHttpParams holding `ignoreLoading` and `ignoreErrors` -- a
    // plain HttpParams assigned over it would drop both on the floor.
    const options = defaultHttpOptionsFromConfig(
      {ignoreErrors: true, ...config, queryParams: httpParams(params)});
    switch (method) {
      case 'GET':
        return this.http.get<T>(url, options);
      case 'POST':
        return this.http.post<T>(url, body ?? {}, options);
      case 'PUT':
        return this.http.put<T>(url, body ?? {}, options);
      case 'PATCH':
        return this.http.patch<T>(url, body ?? {}, options);
      default:
        return this.http.delete<T>(url, options);
    }
  }

  // --- Data sources -------------------------------------------------------------------------

  public getDataSources(deviceId: string, query: GatewayListQuery,
                        config?: RequestConfig): Observable<GatewayPage<GatewayDataSource>> {
    return this.proxy<GatewayPage<GatewayDataSource>>(deviceId, 'GET', '/v2/data-source',
      null, config, query);
  }

  public getDataSource(deviceId: string, xid: string,
                       config?: RequestConfig): Observable<GatewayDataSource> {
    return this.proxy<GatewayDataSource>(deviceId, 'GET', `/v2/data-source/${encodeURIComponent(xid)}`,
      null, config);
  }

  /**
   * A saved data source keeps its `xid`: the gateway's own identifier is what every point, event
   * detector and publisher on the device refers to it by, so a create that invented a new one and
   * an update that changed it would both orphan everything pointing at it.
   */
  public saveDataSource(deviceId: string, dataSource: GatewayDataSource,
                        config?: RequestConfig): Observable<GatewayDataSource> {
    return dataSource.xid
      ? this.proxy<GatewayDataSource>(deviceId, 'PUT',
          `/v2/data-source/${encodeURIComponent(dataSource.xid)}`, dataSource, config)
      : this.proxy<GatewayDataSource>(deviceId, 'POST', '/v2/data-source', dataSource, config);
  }

  public deleteDataSource(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/data-source/${encodeURIComponent(xid)}`,
      null, config);
  }

  /** `restart` only means anything with `enabled` true — the gateway says so itself. */
  public setDataSourceEnabled(deviceId: string, xid: string, enabled: boolean,
                              restart = false, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'PATCH',
      `/v2/data-source/enable-disable/${encodeURIComponent(xid)}`, null, config,
      {enabled, restart: enabled && restart});
  }

  /** Carries `pointLocatorType` — the only published data-source-to-point-locator pairing. */
  public getDataSourceTypes(deviceId: string,
                            config?: RequestConfig): Observable<GatewayPage<GatewayDataSourceType>> {
    return this.proxy<GatewayPage<GatewayDataSourceType>>(deviceId, 'GET',
      '/v2/data-source-types', null, config);
  }

  // --- Platform provisioning ----------------------------------------------------------------

  /**
   * Data sources the gateway does not yet publish to the platform.
   *
   * "Unprovisioned" is the gateway's own bookkeeping, not an inference: it means no
   * `IntegrationMappingData` row links this data source to a publisher and a device. A data source
   * can therefore be publishing — a publisher built by hand does flow telemetry — and still be
   * listed here, because nothing recorded the link.
   */
  public getUnprovisionedDataSources(deviceId: string, query: GatewayListQuery,
                                     config?: RequestConfig): Observable<GatewayPage<GatewayDataSource>> {
    return this.proxy<GatewayPage<GatewayDataSource>>(deviceId, 'GET',
      '/v2/platform-integration/unprovisioned', null, config, query);
  }

  public getProvisionedDataSources(deviceId: string, query: GatewayListQuery,
                                   config?: RequestConfig): Observable<GatewayPage<GatewayDataSource>> {
    return this.proxy<GatewayPage<GatewayDataSource>>(deviceId, 'GET',
      '/v2/platform-integration/provisioned', null, config, query);
  }

  /**
   * Queue a data source to become a device on the platform.
   *
   * Returns as soon as the gateway has queued the work, not when the device exists. The gateway
   * drains that queue on a timer and takes **one data source per minute**, so a caller that
   * provisions several must expect them to appear one a minute and must not read the result back
   * as confirmation.
   *
   * `profileId` is the gateway's own device-profile row id, not the platform's UUID — see
   * {@link GatewayDeviceProfile}.
   */
  public provisionDataSource(deviceId: string, xid: string, profileId: number,
                             config?: RequestConfig): Observable<GatewayDataSource> {
    // Both segments are escaped, the numeric one included. The platform's allowlist would refuse a
    // path that did not match `[0-9]{1,10}` anyway, but these ids come off a gateway row typed
    // `[property: string]: any` — so the type is not the thing keeping them numeric.
    return this.proxy<GatewayDataSource>(deviceId, 'PUT',
      `/v2/platform-integration/provision/${encodeURIComponent(xid)}`
        + `/${encodeURIComponent(profileId)}`, null, config);
  }

  /**
   * Drop the mapping and delete the publisher that fed one device.
   *
   * Keyed by the data source's numeric id, unlike every other write in this service. The device
   * itself may be left behind on the platform — the gateway records no platform device id, so it
   * cannot always delete it and says so in its log rather than failing.
   */
  public unprovisionDataSource(deviceId: string, dataSourceId: number,
                               config?: RequestConfig): Observable<GatewayDataSource> {
    return this.proxy<GatewayDataSource>(deviceId, 'DELETE',
      `/v2/platform-integration/provisioned/${encodeURIComponent(dataSourceId)}`, null, config);
  }

  /**
   * Where the gateway dials this platform's MQTT broker.
   *
   * The write-only fields come back absent, not blank — the gateway omits them — so a caller
   * cannot tell a stored password from no password, and must not try.
   */
  public getMqttConfiguration(deviceId: string,
                              config?: RequestConfig): Observable<GatewayMqttConfiguration> {
    return this.proxy<GatewayMqttConfiguration>(deviceId, 'GET',
      '/v2/platform-integration/mqtt-configuration', null, config);
  }

  /**
   * Saves it, which reconnects the gateway's MQTT client in place.
   *
   * No stack restart: the gateway listens for this settings row changing and rebuilds its client
   * against the new row. So a save takes effect within seconds, and a wrong broker URI takes the
   * gateway's telemetry offline just as quickly.
   *
   * Send the whole object. The gateway stores what it is given — there is no field-level merge
   * beyond the two write-only secrets — so a partial body would reset everything it omitted.
   */
  public saveMqttConfiguration(deviceId: string, configuration: GatewayMqttConfiguration,
                               config?: RequestConfig): Observable<GatewayMqttConfiguration> {
    return this.proxy<GatewayMqttConfiguration>(deviceId, 'POST',
      '/v2/platform-integration/mqtt-configuration', configuration, config);
  }

  public getGatewayDeviceProfiles(deviceId: string, query: GatewayListQuery,
                                  config?: RequestConfig): Observable<GatewayPage<GatewayDeviceProfile>> {
    return this.proxy<GatewayPage<GatewayDeviceProfile>>(deviceId, 'GET',
      '/v2/platform-integration/device-profile', null, config, query);
  }

  /** Re-reads the platform's profiles into the gateway's copy, then answers the refreshed list. */
  public syncGatewayDeviceProfiles(deviceId: string, query: GatewayListQuery,
                                   config?: RequestConfig): Observable<GatewayPage<GatewayDeviceProfile>> {
    return this.proxy<GatewayPage<GatewayDeviceProfile>>(deviceId, 'GET',
      '/v2/platform-integration/device-profile/sync', null, config, query);
  }

  // --- Data points --------------------------------------------------------------------------

  public getDataPoints(deviceId: string, query: GatewayListQuery,
                       config?: RequestConfig): Observable<GatewayPage<GatewayDataPoint>> {
    return this.proxy<GatewayPage<GatewayDataPoint>>(deviceId, 'GET', '/v2/data-point',
      null, config, query);
  }

  public getDataPoint(deviceId: string, xid: string,
                      config?: RequestConfig): Observable<GatewayDataPoint> {
    return this.proxy<GatewayDataPoint>(deviceId, 'GET', `/v2/data-point/${encodeURIComponent(xid)}`,
      null, config);
  }

  public saveDataPoint(deviceId: string, point: GatewayDataPoint,
                       config?: RequestConfig): Observable<GatewayDataPoint> {
    return point.xid
      ? this.proxy<GatewayDataPoint>(deviceId, 'PUT',
          `/v2/data-point/${encodeURIComponent(point.xid)}`, point, config)
      : this.proxy<GatewayDataPoint>(deviceId, 'POST', '/v2/data-point', point, config);
  }

  public setDataPointEnabled(deviceId: string, xid: string, enabled: boolean,
                             config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'PATCH',
      `/v2/data-point/enable-disable/${encodeURIComponent(xid)}`, null, config, {enabled});
  }

  public deleteDataPoint(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/data-point/${encodeURIComponent(xid)}`,
      null, config);
  }

  /**
   * The point's most recent value.
   *
   * Read-only, deliberately. Writing one is a write to live building plant, and the platform's
   * route allowlist permits only GET on `/v2/point-value` until that is decided explicitly.
   */
  public getLatestPointValue(deviceId: string, xid: string,
                             config?: RequestConfig): Observable<GatewayPointValue[]> {
    return this.proxy<GatewayPointValue[]>(deviceId, 'GET',
      `/v2/point-value/latest/${encodeURIComponent(xid)}`, null, config);
  }

  // --- Publishers ---------------------------------------------------------------------------

  public getPublishers(deviceId: string, query: GatewayListQuery,
                       config?: RequestConfig): Observable<GatewayPage<GatewayPublisher>> {
    return this.proxy<GatewayPage<GatewayPublisher>>(deviceId, 'GET', '/v2/publisher',
      null, config, query);
  }

  /**
   * One publisher, with its published points inline.
   *
   * The list read already carries them, but a row that was paged minutes ago has a stale point
   * list, and the points are what the operator is about to edit -- so an open re-reads.
   */
  public getPublisher(deviceId: string, xid: string,
                      config?: RequestConfig): Observable<GatewayPublisher> {
    return this.proxy<GatewayPublisher>(deviceId, 'GET', `/v2/publisher/${encodeURIComponent(xid)}`,
      null, config);
  }

  /** Keeps its `xid` for the same reason a data source does: its points refer to it by that. */
  public savePublisher(deviceId: string, publisher: GatewayPublisher,
                       config?: RequestConfig): Observable<GatewayPublisher> {
    return publisher.xid
      ? this.proxy<GatewayPublisher>(deviceId, 'PUT',
          `/v2/publisher/${encodeURIComponent(publisher.xid)}`, publisher, config)
      : this.proxy<GatewayPublisher>(deviceId, 'POST', '/v2/publisher', publisher, config);
  }

  public deletePublisher(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/publisher/${encodeURIComponent(xid)}`,
      null, config);
  }

  public setPublisherEnabled(deviceId: string, xid: string, enabled: boolean,
                             config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'PATCH',
      `/v2/publisher/enable-disable/${encodeURIComponent(xid)}`, null, config, {enabled});
  }

  public getPublisherTypes(deviceId: string,
                           config?: RequestConfig): Observable<GatewayPage<GatewayPublisherType>> {
    return this.proxy<GatewayPage<GatewayPublisherType>>(deviceId, 'GET',
      '/v2/publisher-types', null, config);
  }

  // --- Published points ---------------------------------------------------------------------

  /**
   * A published point is written on its own, never as part of its publisher.
   *
   * The publisher read carries `points` inline and a publisher save ignores them, which is how the
   * gateway's own webapp works too -- so the inline table edits rows through here while the
   * publisher form above it edits only the connection.
   */
  public savePublishedPoint(deviceId: string, point: GatewayPublishedPoint,
                            config?: RequestConfig): Observable<GatewayPublishedPoint> {
    return point.xid
      ? this.proxy<GatewayPublishedPoint>(deviceId, 'PUT',
          `/v2/published-points/${encodeURIComponent(point.xid)}`, point, config)
      : this.proxy<GatewayPublishedPoint>(deviceId, 'POST', '/v2/published-points', point, config);
  }

  public deletePublishedPoint(deviceId: string, xid: string,
                              config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE',
      `/v2/published-points/${encodeURIComponent(xid)}`, null, config);
  }

  /** PUT, not PATCH -- the published-point route is the one enable-disable that differs. */
  public setPublishedPointEnabled(deviceId: string, xid: string, enabled: boolean,
                                  config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'PUT',
      `/v2/published-points/enable-disable/${encodeURIComponent(xid)}`, null, config, {enabled});
  }

  // --- Event detectors ------------------------------------------------------------------------

  /**
   * The detectors watching one data point.
   *
   * Always scoped to a point, because a detector that watches nothing is not a thing the gateway
   * can store. The scoping is an RQL `eq` term the platform builds, so it costs one page rather
   * than a filter over every detector on the device.
   *
   * The term is `dataPointId`, the point's surrogate id, NOT the `sourceId` the detector body
   * carries: the query runs against the gateway's `event_detectors` table, whose only source
   * column is `dataPointId`, and the model's string `sourceId` exists solely because the model
   * maps that column to and from the point's xid. Asking for `sourceId` is a 500.
   */
  // --- BACnet lookups -----------------------------------------------------------------------

  /**
   * The gateway's own BACnet local devices.
   *
   * `BACNET_IP.DS.localDeviceConfig` is declared a bare string and is a key into these rows, so a
   * data source cannot be created without one: `BACnetDataSourceDefinition.validate` looks it up
   * and rejects a value that resolves to nothing. This is the route the allowlist carries it for.
   */
  public getBacnetLocalDevices(deviceId: string,
                               config?: RequestConfig): Observable<GatewayBacnetLocalDevice[]> {
    return this.proxy<GatewayBacnetLocalDevice[]>(deviceId, 'GET', '/v2/bacnet/local-devices',
      null, config);
  }

  /** Every BACnet object type the gateway decodes, with its own translated name. */
  public getBacnetObjectTypes(deviceId: string,
                              config?: RequestConfig): Observable<GatewayBacnetObjectType[]> {
    return this.proxy<GatewayBacnetObjectType[]>(deviceId, 'GET', '/v2/bacnet/object-types',
      null, config);
  }

  /**
   * The properties of one object type, each with the data types it can be read as.
   *
   * Answers a single object rather than a page, and `typeName` is a path segment -- the one place
   * a gateway-supplied value is put back into a URL, so it is encoded.
   */
  public getBacnetObjectProperties(deviceId: string, objectType: string,
                                   config?: RequestConfig): Observable<GatewayBacnetObjectProperties> {
    return this.proxy<GatewayBacnetObjectProperties>(deviceId, 'GET',
      `/v2/bacnet/object-properties/${encodeURIComponent(objectType)}`, null, config);
  }

  public getDetectorsForPoint(deviceId: string, pointId: number, query: GatewayListQuery,
                              config?: RequestConfig): Observable<GatewayPage<GatewayEventDetector>> {
    return this.proxy<GatewayPage<GatewayEventDetector>>(deviceId, 'GET', '/v2/event-detector',
      null, config, {...query, filterField: 'dataPointId', filterValue: String(pointId)});
  }

  /** Which detector types suit a point of this data type. The gateway decides, not this code. */
  public getDetectorTypes(deviceId: string, dataType: string,
                          config?: RequestConfig): Observable<GatewayPage<GatewayTypeOption>> {
    return this.proxy<GatewayPage<GatewayTypeOption>>(deviceId, 'GET',
      `/v2/event-detector-type/${encodeURIComponent(dataType)}`, null, config);
  }

  public saveDetector(deviceId: string, detector: GatewayEventDetector,
                      config?: RequestConfig): Observable<GatewayEventDetector> {
    return detector.xid
      ? this.proxy<GatewayEventDetector>(deviceId, 'PUT',
          `/v2/event-detector/${encodeURIComponent(detector.xid)}`, detector, config)
      : this.proxy<GatewayEventDetector>(deviceId, 'POST', '/v2/event-detector', detector, config);
  }

  public deleteDetector(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/event-detector/${encodeURIComponent(xid)}`,
      null, config);
  }

  // --- Event handlers -------------------------------------------------------------------------

  public getEventHandlers(deviceId: string, query: GatewayListQuery,
                          config?: RequestConfig): Observable<GatewayPage<GatewayEventHandler>> {
    return this.proxy<GatewayPage<GatewayEventHandler>>(deviceId, 'GET', '/v2/event-handler',
      null, config, query);
  }

  public getEventHandler(deviceId: string, xid: string,
                         config?: RequestConfig): Observable<GatewayEventHandler> {
    return this.proxy<GatewayEventHandler>(deviceId, 'GET',
      `/v2/event-handler/${encodeURIComponent(xid)}`, null, config);
  }

  public saveEventHandler(deviceId: string, handler: GatewayEventHandler,
                          config?: RequestConfig): Observable<GatewayEventHandler> {
    return handler.xid
      ? this.proxy<GatewayEventHandler>(deviceId, 'PUT',
          `/v2/event-handler/${encodeURIComponent(handler.xid)}`, handler, config)
      : this.proxy<GatewayEventHandler>(deviceId, 'POST', '/v2/event-handler', handler, config);
  }

  public deleteEventHandler(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/event-handler/${encodeURIComponent(xid)}`,
      null, config);
  }

  // --- Alert routing --------------------------------------------------------------------------

  public getAlertLists(deviceId: string, query: GatewayListQuery,
                       config?: RequestConfig): Observable<GatewayPage<GatewayAlertList>> {
    return this.proxy<GatewayPage<GatewayAlertList>>(deviceId, 'GET', '/v2/alert-list',
      null, config, query);
  }

  public getAlertList(deviceId: string, xid: string,
                      config?: RequestConfig): Observable<GatewayAlertList> {
    return this.proxy<GatewayAlertList>(deviceId, 'GET',
      `/v2/alert-list/${encodeURIComponent(xid)}`, null, config);
  }

  public saveAlertList(deviceId: string, alertList: GatewayAlertList,
                       config?: RequestConfig): Observable<GatewayAlertList> {
    return alertList.xid
      ? this.proxy<GatewayAlertList>(deviceId, 'PUT',
          `/v2/alert-list/${encodeURIComponent(alertList.xid)}`, alertList, config)
      : this.proxy<GatewayAlertList>(deviceId, 'POST', '/v2/alert-list', alertList, config);
  }

  public deleteAlertList(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/alert-list/${encodeURIComponent(xid)}`,
      null, config);
  }

  // --- Event log ------------------------------------------------------------------------------

  /**
   * The raised events.
   *
   * No `textSearch`: the events table has no `name` column, and the platform's free-text search
   * builds `match(name, ...)` — which the gateway answers with an unknown-property failure naming
   * every column it does have.
   */
  public getEvents(deviceId: string, query: GatewayListQuery,
                   config?: RequestConfig): Observable<GatewayPage<GatewayEventInstance>> {
    return this.proxy<GatewayPage<GatewayEventInstance>>(deviceId, 'GET', '/v2/events',
      null, config, {...query, textSearch: undefined});
  }

  /** Acknowledging takes the event's numeric id — the one place an id rather than an xid is used. */
  public acknowledgeEvent(deviceId: string, id: number, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'PUT', `/v2/events/acknowledge/${encodeURIComponent(String(id))}`,
      null, config);
  }

  // --- Schedules ------------------------------------------------------------------------------

  public getSchedules(deviceId: string, query: GatewayListQuery,
                      config?: RequestConfig): Observable<GatewayPage<GatewaySchedule>> {
    return this.proxy<GatewayPage<GatewaySchedule>>(deviceId, 'GET', '/v2/schedules',
      null, config, query);
  }

  public getSchedule(deviceId: string, xid: string,
                     config?: RequestConfig): Observable<GatewaySchedule> {
    return this.proxy<GatewaySchedule>(deviceId, 'GET',
      `/v2/schedules/${encodeURIComponent(xid)}`, null, config);
  }

  /**
   * A schedule always goes out with seven days and an `exceptions` array.
   *
   * Both are enforced here rather than in the dialog because both are wire requirements rather than
   * form rules, and both fail in a way the operator cannot act on: a missing `exceptions` is HTTP
   * 422 `{"property":"exceptions","message":"Required value"}` on every save, and a week of any
   * other length is accepted and then breaks on enable (see {@link gatewayWeek}).
   */
  public saveSchedule(deviceId: string, schedule: GatewaySchedule,
                      config?: RequestConfig): Observable<GatewaySchedule> {
    const body: GatewaySchedule = {...schedule,
      defaultSchedule: gatewayWeek(schedule.defaultSchedule),
      exceptions: schedule.exceptions ?? []};
    return body.xid
      ? this.proxy<GatewaySchedule>(deviceId, 'PUT',
          `/v2/schedules/${encodeURIComponent(body.xid)}`, body, config)
      : this.proxy<GatewaySchedule>(deviceId, 'POST', '/v2/schedules', body, config);
  }

  public deleteSchedule(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/schedules/${encodeURIComponent(xid)}`,
      null, config);
  }

  /** PUT, not PATCH — schedules are the one domain whose enable route is not a PATCH. */
  public setScheduleEnabled(deviceId: string, xid: string, enabled: boolean,
                            config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'PUT',
      `/v2/schedules/enable-disable/${encodeURIComponent(xid)}`, null, config, {enabled});
  }

  // --- Calendar rule sets ---------------------------------------------------------------------

  public getRuleSets(deviceId: string, query: GatewayListQuery,
                     config?: RequestConfig): Observable<GatewayPage<GatewayCalendarRuleSet>> {
    return this.proxy<GatewayPage<GatewayCalendarRuleSet>>(deviceId, 'GET',
      '/v2/schedule-rule-sets', null, config, query);
  }

  public saveRuleSet(deviceId: string, ruleSet: GatewayCalendarRuleSet,
                     config?: RequestConfig): Observable<GatewayCalendarRuleSet> {
    return ruleSet.xid
      ? this.proxy<GatewayCalendarRuleSet>(deviceId, 'PUT',
          `/v2/schedule-rule-sets/${encodeURIComponent(ruleSet.xid)}`, ruleSet, config)
      : this.proxy<GatewayCalendarRuleSet>(deviceId, 'POST', '/v2/schedule-rule-sets',
          ruleSet, config);
  }

  public deleteRuleSet(deviceId: string, xid: string, config?: RequestConfig): Observable<any> {
    return this.proxy<any>(deviceId, 'DELETE', `/v2/schedule-rule-sets/${encodeURIComponent(xid)}`,
      null, config);
  }

  // --- System ---------------------------------------------------------------------------------

  public getAbout(deviceId: string, config?: RequestConfig): Observable<GatewayAbout> {
    return this.proxy<GatewayAbout>(deviceId, 'GET', '/v2/about', null, config);
  }

  public getSystemSettings(deviceId: string,
                           config?: RequestConfig): Observable<GatewaySystemSettings> {
    return this.proxy<GatewaySystemSettings>(deviceId, 'GET', '/v2/system-setting', null, config);
  }

  /**
   * These three answer **bare arrays**, not the `{items, total}` page every other list route
   * returns, so nothing here may go through `gatewayPageToPageData`.
   */
  public getMonitorValues(deviceId: string,
                          config?: RequestConfig): Observable<GatewayMonitorValue[]> {
    return this.proxy<GatewayMonitorValue[]>(deviceId, 'GET', '/v2/stack-monitor', null, config);
  }

  public getNetworkInterfaces(deviceId: string,
                              config?: RequestConfig): Observable<GatewayNetworkInterface[]> {
    return this.proxy<GatewayNetworkInterface[]>(deviceId, 'GET', '/v2/server/network-interfaces',
      null, config);
  }

  public getLanguages(deviceId: string, config?: RequestConfig): Observable<GatewayLanguage[]> {
    return this.proxy<GatewayLanguage[]>(deviceId, 'GET', '/v2/server/languages', null, config);
  }
}

/**
 * Only the fields that were actually asked for.
 *
 * An `undefined` left in would be serialised as the string "undefined" and the platform would then
 * try to parse it as a page size.
 */
const httpParams = (query?: GatewayProxyParams): QueryParams => {
  const params: QueryParams = {};
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = String(value);
    }
  });
  return params;
};
