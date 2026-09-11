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
package org.thingsboard.server.service.inferrix;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the platform will forward to a controller, and nothing else.
 *
 * <p>An allowlist rather than a blanket pass-through, for three reasons:
 *
 * <ul>
 *   <li><b>The auth routes are excluded on purpose.</b> {@code /auth/login} mints a new token and
 *       revokes the previous one, {@code /auth/password} revokes it too — either called through the
 *       proxy would silently invalidate the token the platform holds and lock it out of the device
 *       it manages. The platform owns those credentials; the UI never touches them.</li>
 *   <li><b>The binary upload routes are excluded</b> ({@code /firmware}, {@code /logic} chunk
 *       appends). They are raw octet streams against a 2048-byte request cap, so they are served by
 *       {@link InferrixUploadService} and its own endpoints instead — the platform holds the
 *       artifact and drives several hundred chunks itself. Do not add them here: proxying them
 *       would put the whole loop in the browser, one round trip per chunk. Their read-only status
 *       endpoints are allowed.</li>
 *   <li>Matching a fixed pattern per route means no user-supplied path ever reaches the device — a
 *       traversal attempt simply fails to match.</li>
 * </ul>
 *
 * <p>Everything here is device configuration the tenant already owns outright, so allowing it is not
 * a privilege escalation; the list exists to keep the two categories above out.
 */
public final class InferrixProxyRoutes {

    private static final String ID = "[0-9]{1,10}";

    private static final List<Route> ROUTES = List.of(
            // Read-only status and identity
            route("/api/v1/info", "GET"),
            route("/api/v1/health", "GET"),
            route("/api/v1/points", "GET"),
            route("/api/v1/points/" + ID, "GET"),
            // A point write goes through the same validation ladder as an MQTT "set" and reaches
            // real plant, so it needs the TENANT_ADMIN that every non-GET here already takes.
            route("/api/v1/points/" + ID, "POST"),
            route("/api/v1/diag/network", "GET"),
            route("/api/v1/diag/memory", "GET"),
            route("/api/v1/logic/status", "GET"),
            route("/api/v1/firmware/status", "GET"),

            // Settings
            route("/api/v1/identity", "GET", "PUT"),
            route("/api/v1/network", "GET", "PUT"),
            route("/api/v1/network/confirm", "POST"),
            route("/api/v1/mqtt", "GET", "PUT"),
            route("/api/v1/discovery", "GET", "PUT"),
            route("/api/v1/time", "GET", "PUT"),
            route("/api/v1/peers", "GET", "PUT"),

            // Config plane
            route("/api/v1/config", "GET"),
            route("/api/v1/config/draft", "GET"),
            route("/api/v1/config/apply", "POST"),
            route("/api/v1/config/discard", "POST"),
            route("/api/v1/config/owner", "GET", "PUT"),
            route("/api/v1/config/draft/(buses|queries|points|scalings|mqtt-policies|peers)", "POST", "PUT"),
            route("/api/v1/config/draft/(buses|queries|points|scalings|mqtt-policies|peers)/" + ID, "DELETE"),

            // Logic control (the program upload itself is not proxied)
            route("/api/v1/logic/restart", "POST"),
            route("/api/v1/logic/tune", "POST"),
            // The autotune outcome is read per slot: the device answers 400 on the bare path.
            route("/api/v1/logic/tune/" + ID, "GET"),
            route("/api/v1/logic/tune/abort", "POST"),

            // Restarting the controller. Plant control stops for the reboot and a staged logic
            // program or firmware image activates, so this is the most consequential thing on the
            // proxy — it is here rather than behind its own endpoint only because the gate is
            // already right: every non-GET route requires TENANT_ADMIN and device WRITE.
            route("/api/v1/system/reboot", "POST"),

            // Diagnostics and attestation
            route("/api/v1/diag/ping", "POST"),
            route("/api/v1/mqtt/test", "POST"),
            route("/api/v1/attest", "POST"));

    private InferrixProxyRoutes() {
    }

    /**
     * @param path the device path, which must already have had any query string separated off
     */
    public static boolean isAllowed(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String upper = method.toUpperCase();
        for (Route route : ROUTES) {
            if (route.methods().contains(upper) && route.pattern().matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Route route(String pattern, String... methods) {
        return new Route(Pattern.compile(pattern), Set.of(methods));
    }

    private record Route(Pattern pattern, Set<String> methods) {
    }

}
