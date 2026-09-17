// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
