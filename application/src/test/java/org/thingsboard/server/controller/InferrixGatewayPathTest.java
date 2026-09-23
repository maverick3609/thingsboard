// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayRoutes;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.UserPrincipal;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The proxy endpoint is mapped at {@code /{deviceId}/proxy/**}, so the device-bound path has to be
 * carved back out of the request URI. That carving is the only piece of the controller worth
 * testing on its own — everything else it does is delegation — and it is the piece that decides
 * what string the allowlist is handed, so getting it wrong is a security bug rather than a bug.
 */
class InferrixGatewayPathTest {

    private static final String ID = "784f394c-42b6-435a-983c-b7beff2784f9";

    @Test
    void theDevicePathIsWhateverFollowsProxy() {
        assertEquals("/v2/about", path("/api/inferrix/gateways/" + ID + "/proxy/v2/about"));
        assertEquals("/v2/data-source/DS_1", path("/api/inferrix/gateways/" + ID + "/proxy/v2/data-source/DS_1"));
        assertEquals("/v2/model-schemas", path("/api/inferrix/gateways/" + ID + "/proxy/v2/model-schemas"));
    }

    @Test
    void aUriThatIsNotAProxyCallYieldsNothing() {
        // Empty is the safe answer: no route matches it, so the caller is refused. Returning the
        // whole URI here would hand the allowlist a string it was never meant to judge.
        assertEquals("", path("/api/inferrix/gateways/" + ID + "/reachability"));
        assertEquals("", path("/api/something/else"));
        assertEquals("", path(""));
    }

    @Test
    void anEmptyRemainderIsNotARoute() {
        assertEquals("", path("/api/inferrix/gateways/" + ID + "/proxy"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", ""));
    }

    @Test
    void theCarvedPathIsStillSubjectToTheAllowlist() {
        // The two halves have to agree: whatever this produces is what gets judged. A traversal
        // written into the URI survives carving -- it is the allowlist, not the carving, that
        // refuses it, and this pins that the seam between them has no gap.
        String traversal = path("/api/inferrix/gateways/" + ID + "/proxy/v2/about/../auth/user");
        assertEquals("/v2/about/../auth/user", traversal);
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", traversal));

        String encoded = path("/api/inferrix/gateways/" + ID + "/proxy/v2/data-source/%2e%2e");
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", encoded));
    }

    @Test
    void percentDecodingByTheContainerCannotSmuggleAPathThrough() {
        // getRequestURI() is specified to hand back the raw, undecoded URI, so the refusal of '%'
        // in InferrixGatewayRoutes is what stops an encoded path -- and the previous test pins
        // that. But the refusal must not be the ONLY thing standing there: a container that
        // decodes first (a future Tomcat setting, a proxy in front, a different servlet engine)
        // would hand over a string with no '%' left in it, and the allowlist would then be judging
        // the decoded form on its own merits. So pin that the decoded forms are refused too.
        //
        // This is what makes the container's behaviour a non-question rather than a dependency:
        // undecoded is refused for containing '%', decoded is refused for what it decodes to.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/.."));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/about/../auth/user"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/about/..%2fauth/user"));
        // %2f decoded is a bare separator, which only matters if it lands somewhere a route
        // pattern would otherwise accept. It does not: every pattern is anchored end to end.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/about/auth/user"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/auth/oauth/token"));
        // And the one that would actually hurt: a traversal that resolves back to an excluded
        // route. Refused on the literal string, before anything normalises it.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/data-source/../script"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/data-source/%2e%2e/script"));
    }

    @Test
    void aPublicDashboardLinkHolderCannotReachADeviceProxy() throws Exception {
        // TB's synthetic public user is a CUSTOMER_USER assigned to the public customer, and the
        // public role grants DEVICE READ -- so @PreAuthorize admits it and checkDeviceId(READ)
        // passes. Every GET on the allowlist would then be reachable by anyone holding a shared
        // dashboard link, with no account: the gateway's data sources, publishers, schedules,
        // event handlers and its network interfaces. One click ("Make device public") or putting
        // the gateway on a public dashboard to show connectivity is the whole precondition.
        //
        // The guard is shared with InferrixPlcController, which had the identical hole over the
        // soft-PLC proxy's /info, /health, /points, /diag/*, /config, /network, /mqtt and
        // /identity reads.
        //
        // Authority is not the thing to test here -- the public user's authority is genuinely
        // CUSTOMER_USER. The principal type is what distinguishes it.
        User user = new User();
        user.setAuthority(Authority.CUSTOMER_USER);
        user.setCustomerId(new CustomerId(UUID.randomUUID()));

        SecurityUser publicLink = new SecurityUser(user, true,
                new UserPrincipal(UserPrincipal.Type.PUBLIC_ID, "some-public-id"));
        assertThatThrownBy(() -> InferrixPublicLink.requireNotPublicLink(publicLink))
                .hasMessageContaining("public dashboard link");

        // A real customer user, same authority, is unaffected.
        SecurityUser realUser = new SecurityUser(user, true,
                new UserPrincipal(UserPrincipal.Type.USER_NAME, "someone@example.com"));
        InferrixPublicLink.requireNotPublicLink(realUser);
    }

    private static String path(String requestUri) {
        return InferrixGatewayController.devicePath(requestUri, ID);
    }
}
