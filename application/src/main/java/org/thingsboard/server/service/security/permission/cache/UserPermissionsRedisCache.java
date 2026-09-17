// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.thingsboard.server.cache.CacheSpecsMap;
import org.thingsboard.server.cache.RedisTbTransactionalCache;
import org.thingsboard.server.cache.TBRedisCacheConfiguration;
import org.thingsboard.server.cache.TbJsonRedisSerializer;
import org.thingsboard.server.service.security.permission.MergedUserPermissions;
import org.thingsboard.server.service.security.permission.UserPermissionCacheKey;

@ConditionalOnProperty(prefix = "cache", value = "type", havingValue = "redis")
@Service("UserPermissionsCache")
public class UserPermissionsRedisCache extends RedisTbTransactionalCache<UserPermissionCacheKey, MergedUserPermissions> {

    public UserPermissionsRedisCache(TBRedisCacheConfiguration configuration, CacheSpecsMap cacheSpecsMap, RedisConnectionFactory connectionFactory) {
        super("permissions", cacheSpecsMap, connectionFactory, configuration, new TbJsonRedisSerializer<>(MergedUserPermissions.class));
    }

}
