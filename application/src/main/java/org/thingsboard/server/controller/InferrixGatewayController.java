// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAccess;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAdoption.AdoptRequest;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAdoptionService;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAdoptionService.PendingGateway;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayReachability;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewaySchemaService;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayRoutes;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayRql;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.List;

/**
 * Configuration of adopted Inferrix gateways, proxied from the platform.
 *
 * <p><b>Permissions.</b> The role read gate only covers an explicit allowlist of upstream URL
 * patterns, so a new path like this one is not covered by it — every check here is made explicitly.
 * Reads are ordinary device access; anything that changes a gateway's configuration is tenant-admin
 * only, even where a customer user holds device write, because a gateway's data sources and event
 * handlers drive building plant.
 *
 * <p><b>What this deliberately does not do.</b> It does not forward the caller's query string. The
 * gateway parses a raw query string as RQL — on every verb, not just GET — so forwarding one would
 * hand an operator-typed expression to the device's query parser. Paging, sorting and filtering
 * arrive instead as typed parameters that {@link InferrixGatewayRql} turns into RQL here (spec
 * §2.4); anything else on the query string is simply not read, and a path carrying {@code ?} is
 * refused by {@link InferrixGatewayRoutes} rather than silently truncated.
 */
@RestController
@TbCoreComponent
@RequestMapping("/api/inferrix/gateways")
@RequiredArgsConstructor
public class InferrixGatewayController extends BaseController {

    private static final String PROXY_SEGMENT = "/proxy";

    /**
     * What a free-text search searches.
     *
     * <p>Fixed rather than supplied by the caller: every list this feature pages — data sources,
     * data points, publishers, schedules — carries a {@code name}, and letting the browser choose
     * the search column would put a second operator-typed field name into the RQL for no gain.
     */
    private static final String SEARCH_FIELD = "name";

    private final ObjectProvider<InferrixGatewayAccess> gatewayAccess;
    private final ObjectProvider<InferrixGatewayAdoptionService> adoptionService;
    private final ObjectProvider<InferrixGatewaySchemaService> schemaService;

