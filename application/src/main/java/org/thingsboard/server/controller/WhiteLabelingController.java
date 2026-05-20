/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.wl.LoginWhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabelingType;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.UUID;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@Tag(name = "White Labeling", description = "White Labeling configuration endpoints")
@Slf4j
public class WhiteLabelingController extends BaseController {

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/whiteLabel/whiteLabelParams", produces = "application/json")
    public WhiteLabelingParams getWhiteLabelParams() throws ThingsboardException {
        SecurityUser user = getCurrentUser();
        WhiteLabelingParams params;
        switch (user.getAuthority()) {
            case SYS_ADMIN:
                params = whiteLabelingService.getSystemWhiteLabelingParams();
                if (params == null) params = WhiteLabelingParams.defaultParams();
                break;
            case TENANT_ADMIN:
                params = whiteLabelingService.getMergedTenantWhiteLabelingParams(user.getTenantId());
                break;
            case CUSTOMER_USER:
                params = whiteLabelingService.getMergedCustomerWhiteLabelingParams(
                        user.getTenantId(), user.getCustomerId());
                break;
            default:
                params = WhiteLabelingParams.defaultParams();
        }
        params.setWhiteLabelingEnabled(true);
        return params;
    }

    @GetMapping(value = "/noauth/whiteLabel/loginWhiteLabelParams", produces = "application/json")
    public LoginWhiteLabelingParams getLoginWhiteLabelParams(HttpServletRequest request) {
        LoginWhiteLabelingParams params = whiteLabelingService.getMergedLoginWhiteLabelingParams(request.getServerName());
        if (params == null) {
            params = LoginWhiteLabelingParams.defaultLoginParams();
        }
        params.setWhiteLabelingEnabled(true);
        return params;
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/whiteLabel/currentWhiteLabelParams", produces = "application/json")
    public WhiteLabelingParams getCurrentWhiteLabelParams(
            @RequestParam(value = "customerId", required = false) String strCustomerId
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.READ);
        SecurityUser user = getCurrentUser();
        switch (user.getAuthority()) {
            case SYS_ADMIN:
                return whiteLabelingService.getSystemWhiteLabelingParams();
            case TENANT_ADMIN:
                if (strCustomerId != null && !strCustomerId.isEmpty()) {
                    CustomerId customerId = new CustomerId(UUID.fromString(strCustomerId));
                    return whiteLabelingService.getCustomerWhiteLabelingParams(user.getTenantId(), customerId);
                }
                return whiteLabelingService.getTenantWhiteLabelingParams(user.getTenantId());
            case CUSTOMER_USER:
                return whiteLabelingService.getCustomerWhiteLabelingParams(
                        user.getTenantId(), user.getCustomerId());
            default:
                return null;
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/whiteLabel/currentLoginWhiteLabelParams", produces = "application/json")
    public LoginWhiteLabelingParams getCurrentLoginWhiteLabelParams(
            @RequestParam(value = "customerId", required = false) String strCustomerId
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.READ);
        SecurityUser user = getCurrentUser();
        switch (user.getAuthority()) {
            case SYS_ADMIN:
                return whiteLabelingService.getSystemLoginWhiteLabelingParams();
            case TENANT_ADMIN:
                if (strCustomerId != null && !strCustomerId.isEmpty()) {
                    CustomerId customerId = new CustomerId(UUID.fromString(strCustomerId));
                    return whiteLabelingService.getCustomerLoginWhiteLabelingParams(user.getTenantId(), customerId);
                }
                return whiteLabelingService.getTenantLoginWhiteLabelingParams(user.getTenantId());
            case CUSTOMER_USER:
                return whiteLabelingService.getCustomerLoginWhiteLabelingParams(
                        user.getTenantId(), user.getCustomerId());
            default:
                return null;
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/whiteLabel/whiteLabelParams")
    @ResponseStatus(HttpStatus.OK)
    public WhiteLabelingParams saveWhiteLabelParams(
            @RequestBody WhiteLabelingParams params,
            @RequestParam(value = "customerId", required = false) String strCustomerId
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        switch (user.getAuthority()) {
            case SYS_ADMIN:
                return whiteLabelingService.saveSystemWhiteLabelingParams(params);
            case TENANT_ADMIN:
                if (strCustomerId != null && !strCustomerId.isEmpty()) {
                    CustomerId customerId = new CustomerId(UUID.fromString(strCustomerId));
                    return whiteLabelingService.saveCustomerWhiteLabelingParams(user.getTenantId(), customerId, params);
                }
                return whiteLabelingService.saveTenantWhiteLabelingParams(user.getTenantId(), params);
            case CUSTOMER_USER:
                return whiteLabelingService.saveCustomerWhiteLabelingParams(
                        user.getTenantId(), user.getCustomerId(), params);
            default:
                throw new ThingsboardException("Unsupported authority", org.thingsboard.server.common.data.exception.ThingsboardErrorCode.AUTHENTICATION);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/whiteLabel/loginWhiteLabelParams")
    @ResponseStatus(HttpStatus.OK)
    public LoginWhiteLabelingParams saveLoginWhiteLabelParams(
            @RequestBody LoginWhiteLabelingParams params,
            @RequestParam(value = "customerId", required = false) String strCustomerId
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        switch (user.getAuthority()) {
            case SYS_ADMIN:
                return whiteLabelingService.saveSystemLoginWhiteLabelingParams(params);
            case TENANT_ADMIN:
                if (strCustomerId != null && !strCustomerId.isEmpty()) {
                    CustomerId customerId = new CustomerId(UUID.fromString(strCustomerId));
                    return whiteLabelingService.saveCustomerLoginWhiteLabelingParams(user.getTenantId(), customerId, params);
                }
                return whiteLabelingService.saveTenantLoginWhiteLabelingParams(user.getTenantId(), params);
            case CUSTOMER_USER:
                return whiteLabelingService.saveCustomerLoginWhiteLabelingParams(
                        user.getTenantId(), user.getCustomerId(), params);
            default:
                throw new ThingsboardException("Unsupported authority", org.thingsboard.server.common.data.exception.ThingsboardErrorCode.AUTHENTICATION);
        }
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/whiteLabel/previewWhiteLabelParams")
    @ResponseStatus(HttpStatus.OK)
    public WhiteLabelingParams previewWhiteLabelParams(
            @RequestBody WhiteLabelingParams params
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        switch (user.getAuthority()) {
            case SYS_ADMIN:
                return whiteLabelingService.mergeSystemWhiteLabelingParams(params);
            case TENANT_ADMIN:
                return whiteLabelingService.mergeTenantWhiteLabelingParams(user.getTenantId(), params);
            case CUSTOMER_USER:
                return whiteLabelingService.mergeCustomerWhiteLabelingParams(
                        user.getTenantId(), user.getCustomerId(), params);
            default:
                return params;
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/whiteLabel/isWhiteLabelingAllowed")
    public Boolean isWhiteLabelingAllowed() {
        return true;
    }

    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/whiteLabel/isCustomerWhiteLabelingAllowed")
    public Boolean isCustomerWhiteLabelingAllowed() {
        return true;
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @DeleteMapping(value = "/whiteLabel/currentWhiteLabelParams")
    public void deleteCurrentWhiteLabelParams(
            @RequestParam(value = "customerId", required = false) String strCustomerId
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        CustomerId customerId = resolveCustomerId(user, strCustomerId);
        whiteLabelingService.deleteWhiteLabeling(user.getTenantId(), customerId, WhiteLabelingType.GENERAL);
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @DeleteMapping(value = "/whiteLabel/currentLoginWhiteLabelParams")
    public void deleteCurrentLoginWhiteLabelParams(
            @RequestParam(value = "customerId", required = false) String strCustomerId
    ) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        CustomerId customerId = resolveCustomerId(user, strCustomerId);
        whiteLabelingService.deleteWhiteLabeling(user.getTenantId(), customerId, WhiteLabelingType.LOGIN);
    }

    // --- Privacy Policy ---

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @GetMapping(value = "/whiteLabel/privacyPolicy", produces = "application/json")
    public JsonNode getPrivacyPolicy() throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.READ);
        SecurityUser user = getCurrentUser();
        return whiteLabelingService.getTenantPrivacyPolicy(user.getTenantId());
    }

    @GetMapping(value = "/noauth/whiteLabel/privacyPolicy", produces = "application/json")
    public JsonNode getWebPrivacyPolicy(HttpServletRequest request) {
        return whiteLabelingService.getWebPrivacyPolicy(request.getServerName());
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @PostMapping(value = "/whiteLabel/privacyPolicy")
    @ResponseStatus(HttpStatus.OK)
    public JsonNode savePrivacyPolicy(@RequestBody JsonNode policy) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        return whiteLabelingService.savePrivacyPolicy(user.getTenantId(), policy);
    }

    // --- Terms of Use ---

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @GetMapping(value = "/whiteLabel/termsOfUse", produces = "application/json")
    public JsonNode getTermsOfUse() throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.READ);
        SecurityUser user = getCurrentUser();
        return whiteLabelingService.getTenantTermsOfUse(user.getTenantId());
    }

    @GetMapping(value = "/noauth/whiteLabel/termsOfUse", produces = "application/json")
    public JsonNode getWebTermsOfUse(HttpServletRequest request) {
        return whiteLabelingService.getWebTermsOfUse(request.getServerName());
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @PostMapping(value = "/whiteLabel/termsOfUse")
    @ResponseStatus(HttpStatus.OK)
    public JsonNode saveTermsOfUse(@RequestBody JsonNode terms) throws ThingsboardException {
        checkWhiteLabelingPermissions(Operation.WRITE);
        SecurityUser user = getCurrentUser();
        return whiteLabelingService.saveTermsOfUse(user.getTenantId(), terms);
    }

    // --- Private helpers ---

    private void checkWhiteLabelingPermissions(Operation operation) throws ThingsboardException {
        accessControlService.checkPermission(getCurrentUser(), Resource.WHITE_LABELING, operation);
    }

    private CustomerId resolveCustomerId(SecurityUser user, String strCustomerId) {
        if (user.getAuthority() == Authority.CUSTOMER_USER) {
            return user.getCustomerId();
        }
        if (strCustomerId != null && !strCustomerId.isEmpty()) {
            return new CustomerId(UUID.fromString(strCustomerId));
        }
        return new CustomerId(org.thingsboard.server.common.data.id.EntityId.NULL_UUID);
    }
}
