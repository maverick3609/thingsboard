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

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.exception.ThingsboardErrorResponseHandler;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class RoleReadGateInterceptorTest {

    private static final String GATED_PATTERN = "/api/tenant/devices";

    private final ThingsboardErrorResponseHandler errorResponseHandler = mock(ThingsboardErrorResponseHandler.class);
    private final RoleReadGateInterceptor interceptor = new RoleReadGateInterceptor(errorResponseHandler);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testTableIsGatedOnRealApiPatterns() {
        assertFalse(RoleReadGateInterceptor.GATED_READS.isEmpty());
        RoleReadGateInterceptor.GATED_READS.forEach((pattern, resource) -> {
            assertTrue(pattern.startsWith("/api/"), pattern + " must be a full mapping pattern");
            assertTrue(resource != Resource.ALL, pattern + " must name the resource it exposes, not ALL");
        });
        assertTrue(RoleReadGateInterceptor.GATED_READS.containsKey(GATED_PATTERN));
    }

    @Test
    public void testUngatedPatternIsUntouched() {
        authenticate(restrictedUser("{\"DASHBOARD\": [\"READ\"]}"));
        assertTrue(preHandle("/api/user/settings/GENERAL", new MockHttpServletResponse()));
        verify(errorResponseHandler, never()).handle(any(), any(HttpServletResponse.class));
    }

    @Test
    public void testLegacyAndSysAdminUsersAreNotGated() {
        // no roles assigned: keeps the pre-RBAC behaviour
        authenticate(user(Authority.TENANT_ADMIN, null));
        assertTrue(preHandle(GATED_PATTERN, new MockHttpServletResponse()));

        authenticate(user(Authority.SYS_ADMIN, null));
        assertTrue(preHandle(GATED_PATTERN, new MockHttpServletResponse()));

        // unauthenticated (public endpoints reach the interceptor too)
        SecurityContextHolder.clearContext();
        assertTrue(preHandle(GATED_PATTERN, new MockHttpServletResponse()));
        verify(errorResponseHandler, never()).handle(any(), any(HttpServletResponse.class));
    }

    @Test
    public void testRoleWithoutReadIsDenied() {
        authenticate(restrictedUser("{\"DASHBOARD\": [\"READ\"]}"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(preHandle(GATED_PATTERN, response));
        verify(errorResponseHandler).handle(any(), any(HttpServletResponse.class));
    }

    @Test
    public void testRoleWithReadIsAllowed() {
        authenticate(restrictedUser("{\"DEVICE\": [\"READ\"]}"));
        assertTrue(preHandle(GATED_PATTERN, new MockHttpServletResponse()));

        authenticate(restrictedUser("{\"ALL\": [\"ALL\"]}"));
        assertTrue(preHandle(GATED_PATTERN, new MockHttpServletResponse()));
        verify(errorResponseHandler, never()).handle(any(), any(HttpServletResponse.class));
    }

    @Test
    public void testWidgetLibraryListsAreGatedButTheRenderPathIsNot() {
        assertEquals(Resource.WIDGETS_BUNDLE, RoleReadGateInterceptor.GATED_READS.get("/api/widgetsBundles"));
        assertEquals(Resource.WIDGET_TYPE, RoleReadGateInterceptor.GATED_READS.get("/api/widgetTypes"));
        assertEquals(Resource.WIDGET_TYPE, RoleReadGateInterceptor.GATED_READS.get("/api/widgetTypesInfos"));
        // a dashboard resolves every widget through this one; gating it blanks out the dashboard
        assertNull(RoleReadGateInterceptor.GATED_READS.get("/api/widgetType"));
    }

    /**
     * Actuator publishes a second RequestMappingHandlerMapping (controllerEndpointHandlerMapping),
     * so resolving the mapping by type aborts startup with NoUniqueBeanDefinitionException.
     */
    @Test
    public void testStartupCheckSurvivesASecondHandlerMappingBean() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        context.registerSingleton("controllerEndpointHandlerMapping", RequestMappingHandlerMapping.class);
        context.refresh();
        assertDoesNotThrow(() -> interceptor.verifyPatterns(new ContextRefreshedEvent(context)));
    }

    private boolean preHandle(String pattern, MockHttpServletResponse response) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", pattern);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return interceptor.preHandle(request, response, mock(HandlerMethod.class));
    }

    private static void authenticate(SecurityUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "password", Collections.emptyList()));
    }

    private static SecurityUser restrictedUser(String permissionsJson) {
        Role role = new Role();
        role.setType(RoleType.GENERIC);
        role.setPermissions(JacksonUtil.toJsonNode(permissionsJson));
        return user(Authority.TENANT_ADMIN, DefaultUserPermissionsService.mergeRolePermissions(List.of(role)));
    }

    private static SecurityUser user(Authority authority, MergedUserPermissions permissions) {
        SecurityUser user = new SecurityUser();
        user.setAuthority(authority);
        user.setUserPermissions(permissions);
        return user;
    }

}
