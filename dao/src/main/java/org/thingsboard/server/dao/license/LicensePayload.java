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
package org.thingsboard.server.dao.license;

import java.util.Map;

/**
 * A decoded, signature-verified licence payload.
 * <p>
 * Payload times are epoch <b>seconds</b>, because that is what a human reads out of a decoded key.
 * Everything inside the platform is epoch <b>milliseconds</b>. {@link #expMillis()} and {@link #iatMillis()}
 * are the only places that conversion happens.
 */
public record LicensePayload(int version, String iid, String cust, long iat, long exp, Map<String, Long> plan) {

    public LicensePayload {
        plan = plan == null ? Map.of() : Map.copyOf(plan);
    }

    public long expMillis() {
        return exp * 1000L;
    }

    public long iatMillis() {
        return iat * 1000L;
    }

    /** The cap for a plan key, or {@code null} when the key is absent, which means unlimited. */
    public Long cap(String key) {
        return plan.get(key);
    }
}
