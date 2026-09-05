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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.thingsboard.server.cache.CacheSpecs;
import org.thingsboard.server.cache.CacheSpecsMap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers a default spec for the RBAC "permissions" cache when the running configuration has
 * none.
 * <p>
 * The spec ships in the packaged {@code thingsboard.yml}, but an install that places its own
 * {@code conf/thingsboard.yml} replaces that file wholesale — a plain jar swap then boots with a
 * {@code cache.specs} map that has no {@code permissions} key. On caffeine that fails the context
 * outright ({@code Cache 'permissions' is not configured}); on redis it silently disables caching
 * and re-queries the roles on every request. Either way the operator sees a failure that names
 * neither this feature nor the key to add, so supply the default in code instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserPermissionsCacheSpecRegistrar implements BeanPostProcessor {

    private static final String CACHE_NAME = "permissions";
    private static final int DEFAULT_TTL_MINUTES = 60;
    private static final int DEFAULT_MAX_SIZE = 10000;

    private final Environment environment;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof CacheSpecsMap specsMap)) {
            return bean;
        }
        Map<String, CacheSpecs> specs = specsMap.getSpecs();
        if (specs != null && specs.containsKey(CACHE_NAME)) {
            return bean;
        }
        CacheSpecs defaults = new CacheSpecs();
        defaults.setTimeToLiveInMinutes(intProperty("CACHE_SPECS_PERMISSIONS_TTL", DEFAULT_TTL_MINUTES));
        defaults.setMaxSize(intProperty("CACHE_SPECS_PERMISSIONS_MAX_SIZE", DEFAULT_MAX_SIZE));
        Map<String, CacheSpecs> merged = specs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(specs);
        merged.put(CACHE_NAME, defaults);
        specsMap.setSpecs(merged);
        log.info("No 'cache.specs.{}' in the configuration - registering the default RBAC permissions cache spec " +
                "(ttl {} min, max size {})", CACHE_NAME, defaults.getTimeToLiveInMinutes(), defaults.getMaxSize());
        return bean;
    }

    private int intProperty(String name, int defaultValue) {
        Integer value = environment.getProperty(name, Integer.class);
        return value == null ? defaultValue : value;
    }

}
