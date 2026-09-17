// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