    @ApiOperation(value = "List gateways waiting to be adopted",
            notes = "Devices on the Inferrix Gateway profile that have provisioned themselves over "
                    + "MQTT but that the platform has not adopted -- meaning no management address "
                    + "has been sealed onto them. Each row carries the address the gateway reports "
                    + "for itself, where it has reported one, so the adopt form can be prefilled. "
                    + "Tenant-admin only: adopting is, and a list of what is adoptable is the same "
                    + "surface.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/pending")
    public List<PendingGateway> pending() throws ThingsboardException {
        try {
            return adoption().pending(getTenantId());
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Probe whether a gateway can be reached",
            notes = "Asks the gateway for its identity and reports whether the platform reached "
                    + "it, and if not, why. The reason is the point: a rejected token, an "
                    + "under-privileged one, a changed certificate and a gateway that was never "
                    + "adopted are four different problems in four different places, and none of "
                    + "them is 'the network is down'. Never fails -- every failure becomes a "
                    + "reason, since an error here would make the one question it is asked "
                    + "unanswerable.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping("/{deviceId}/reachability")
    public InferrixGatewayReachability reachability(@PathVariable("deviceId") String strDeviceId)
            throws ThingsboardException {
        checkParameter("deviceId", strDeviceId);
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
        InferrixPublicLink.requireNotPublicLink(getCurrentUser());
        Device device = checkDeviceId(deviceId, Operation.READ);
        requireGatewayProfile(device, strDeviceId);
        return access().probe(device.getTenantId(), deviceId);
    }

    @ApiOperation(value = "The gateway's model schemas",
            notes = "The gateway's own JSON Schema for every model type it supports, which is what "
                    + "lets the platform render a form per data source, publisher, event detector "
                    + "and event handler type instead of hand-coding one component per protocol "
                    + "module. The WHOLE document is returned, not a slice: nested types are $refs "
                    + "into its own components.schemas, so a slice would hold dangling references. "
                    + "Cached per device for 30 minutes -- it only changes when the gateway's "
                    + "build does.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping("/{deviceId}/schemas")
    public JsonNode schemas(@PathVariable("deviceId") String strDeviceId) throws ThingsboardException {
        checkParameter("deviceId", strDeviceId);
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
        InferrixPublicLink.requireNotPublicLink(getCurrentUser());
        Device device = checkDeviceId(deviceId, Operation.READ);
        requireGatewayProfile(device, strDeviceId);
        try {
            return schemas().schemas(device.getTenantId(), deviceId);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Adopt an Inferrix gateway",
            notes = "Brings a gateway under platform management: captures and vets its certificate, "
                    + "proves the API token against it, records whether that token reaches the "
                    + "platform-link domain, then creates the device and seals the credential onto "
                    + "it. Both halves of the credential are sealed before storage. Note that a "
                    + "server-scope attribute is not private: the platform's generic attribute "
                    + "read serves gwManagementAddress, gwManagementPort and gwCertFingerprint to "
                    + "any caller holding READ_ATTRIBUTES on the device. Only the credential is "
                    + "protected, and it is protected by the seal, not by the scope.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/adopt")
    public Device adopt(@RequestBody AdoptRequest request) throws ThingsboardException {
        try {
            return adoption().adopt(getTenantId(), getCurrentUser(), request);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Call an adopted gateway's REST API (proxy)",
            notes = "Forwards one request to the gateway and returns its response verbatim. The "
                    + "platform holds the API token, exchanges it for a short-lived JWT and pins the "
                    + "device certificate, so the caller never handles any of them. Only "
                    + "configuration routes are forwarded: the auth and api-token routes are "
                    + "excluded because calling either would let a browser spend or revoke the "
                    + "platform's own credential, and the script, file-store, user and certificate "
                    + "routes are excluded because they reach past configuration into the host.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/{deviceId}/proxy/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<String> proxyToGateway(@PathVariable("deviceId") String strDeviceId,
                                                 @RequestParam(required = false) Integer pageSize,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) String textSearch,
                                                 @RequestParam(required = false) String sortProperty,
                                                 @RequestParam(required = false) String sortOrder,
                                                 @RequestParam(required = false) String filterField,
                                                 @RequestParam(required = false) String filterValue,
                                                 @RequestParam(required = false) Boolean enabled,
                                                 @RequestParam(required = false) Boolean restart,
                                                 @RequestBody(required = false) String body,
                                                 HttpServletRequest request) throws ThingsboardException {
        checkParameter("deviceId", strDeviceId);
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));

        InferrixPublicLink.requireNotPublicLink(getCurrentUser());

        String path = devicePath(request.getRequestURI(), strDeviceId);
        // Not "is this a GET". Some allowlisted GETs write on the gateway and others are
        // administrator reads there; the verb tells you neither. See InferrixGatewayRoutes.
        boolean readOnly = !InferrixGatewayRoutes.requiresTenantAdmin(request.getMethod(), path);
        if (!readOnly && !Authority.TENANT_ADMIN.equals(getCurrentUser().getAuthority())) {
            throw new ThingsboardException(
                    "Only a tenant administrator may change a gateway's configuration",
                    ThingsboardErrorCode.PERMISSION_DENIED);
        }
        Device device = checkDeviceId(deviceId, readOnly ? Operation.READ : Operation.WRITE);
        requireGatewayProfile(device, strDeviceId);

        // Checked here and again inside InferrixGatewayAccess. Not redundant: this one produces a
        // clean 403 for the caller, and that one guarantees no future entry point can reach a
        // device without passing the list.
        if (!InferrixGatewayRoutes.isAllowed(request.getMethod(), path)) {
            throw new ThingsboardException("This gateway route cannot be called through the platform: "
                    + request.getMethod() + " " + path, ThingsboardErrorCode.PERMISSION_DENIED);
        }

        String query = rql(pageSize, page, textSearch, sortProperty, sortOrder,
                filterField, filterValue, enabled, restart);
        try {
            GatewayResponse response = access().call(device.getTenantId(), deviceId,
                    request.getMethod(), path, query, body);
            // The gateway's status code carries meaning the caller needs, and 403 especially so:
            // with a non-admin service account it is the ordinary answer on the platform-link
            // routes (spec §2.9), and the UI degrades that tab rather than treating it as broken.
            // Flattening these into a generic error would make an under-privileged token
            // indistinguishable from a wrong one.
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    /**
     * Turns the platform's own paging vocabulary into the gateway's.
     *
     * <p>Deliberately the same parameter names TB's {@code PageLink} already uses, so the browser
     * sends what it sends for every other list and nothing here is bespoke. The translation is the
     * whole point: what arrives is typed, what leaves is an RQL expression this platform wrote.
     *
     * @return the query string, or {@code null} when the caller asked for no paging at all — in
     *         which case the gateway applies its own {@code limit(100)} default
     */
    private static String rql(Integer pageSize, Integer page, String textSearch,
                              String sortProperty, String sortOrder,
                              String filterField, String filterValue,
                              Boolean enabled, Boolean restart) throws ThingsboardException {
        try {
            InferrixGatewayRql rql = InferrixGatewayRql.query();
            // Not paging: the enable-disable routes declare ordinary request parameters and parse
            // no RQL at all, so what they need cannot be expressed as a filter term.
            if (enabled != null) {
                rql.flag("enabled", enabled);
            }
            if (restart != null) {
                rql.flag("restart", restart);
            }
            if (isNotBlank(filterField) && filterValue != null) {
                rql.eq(filterField, filterValue);
            }
            if (isNotBlank(textSearch)) {
                rql.match(SEARCH_FIELD, textSearch);
            }
            if (isNotBlank(sortProperty)) {
                rql.sort(sortProperty, "DESC".equalsIgnoreCase(sortOrder));
            }
            if (pageSize != null) {
                if (page != null && page < 0) {
                    throw new IllegalArgumentException("A page number cannot be negative");
                }
                // multiplyExact, not '*': a page number the browser is free to choose times a page
                // size would otherwise overflow into a small positive offset and quietly serve the
                // wrong rows.
                rql.page(pageSize, page == null ? 0 : Math.multiplyExact(page, pageSize));
            }
            return rql.build();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ThingsboardException(e.getMessage(), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Refuses any device adoption did not put on the gateway profile.
     *
     * <p>Without this, every device-bound endpoint here is a general "dial the address stored in
     * this device's attributes" primitive: a tenant admin can write {@code gwManagementAddress} and
     * a credential onto any device through the ordinary attributes API, and the SSRF guard
     * deliberately permits RFC1918 because gateways live on private LANs — so the platform's
     * network position, inside the customer's network, becomes reachable to someone who only had
     * tenant administration. Adoption sets this type; nothing else does.
     */
    private static void requireGatewayProfile(Device device, String strDeviceId)
            throws ThingsboardException {
        if (!InferrixGatewayAdoptionService.PROFILE_NAME.equals(device.getType())) {
            throw new ThingsboardException("Device " + strDeviceId + " is not an Inferrix gateway",
                    ThingsboardErrorCode.PERMISSION_DENIED);
        }
    }

    private InferrixGatewayAccess access() throws ThingsboardException {
        InferrixGatewayAccess access = gatewayAccess.getIfAvailable();
        if (access == null) {
            throw new ThingsboardException("Gateway access is not available on this node",
                    ThingsboardErrorCode.GENERAL);
        }
        return access;
    }

    private InferrixGatewaySchemaService schemas() throws ThingsboardException {
        InferrixGatewaySchemaService service = schemaService.getIfAvailable();
        if (service == null) {
            throw new ThingsboardException("Gateway schemas are not available on this node",
                    ThingsboardErrorCode.GENERAL);
        }
        return service;
    }

    private InferrixGatewayAdoptionService adoption() throws ThingsboardException {
        InferrixGatewayAdoptionService service = adoptionService.getIfAvailable();
        if (service == null) {
            throw new ThingsboardException("Gateway adoption is not available on this node",
                    ThingsboardErrorCode.GENERAL);
        }
        return service;
    }

    /**
     * Carves the device-bound path back out of the request URI.
     *
     * <p>Package-visible and taking a plain string rather than the request, so the carving can be
     * tested without a servlet container — it decides what the allowlist is handed, which makes it
     * the one part of this controller where a mistake is a security bug rather than a bug.
     *
     * <p>Returns an empty string when the URI is not a proxy call, which no route matches, so an
     * unexpected shape is refused rather than guessed at.
     *
     * <p><b>It must be fed {@code getRequestURI()} and nothing else.</b> That is specified — and
     * confirmed in Tomcat 10.1's {@code CoyoteAdapter} — to be the original, non-decoded,
     * non-normalised URI, which is what lets {@link InferrixGatewayRoutes#isAllowed} refuse a path
     * containing {@code %}. {@code getServletPath()} is the decoded, normalised, path-parameter-
     * stripped form, and {@code UriUtils.decode} the same: switching to either as an "obvious
     * cleanup" would hand the allowlist a string with the encoding already resolved, defeating that
     * refusal silently and with no test failing. ({@code ServletUriComponentsBuilder.fromRequest}
     * builds from {@code getRequestURI}, so it is equivalent, not safer.)
     *
     * <p>Note the deliberate asymmetry with routing: {@code thingsboard.yml} sets
     * {@code spring.mvc.pathmatch.matching-strategy: ANT_PATH_MATCHER}, so Spring matches
     * {@code /proxy/**} against the <em>decoded</em> lookup path while this reads the raw one. The
     * divergence is what makes a crafted URI fail closed rather than route one way and forward
     * another.
     */
    static String devicePath(String requestUri, String strDeviceId) {
        if (requestUri == null) {
            return "";
        }
        String prefix = "/api/inferrix/gateways/" + strDeviceId + PROXY_SEGMENT;
        int at = requestUri.indexOf(prefix);
        return at < 0 ? "" : requestUri.substring(at + prefix.length());
    }
}
