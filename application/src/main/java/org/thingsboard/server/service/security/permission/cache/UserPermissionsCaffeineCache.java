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
package org.thingsboard.server.service.security.permission.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.thingsboard.server.cache.CaffeineTbTransactionalCache;
import org.thingsboard.server.service.security.permission.MergedUserPermissions;
import org.thingsboard.server.service.security.permission.UserPermissionCacheKey;

@ConditionalOnProperty(prefix = "cache", value = "type", havingValue = "caffeine", matchIfMissing = true)
@Service("UserPermissionsCache")
public class UserPermissionsCaffeineCache extends CaffeineTbTransactionalCache<UserPermissionCacheKey, MergedUserPermissions> {

    public UserPermissionsCaffeineCache(CacheManager cacheManager) {
        // Inferrix-owned cache name — deliberately NOT in the TB-core CacheConstants
        // (one fewer upstream file to patch); must match the cache.specs key in thingsboard.yml.
        super(cacheManager, "permissions");
    }

}
