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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.thingsboard.server.common.data.EntityType;

import java.io.Serial;
import java.io.Serializable;

/**
 * Key for the install-wide licensed-entity count cache. The licence caps the deployment, so unlike
 * ThingsBoard's own EntityCountCacheKey there is no tenant in the key.
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public class LicenseCountCacheKey implements Serializable {

    /** Cache name. Defined here rather than in TB's CacheConstants to avoid a ThingsBoard-core touch. */
    public static final String CACHE_NAME = "inferrixLicenseCount";

    @Serial
    private static final long serialVersionUID = 1L;

    private final EntityType entityType;

    @Override
    public String toString() {
        return "license_count_" + entityType;
    }
}
