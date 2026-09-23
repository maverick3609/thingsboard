// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAccess.GatewayUnreachableException;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;

import java.util.Locale;

/**
 * Whether the platform can reach a gateway, and if not, why.
 *
 * <p>The {@code reason} is the point. "Unreachable" on its own sends an operator to check cables
 * when the real answer is that they pasted the wrong secret, that the token they issued is not an
 * administrator, or that this platform node has no sealing key — three problems in three different
 * places, none of them the network. Each condition keeps its own reason and nothing collapses two
 * of them together.
 *
 * <p>Pure: it classifies an answer that someone else obtained. That is what makes every branch
 * testable without a device.
 *
 * @param stackVersion the gateway's stack version when it answered, otherwise {@code null}. Carried
 *                     here because {@code /v2/about} is the only identity source that exists — the
 *                     stack serves no version or uptime endpoint besides it — so the Details and
 *                     Health tabs would otherwise spend a second call on it.
 */
public record InferrixGatewayReachability(boolean reachable, String reason, String message,
                                          long checkedAt, String stackVersion) {

    /** Classifies an answer the gateway actually gave. */
    public static InferrixGatewayReachability of(GatewayResponse response) {
        return switch (response.statusCode()) {
            case 200 -> new InferrixGatewayReachability(true, "OK",
                    "The gateway answered.", now(), versionOf(response.body()));
            // The network is fine and the credential is not. Saying "unreachable" would send the
            // operator to check the one thing that is working.
            case 401 -> unreachable("UNAUTHORIZED", "The gateway rejected the platform's API token."
                    + " It may have been rotated or revoked on the gateway; adopt again with a new"
                    + " token pair.");
            // The expected steady state until stack ask A10 lands: the service account is
            // deliberately non-admin, so platform-link routes answer 403 while the rest works.
            case 403 -> unreachable("FORBIDDEN", "The gateway accepted the platform's API token but"
                    + " refused the request. The token's account does not have permission for it.");
            default -> unreachable("HTTP_ERROR",
                    "The gateway answered HTTP " + response.statusCode() + ".");
        };
    }

    /** Classifies a failure that stopped the call from completing. */
    public static InferrixGatewayReachability of(Exception failure) {
        if (failure instanceof GatewayUnreachableException e) {
            // load() already decided why — NO_ADDRESS, BAD_ADDRESS, NO_CREDENTIAL — and each sends
            // the operator somewhere different. Pass it through rather than re-deciding.
            //
            // The exception prefixes its own message with the reason, which is right for a log
            // line and wrong here: reason is a field of its own, so repeating it would show the
            // operator "NO_ADDRESS: NO_ADDRESS: ...".
            String detail = e.getMessage();
            String prefix = e.getReason() + ": ";
            return unreachable(e.getReason(),
                    detail != null && detail.startsWith(prefix)
                            ? detail.substring(prefix.length()) : detail);
        }
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (failure instanceof IllegalStateException && message.contains("credentials_key")) {
            // Nothing is wrong with the gateway. An operator told "unreachable" here would go and
            // power-cycle a working device.
            return unreachable("NO_SEALING_KEY", message);
        }
        if (message.toLowerCase(Locale.ROOT).contains("fingerprint")) {
            // The one failure that must not be shrugged off as a flaky network: the device is not
            // the device that was adopted.
            return unreachable("CERTIFICATE_CHANGED", message);
        }
        return unreachable("UNREACHABLE", message);
    }

    private static InferrixGatewayReachability unreachable(String reason, String message) {
        return new InferrixGatewayReachability(false, reason, message, now(), null);
    }

    private static String versionOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        // A gateway that answers 200 is reachable whatever its body looks like. Parsing is a
        // convenience here, never a condition -- and JacksonUtil.toJsonNode throws on malformed
        // input rather than returning null, so an unparseable body must not be allowed to turn a
        // successful probe into a failed one.
        try {
            JsonNode json = JacksonUtil.toJsonNode(body);
            return json != null && json.hasNonNull("version") ? json.get("version").asText() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
