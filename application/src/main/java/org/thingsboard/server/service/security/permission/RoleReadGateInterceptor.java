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
package org.thingsboard.server.service.security.permission;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.exception.ThingsboardErrorResponseHandler;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Map.entry;

/**
 * RBAC phase 5: role gate for the tenant- and customer-wide READ endpoints.
 * <p>
 * Single-entity reads funnel through {@code BaseController.checkEntityId} and therefore through
 * {@link DefaultAccessControlService}, which already AND-s the role gate in. The collection
 * endpoints do not: they are {@code @PreAuthorize}-authority-only and call the DAO directly, so
 * without this interceptor a role denying DEVICE:READ still lists every device in the tenant.
 * <p>
 * Only the ROLE half of the check is applied (never the authority {@code PermissionChecker}
 * half): {@link UserPermissionsUtil#granted} is a no-op for SYS_ADMIN and for role-less users, so
 * this cannot change behaviour on an install that has no roles. Applying the checker half here
 * would deny paths the authority baseline has always allowed — several checkers implement only
 * the entity-scoped {@code hasPermission} overload and return false for the generic one.
 * <p>
 * Keyed on Spring's best-matching pattern rather than on controller classes, because a controller
 * mixes tenant-wide reads with self-service endpoints (a user's own settings, dashboards, server
 * time) that must stay reachable for a restricted user. A pattern that no longer resolves is
 * reported at startup by {@link #verifyPatterns} — otherwise upstream renaming a URL would
 * silently drop the gate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleReadGateInterceptor implements HandlerInterceptor {

    /**
     * GET patterns that reach entity data without any access-control call, and the resource whose
     * READ permission they require. Keys must match the declared mapping exactly, including the
     * class-level {@code /api} prefix and the path-variable names.
     */
    static final Map<String, Resource> GATED_READS = Map.ofEntries(
            entry("/api/tenant/devices", Resource.DEVICE),
            entry("/api/tenant/deviceInfos", Resource.DEVICE),
            entry("/api/tenant/device", Resource.DEVICE),
            entry("/api/customer/{customerId}/devices", Resource.DEVICE),
            entry("/api/customer/{customerId}/deviceInfos", Resource.DEVICE),
            entry("/api/devices", Resource.DEVICE),
            entry("/api/edge/{edgeId}/devices", Resource.DEVICE),

            entry("/api/tenant/assets", Resource.ASSET),
            entry("/api/tenant/assetInfos", Resource.ASSET),
            entry("/api/tenant/asset", Resource.ASSET),
            entry("/api/customer/{customerId}/assets", Resource.ASSET),
            entry("/api/customer/{customerId}/assetInfos", Resource.ASSET),
            entry("/api/assets", Resource.ASSET),
            entry("/api/edge/{edgeId}/assets", Resource.ASSET),

            entry("/api/tenant/entityViews", Resource.ENTITY_VIEW),
            entry("/api/tenant/entityViewInfos", Resource.ENTITY_VIEW),
            entry("/api/tenant/entityView", Resource.ENTITY_VIEW),
            entry("/api/customer/{customerId}/entityViews", Resource.ENTITY_VIEW),
            entry("/api/customer/{customerId}/entityViewInfos", Resource.ENTITY_VIEW),
            entry("/api/entityViews", Resource.ENTITY_VIEW),
            entry("/api/entityViews/list", Resource.ENTITY_VIEW),
            entry("/api/edge/{edgeId}/entityViews", Resource.ENTITY_VIEW),

            entry("/api/tenant/edges", Resource.EDGE),
            entry("/api/tenant/edgeInfos", Resource.EDGE),
            entry("/api/tenant/edge", Resource.EDGE),
            entry("/api/customer/{customerId}/edges", Resource.EDGE),
            entry("/api/customer/{customerId}/edgeInfos", Resource.EDGE),
            entry("/api/edges", Resource.EDGE),
            entry("/api/edges/list", Resource.EDGE),

            entry("/api/tenant/dashboards", Resource.DASHBOARD),
            entry("/api/tenant/{tenantId}/dashboards", Resource.DASHBOARD),
            entry("/api/customer/{customerId}/dashboards", Resource.DASHBOARD),
            entry("/api/dashboards", Resource.DASHBOARD),
            entry("/api/dashboards/list", Resource.DASHBOARD),
            entry("/api/edge/{edgeId}/dashboards", Resource.DASHBOARD),

            entry("/api/customers", Resource.CUSTOMER),
            entry("/api/customers/list", Resource.CUSTOMER),
            entry("/api/tenant/customers", Resource.CUSTOMER),

            entry("/api/tenant/{tenantId}/users", Resource.USER),
            entry("/api/customer/{customerId}/users", Resource.USER),

            entry("/api/alarms", Resource.ALARM),
            entry("/api/v2/alarms", Resource.ALARM),
            entry("/api/alarm/{entityType}/{entityId}", Resource.ALARM),
            entry("/api/v2/alarm/{entityType}/{entityId}", Resource.ALARM),
            entry("/api/alarm/types", Resource.ALARM),
            entry("/api/alarm/highestSeverity/{entityType}/{entityId}", Resource.ALARM),

            entry("/api/audit/logs", Resource.AUDIT_LOG),
            entry("/api/audit/logs/customer/{customerId}", Resource.AUDIT_LOG),
            entry("/api/audit/logs/user/{userId}", Resource.AUDIT_LOG),
            entry("/api/audit/logs/entity/{entityType}/{entityId}", Resource.AUDIT_LOG),

            // entity type/name vocabularies: tenant-wide, and they enumerate what exists
            entry("/api/device/types", Resource.DEVICE),
            entry("/api/asset/types", Resource.ASSET),
            entry("/api/entityView/types", Resource.ENTITY_VIEW),
            entry("/api/edge/types", Resource.EDGE),
            entry("/api/devices/count/{otaPackageType}/{deviceProfileId}", Resource.DEVICE),
            // edgeId is never permission-checked in this one
            entry("/api/edge/missingToRelatedRuleChains/{edgeId}", Resource.EDGE),

            entry("/api/deviceProfiles", Resource.DEVICE_PROFILE),
            entry("/api/deviceProfileInfos", Resource.DEVICE_PROFILE),
            entry("/api/deviceProfileInfos/list", Resource.DEVICE_PROFILE),
            entry("/api/deviceProfile/names", Resource.DEVICE_PROFILE),
            entry("/api/deviceProfileInfo/default", Resource.DEVICE_PROFILE),
            // tenant-wide telemetry / attribute key names across every device of the tenant
            entry("/api/deviceProfile/devices/keys/timeseries", Resource.DEVICE_PROFILE),
            entry("/api/deviceProfile/devices/keys/attributes", Resource.DEVICE_PROFILE),

            entry("/api/assetProfiles", Resource.ASSET_PROFILE),
            entry("/api/assetProfileInfos", Resource.ASSET_PROFILE),
            entry("/api/assetProfileInfos/list", Resource.ASSET_PROFILE),
            entry("/api/assetProfile/names", Resource.ASSET_PROFILE),
            entry("/api/assetProfileInfo/default", Resource.ASSET_PROFILE),

            // alarm rules are calculated-field rows of type ALARM: same table, same gate
            entry("/api/calculatedFields", Resource.CALCULATED_FIELD),
            entry("/api/calculatedFields/names", Resource.CALCULATED_FIELD),
            entry("/api/alarm/rules", Resource.CALCULATED_FIELD),
            entry("/api/alarm/rules/names", Resource.CALCULATED_FIELD),

            entry("/api/users", Resource.USER),
            entry("/api/users/assign/{alarmId}", Resource.USER),

            entry("/api/ruleChains", Resource.RULE_CHAIN),
            entry("/api/ruleChains/list", Resource.RULE_CHAIN),
            entry("/api/ruleChains/export", Resource.RULE_CHAIN),
            entry("/api/ruleChain/autoAssignToEdgeRuleChains", Resource.RULE_CHAIN),
            entry("/api/edge/{edgeId}/ruleChains", Resource.RULE_CHAIN),

            entry("/api/resource", Resource.TB_RESOURCE),
            entry("/api/resource/list", Resource.TB_RESOURCE),
            entry("/api/resource/tenant", Resource.TB_RESOURCE),
            entry("/api/resource/lwm2m", Resource.TB_RESOURCE),
            entry("/api/resource/lwm2m/page", Resource.TB_RESOURCE),
            entry("/api/images", Resource.TB_RESOURCE),

            entry("/api/otaPackages", Resource.OTA_PACKAGE),
            entry("/api/otaPackages/{deviceProfileId}/{type}", Resource.OTA_PACKAGE),
            entry("/api/otaPackage/info/{otaPackageId}", Resource.OTA_PACKAGE),

            entry("/api/notification/requests", Resource.NOTIFICATION),
            entry("/api/notification/rules", Resource.NOTIFICATION),
            entry("/api/notification/targets", Resource.NOTIFICATION),
            entry("/api/notification/targets/list", Resource.NOTIFICATION),
            entry("/api/notification/targets/notificationType/{notificationType}", Resource.NOTIFICATION),
            entry("/api/notification/templates", Resource.NOTIFICATION),
            entry("/api/notification/slack/conversations", Resource.NOTIFICATION),

            entry("/api/oauth2/client/infos", Resource.OAUTH2_CLIENT),
            entry("/api/oauth2/client/list", Resource.OAUTH2_CLIENT),

            entry("/api/mobile/app", Resource.MOBILE_APP),
            entry("/api/mobile/bundle/infos", Resource.MOBILE_APP_BUNDLE),

            entry("/api/queues", Resource.QUEUE),
            entry("/api/queues/name/{queueName}", Resource.QUEUE),
            entry("/api/queueStats", Resource.QUEUE_STATS),
            entry("/api/queueStats/list", Resource.QUEUE_STATS),
            entry("/api/queueStats/{queueStatsId}", Resource.QUEUE_STATS),

            entry("/api/jobs", Resource.JOB),
            entry("/api/rpc/persistent/device/{deviceId}", Resource.RPC),
            entry("/api/lwm2m/deviceProfile/bootstrap/{isBootstrapServer}", Resource.DEVICE_PROFILE)
    );

    private static final String MVC_HANDLER_MAPPING_BEAN = "requestMappingHandlerMapping";

    private final ThingsboardErrorResponseHandler errorResponseHandler;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Resource resource = pattern instanceof String ? GATED_READS.get(pattern) : null;
        if (resource == null) {
            return true;
        }
        SecurityUser user = currentUser();
        if (user == null || UserPermissionsUtil.granted(user, resource, Operation.READ)) {
            return true;
        }
        log.debug("[{}][{}] Role denies {} READ on {}", user.getTenantId(), user.getId(), resource, pattern);
        errorResponseHandler.handle(new ThingsboardException("You don't have permission to perform this operation!",
                ThingsboardErrorCode.PERMISSION_DENIED), response);
        return false;
    }

    private static SecurityUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser securityUser) {
            return securityUser;
        }
        return null;
    }

    /**
     * A gate keyed on a URL that no longer exists fails open and silently. Compare the table
     * against the mappings Spring actually registered and complain loudly if one has rotted.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void verifyPatterns(ContextRefreshedEvent event) {
        // By name, not by type: actuator contributes a second RequestMappingHandlerMapping
        // (controllerEndpointHandlerMapping), so a by-type lookup is ambiguous and aborts startup.
        // Read off the event rather than injecting it - the mapping is built from the
        // WebMvcConfigurers, one of which registers this interceptor, so injecting it is a cycle.
        ApplicationContext context = event.getApplicationContext();
        if (!context.containsBean(MVC_HANDLER_MAPPING_BEAN)) {
            log.warn("No '{}' bean - cannot verify that the RBAC read gate patterns are still mapped",
                    MVC_HANDLER_MAPPING_BEAN);
            return;
        }
        RequestMappingHandlerMapping handlerMapping =
                context.getBean(MVC_HANDLER_MAPPING_BEAN, RequestMappingHandlerMapping.class);
        Set<String> registered = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var pathPatterns = info.getPathPatternsCondition();
            if (pathPatterns != null) {
                registered.addAll(pathPatterns.getPatternValues());
            } else if (info.getPatternsCondition() != null) {
                registered.addAll(info.getPatternsCondition().getPatterns());
            }
        }
        Set<String> missing = new TreeSet<>(GATED_READS.keySet());
        missing.removeAll(registered);
        if (missing.isEmpty()) {
            log.info("RBAC read gate active on {} endpoints", GATED_READS.size());
        } else {
            log.error("RBAC read gate is INACTIVE on {} endpoint(s) - these patterns are no longer mapped and " +
                    "the role restriction on them is silently lost: {}", missing.size(), missing);
        }
    }

}
