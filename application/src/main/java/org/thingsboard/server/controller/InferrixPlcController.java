/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.InferrixAdoptionService;
import org.thingsboard.server.service.inferrix.InferrixControllerAccess;
import org.thingsboard.server.service.inferrix.InferrixProxyRoutes;
import org.thingsboard.server.service.inferrix.InferrixControllerSighting;
import org.thingsboard.server.service.inferrix.InferrixDiscoveryService;
import org.thingsboard.server.service.inferrix.InferrixUploadService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Discovery and adoption of Inferrix soft-PLC controllers.
 *
 * <p><b>Permissions.</b> The role read gate only covers an explicit allowlist of upstream URL
 * patterns, so a new path like this one is not gated by it — every check here is made explicitly.
 * Both endpoints are tenant-admin only and additionally run the role check for the DEVICE resource,
 * so a role that denies device READ or CREATE also denies discovery and adoption. Adoption creates
 * a device and takes ownership of physical hardware; it is not a customer-user operation.
 */
@RestController
@TbCoreComponent
@RequestMapping("/api/inferrix/controllers")
@RequiredArgsConstructor
public class InferrixPlcController extends BaseController {

    private static final int MAX_QUERY_LENGTH = 256;
    private static final Pattern SAFE_QUERY = Pattern.compile("[A-Za-z0-9_.,\\-=&]+");

    /** Absent unless discovery is switched on, which it is not by default. */
    private final ObjectProvider<InferrixDiscoveryService> discoveryService;
    private final ObjectProvider<InferrixAdoptionService> adoptionService;
    private final ObjectProvider<InferrixControllerAccess> controllerAccess;
    private final ObjectProvider<InferrixUploadService> uploadService;

