// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.UserPrincipal;

/**
 * Keeps the holder of a shared public-dashboard link out of a device's own API.
 *
 * <p>One class for one method, deliberately. Both Inferrix device proxies need this check and a
 * duplicated security guard is the kind that drifts — one copy gets a fix the other does not, and
 * nothing fails. The alternatives were worse: a cross-controller call would make the soft-PLC
 * feature depend on the gateway feature, and the natural home, {@code BaseController}, is
 * ThingsBoard core, where every touch is a ledger row and an upstream merge conflict.
 */
final class InferrixPublicLink {

    private InferrixPublicLink() {
    }

    /**
     * Refuses the synthetic user ThingsBoard creates behind a shared public-dashboard link.
     *
     * <p>Authority cannot tell it apart, which is the whole trap: TB builds that user as a real
     * {@code CUSTOMER_USER} assigned to the public customer
     * ({@code DefaultSystemSecurityService}), and the public role grants {@code DEVICE READ}
     * ({@code DefaultUserPermissionsService}) — so {@code @PreAuthorize} admits it and
     * {@code checkDeviceId(..., READ)} passes. Every GET a device proxy allows would then be
     * reachable by anyone holding the link, with no account at all. The precondition is one click:
     * "Make device public", or putting the device on a public dashboard to show whether it is
     * online.
     *
     * <p>The principal type is the only thing that distinguishes it.
     *
     * <p>Package-visible and taking the user rather than reading the security context, so the rule
     * can be tested without a servlet container.
     */
    static void requireNotPublicLink(SecurityUser user) throws ThingsboardException {
        if (user != null && user.getUserPrincipal() != null
                && UserPrincipal.Type.PUBLIC_ID == user.getUserPrincipal().getType()) {
            throw new ThingsboardException("A public dashboard link cannot reach device"
                    + " configuration", ThingsboardErrorCode.PERMISSION_DENIED);
        }
    }
}
