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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferrixProxyRoutesTest {

    @Test
    void theAuthRoutesAreNeverForwarded() {
        // Calling either through the proxy revokes the token the platform holds, locking it out of
        // the device it manages. This is the single most important thing the allowlist prevents.
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/auth/login"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/auth/password"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/auth/provision"));
    }

    @Test
    void theBinaryUploadRoutesAreNotForwarded() {
        // Raw octet streams against a 2048-byte request cap; they need their own chunking flow.
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/firmware"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/firmware/begin"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/firmware/apply"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/logic"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/logic/begin"));
        assertFalse(InferrixProxyRoutes.isAllowed("POST", "/api/v1/logic/apply"));
        // ...but reading their state is fine
        assertTrue(InferrixProxyRoutes.isAllowed("GET", "/api/v1/firmware/status"));
        assertTrue(InferrixProxyRoutes.isAllowed("GET", "/api/v1/logic/status"));
    }

    @Test
    void traversalAndSmugglingAttemptsDoNotMatch() {
        assertFalse(InferrixProxyRoutes.isAllowed("GET", "/api/v1/health/../auth/login"));
        assertFalse(InferrixProxyRoutes.isAllowed("GET", "/api/v1/../../etc/passwd"));
        assertFalse(InferrixProxyRoutes.isAllowed("GET", "/api/v1/healthx"));
        assertFalse(InferrixProxyRoutes.isAllowed("GET", "xx/api/v1/health"));
        assertFalse(InferrixProxyRoutes.isAllowed("GET", "/api/v1/health?x=1"));
        assertFalse(InferrixProxyRoutes.isAllowed("GET", ""));
        assertFalse(InferrixProxyRoutes.isAllowed(null, "/api/v1/health"));
        assertFalse(InferrixProxyRoutes.isAllowed("GET", null));
    }

    @Test
    void aRouteIsOnlyOpenForTheMethodsItDeclares() {
        assertTrue(InferrixProxyRoutes.isAllowed("GET", "/api/v1/health"));
        assertFalse(InferrixProxyRoutes.isAllowed("PUT", "/api/v1/health"));
        assertFalse(InferrixProxyRoutes.isAllowed("DELETE", "/api/v1/health"));
        assertTrue(InferrixProxyRoutes.isAllowed("PUT", "/api/v1/network"));
        assertFalse(InferrixProxyRoutes.isAllowed("DELETE", "/api/v1/network"));
    }

    @Test
    void theConfigPlaneIsReachableIncludingIdentifiedRecords() {
        assertTrue(InferrixProxyRoutes.isAllowed("GET", "/api/v1/config"));
        assertTrue(InferrixProxyRoutes.isAllowed("GET", "/api/v1/config/draft"));
        assertTrue(InferrixProxyRoutes.isAllowed("POST", "/api/v1/config/apply"));
        assertTrue(InferrixProxyRoutes.isAllowed("POST", "/api/v1/config/draft/points"));
        assertTrue(InferrixProxyRoutes.isAllowed("PUT", "/api/v1/config/draft/mqtt-policies"));
        assertTrue(InferrixProxyRoutes.isAllowed("DELETE", "/api/v1/config/draft/scalings/7"));
        assertTrue(InferrixProxyRoutes.isAllowed("DELETE", "/api/v1/config/draft/peers/3"));
        // an id is digits, not an arbitrary segment
        assertFalse(InferrixProxyRoutes.isAllowed("DELETE", "/api/v1/config/draft/points/abc"));
        assertFalse(InferrixProxyRoutes.isAllowed("DELETE", "/api/v1/config/draft/nonsense/1"));
    }

    @Test
    void methodMatchingIsCaseInsensitive() {
        assertTrue(InferrixProxyRoutes.isAllowed("get", "/api/v1/health"));
    }

}