    @ApiOperation(value = "List discovered controllers (discovered)",
            notes = "Controllers that have announced themselves on the discovery port and have not "
                    + "been adopted yet. This is unauthenticated device-supplied data: it says "
                    + "something is at an address, not what it is. Identity is established during "
                    + "adoption, when the platform connects back over TLS and pins the certificate. "
                    + "A system administrator sees every announce on the network; a tenant "
                    + "administrator sees only the controllers assigned to that tenant. Empty when "
                    + "discovery is disabled. Sightings and their assignments are held in memory and "
                    + "are lost on restart; an unadopted controller re-announces every five minutes, "
                    + "so the list repopulates but assignments have to be made again.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @GetMapping("/discovered")
    public List<DiscoveredController> getDiscoveredControllers() throws ThingsboardException {
        SecurityUser user = getCurrentUser();
        InferrixDiscoveryService discovery = discoveryService.getIfAvailable();
        if (discovery == null) {
            return List.of();
        }
        if (Authority.SYS_ADMIN.equals(user.getAuthority())) {
            return discovery.getAllSightings().stream().map(DiscoveredController::of).toList();
        }
        // A controller nobody has been given belongs to nobody, and the announce carries nothing
        // that could say otherwise. Showing the unfiltered list to every tenant administrator would
        // hand each of them the addresses, deployment names and locations of every other tenant's
        // hardware on the same network.
        accessControlService.checkPermission(user, Resource.DEVICE, Operation.READ);
        return discovery.getSightingsFor(user.getTenantId()).stream()
                .map(DiscoveredController::of).toList();
    }

    @ApiOperation(value = "Assign a discovered controller to a tenant (assign)",
            notes = "Makes a discovered controller visible and adoptable by one tenant. Allocating "
                    + "physical hardware to a customer is a platform-operator decision, so this is "
                    + "system-administrator only and there is no tenant-side equivalent.")
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @PostMapping("/discovered/{uid}/assign")
    public DiscoveredController assignDiscoveredController(@PathVariable("uid") String uid,
                                                           @RequestParam("tenantId") String tenantId)
            throws ThingsboardException {
        getCurrentUser();
        InferrixDiscoveryService discovery = discoveryService.getIfAvailable();
        if (discovery == null) {
            throw new IllegalStateException("Controller discovery is not enabled on this node");
        }
        TenantId target = TenantId.fromUUID(toUUID(tenantId));
        checkTenantId(target, Operation.READ);
        return DiscoveredController.of(discovery.assign(uid, target));
    }

    @ApiOperation(value = "Adopt a controller (adopt)",
            notes = "Claims a controller and registers it as a device. Connects to it over TLS, "
                    + "pins its certificate, sets the ownership password if it has none, logs in, "
                    + "creates the device with MQTT credentials, and points the controller at the "
                    + "broker. A controller someone has already claimed answers 409 and can only be "
                    + "adopted by supplying its existing password.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/adopt")
    public Device adoptController(@RequestBody InferrixAdoptionService.InferrixAdoptRequest request)
            throws ThingsboardException {
        SecurityUser user = getCurrentUser();
        accessControlService.checkPermission(user, Resource.DEVICE, Operation.CREATE);
        InferrixAdoptionService adoption = adoptionService.getIfAvailable();
        if (adoption == null) {
            throw new ThingsboardException("Controller adoption is not available on this node",
                    ThingsboardErrorCode.GENERAL);
        }
        if ((request.uid() == null || request.uid().isBlank())
                && (request.host() == null || request.host().isBlank())) {
            throw new IllegalArgumentException("Either uid or host is required");
        }
        try {
            return adoption.adopt(user.getTenantId(), user, request);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Call an adopted controller's REST API (proxy)",
            notes = "Forwards one request to the controller and returns its response verbatim. The "
                    + "platform supplies the bearer token and pins the device certificate, so the "
                    + "caller never handles either. Only device-configuration routes are forwarded: "
                    + "the auth routes are excluded because calling them would revoke the token the "
                    + "platform holds, and the binary firmware and logic upload routes need their "
                    + "own chunking flow rather than a JSON proxy.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/{deviceId}/proxy/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<String> proxyToController(@PathVariable("deviceId") String strDeviceId,
                                                    @RequestBody(required = false) String body,
                                                    HttpServletRequest request) throws ThingsboardException {
        checkParameter("deviceId", strDeviceId);
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
        // Reads and writes are separated: pulling a controller's health is a READ, while changing
        // its network settings or applying a config is not something a read-only role may do.
        boolean readOnly = HttpMethod.GET.matches(request.getMethod());
        if (!readOnly && !Authority.TENANT_ADMIN.equals(getCurrentUser().getAuthority())) {
            // Reading a controller's state is ordinary device access. Changing its network settings,
            // applying a config or retuning a loop reconfigures building plant, so it stays with the
            // tenant administrator even where a customer user has write access to the device.
            throw new ThingsboardException("Only a tenant administrator may change a controller's configuration",
                    ThingsboardErrorCode.PERMISSION_DENIED);
        }
        Device device = checkDeviceId(deviceId, readOnly ? Operation.READ : Operation.WRITE);

        String path = devicePath(request, strDeviceId);
        if (!InferrixProxyRoutes.isAllowed(request.getMethod(), path)) {
            throw new ThingsboardException("This controller route cannot be called through the platform: "
                    + request.getMethod() + " " + path, ThingsboardErrorCode.PERMISSION_DENIED);
        }
        String target = path + queryStringSuffix(request);
        InferrixControllerAccess access = controllerAccess.getIfAvailable();
        if (access == null) {
            throw new ThingsboardException("Controller access is not available on this node",
                    ThingsboardErrorCode.GENERAL);
        }
        try {
            HttpResponse<String> response = access.call(device.getTenantId(), deviceId,
                    request.getMethod(), target, body);
            // The device's status code is meaningful to the caller (409 nothing pending, 503 hot-swap
            // draining, 429 probe busy), so it is passed through rather than flattened.
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Upload firmware or a logic program (upload)",
            notes = "Hands the platform an artifact and starts writing it to the controller. The "
                    + "platform cuts it into pieces that fit the device's 2048-byte request cap, "
                    + "streams them, and finishes with an apply that verifies the whole image by "
                    + "SHA-256 — so a corrupted transfer never activates. This is not proxied like "
                    + "the other controller routes: an image is several hundred chunks and each one "
                    + "is its own TLS handshake against a microcontroller, which takes minutes. The "
                    + "call returns a job to poll rather than waiting. Activation is a reboot for "
                    + "both kinds; the controller decides when.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/{deviceId}/upload/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadStatus startUpload(@PathVariable("deviceId") String strDeviceId,
                                    @PathVariable("kind") String strKind,
                                    @RequestPart("file") MultipartFile file) throws ThingsboardException {
        checkParameter("deviceId", strDeviceId);
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
        // Writing firmware to building plant is the most consequential thing this API does, so it
        // needs device WRITE, not the READ the status endpoints take.
        Device device = checkDeviceId(deviceId, Operation.WRITE);
        InferrixUploadService uploads = uploadService.getIfAvailable();
        if (uploads == null) {
            throw new ThingsboardException("Controller uploads are not available on this node",
                    ThingsboardErrorCode.GENERAL);
        }
        InferrixUploadService.Kind kind = parseKind(strKind);
        try {
            return UploadStatus.of(uploads.start(device.getTenantId(), deviceId, kind, file.getBytes()));
        } catch (IOException e) {
            throw new ThingsboardException("The uploaded file could not be read",
                    ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Wrong size, empty file, or a second upload to a device already being written: all of
            // them are the operator's to fix, so they answer 400 rather than a server error.
            throw new ThingsboardException(e.getMessage(), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
    }

    @ApiOperation(value = "The upload running against a controller (activeUpload)",
            notes = "The upload currently writing to this controller, or nothing. Lets a reloaded "
                    + "page find its way back to a job in flight, since the job id otherwise lived "
                    + "only in the browser.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/{deviceId}/upload")
    public UploadStatus getActiveUpload(@PathVariable("deviceId") String strDeviceId)
            throws ThingsboardException {
        checkParameter("deviceId", strDeviceId);
        DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
        Device device = checkDeviceId(deviceId, Operation.READ);
        InferrixUploadService uploads = uploadService.getIfAvailable();
        InferrixUploadService.UploadJob job = uploads == null ? null
                : uploads.getActiveJob(deviceId, device.getTenantId());
        return job == null ? null : UploadStatus.of(job);
    }

    @ApiOperation(value = "Poll an upload (uploadStatus)",
            notes = "Progress of an upload started on this node. Jobs are held in memory for thirty "
                    + "minutes and are not shared between nodes, so poll the node that accepted the "
                    + "upload. A job is only visible to the tenant that owns the controller.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/uploads/{jobId}")
    public UploadStatus getUpload(@PathVariable("jobId") String jobId) throws ThingsboardException {
        SecurityUser user = getCurrentUser();
        InferrixUploadService uploads = uploadService.getIfAvailable();
        InferrixUploadService.UploadJob job = uploads == null ? null
                : uploads.getJob(jobId, user.getTenantId());
        if (job == null) {
            // Same answer for "no such job" and "not yours": a job id must not confirm that an
            // upload to another tenant's controller exists.
            throw new ThingsboardException("No such upload", ThingsboardErrorCode.ITEM_NOT_FOUND);
        }
        return UploadStatus.of(job);
    }

    private InferrixUploadService.Kind parseKind(String kind) throws ThingsboardException {
        try {
            return InferrixUploadService.Kind.valueOf(kind.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ThingsboardException("Unknown upload kind: " + kind,
                    ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
    }

    /**
     * Recovers the controller path from the request. Taken from the servlet's own decoded path
     * rather than reassembled from anything the caller supplies, and the result still has to match
     * the route allowlist, so a traversal attempt cannot reach a route that is not on it.
     */
    private String devicePath(HttpServletRequest request, String strDeviceId) {
        String prefix = "/api/inferrix/controllers/" + strDeviceId + "/proxy";
        String uri = request.getRequestURI();
        int at = uri.indexOf(prefix);
        return at < 0 ? "" : uri.substring(at + prefix.length());
    }

    /**
     * Carries the query string through, which several controller routes need to be usable at all —
     * {@code /points} pages with {@code offset}, {@code /config} selects with {@code section}.
     *
     * <p>The allowlist is matched against the path alone, so the query is restricted here to the
     * characters those parameters actually use. That keeps a second path, a fragment or an encoded
     * separator from riding along in it and reaching the device as something other than a query.
     */
    private String queryStringSuffix(HttpServletRequest request) throws ThingsboardException {
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) {
            return "";
        }
        if (query.length() > MAX_QUERY_LENGTH || !SAFE_QUERY.matcher(query).matches()) {
            throw new ThingsboardException("Unsupported query string for a controller request",
                    ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        return "?" + query;
    }

    /** Progress of one firmware or logic upload. */
    public record UploadStatus(String jobId, String kind, String state, int sent, int total,
                               String message, String activation) {

        static UploadStatus of(InferrixUploadService.UploadJob job) {
            return new UploadStatus(job.getId(), job.getKind().name(), job.getState().name(),
                    job.getSent(), job.getTotalBytes(), job.getMessage(), job.getActivation());
        }
    }

    /**
     * What the operator is shown before adopting. The announce body is passed through as-is under
     * {@code identity} so the UI can display whatever the firmware chose to send, while {@code ip}
     * is the TCP peer address the platform will actually dial — never the body's own claim.
     */
    public record DiscoveredController(String uid, String ip, JsonNode identity,
                                       long firstSeenTs, long lastSeenTs, int announceCount,
                                       String assignedTenantId) {

        static DiscoveredController of(InferrixControllerSighting sighting) {
            TenantId assigned = sighting.getAssignedTenantId();
            return new DiscoveredController(sighting.getUid(), sighting.getConnectBackIp(),
                    sighting.getIdentity(), sighting.getFirstSeenTs(), sighting.getLastSeenTs(),
                    sighting.getAnnounceCount(), assigned == null ? null : assigned.getId().toString());
        }
    }

}
