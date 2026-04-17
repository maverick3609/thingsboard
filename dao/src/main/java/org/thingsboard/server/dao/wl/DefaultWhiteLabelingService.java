/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
package org.thingsboard.server.dao.wl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.CacheConstants;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.LoginWhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabeling;
import org.thingsboard.server.common.data.wl.WhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabelingType;
import org.thingsboard.server.dao.model.sql.WhiteLabelingCompositeKey;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultWhiteLabelingService implements WhiteLabelingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WhiteLabelingDao whiteLabelingDao;

    // --- Raw getters ---

    @Override
    @Cacheable(cacheNames = CacheConstants.WHITE_LABELING_CACHE,
            key = "'sys:' + #root.methodName")
    public WhiteLabelingParams getSystemWhiteLabelingParams() {
        return loadParams(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.GENERAL),
                WhiteLabelingParams.class);
    }

    @Override
    @Cacheable(cacheNames = CacheConstants.WHITE_LABELING_CACHE,
            key = "'tenant:' + #tenantId.id + ':GENERAL'")
    public WhiteLabelingParams getTenantWhiteLabelingParams(TenantId tenantId) {
        return loadParams(WhiteLabelingCompositeKey.forTenant(tenantId, WhiteLabelingType.GENERAL),
                WhiteLabelingParams.class);
    }

    @Override
    @Cacheable(cacheNames = CacheConstants.WHITE_LABELING_CACHE,
            key = "'customer:' + #tenantId.id + ':' + #customerId.id + ':GENERAL'")
    public WhiteLabelingParams getCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId) {
        return loadParams(WhiteLabelingCompositeKey.forCustomer(tenantId, customerId, WhiteLabelingType.GENERAL),
                WhiteLabelingParams.class);
    }

    @Override
    @Cacheable(cacheNames = CacheConstants.WHITE_LABELING_CACHE,
            key = "'sys:loginParams'")
    public LoginWhiteLabelingParams getSystemLoginWhiteLabelingParams() {
        return loadParams(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.LOGIN),
                LoginWhiteLabelingParams.class);
    }

    @Override
    @Cacheable(cacheNames = CacheConstants.WHITE_LABELING_CACHE,
            key = "'tenant:' + #tenantId.id + ':LOGIN'")
    public LoginWhiteLabelingParams getTenantLoginWhiteLabelingParams(TenantId tenantId) {
        return loadParams(WhiteLabelingCompositeKey.forTenant(tenantId, WhiteLabelingType.LOGIN),
                LoginWhiteLabelingParams.class);
    }

    @Override
    @Cacheable(cacheNames = CacheConstants.WHITE_LABELING_CACHE,
            key = "'customer:' + #tenantId.id + ':' + #customerId.id + ':LOGIN'")
    public LoginWhiteLabelingParams getCustomerLoginWhiteLabelingParams(TenantId tenantId, CustomerId customerId) {
        return loadParams(WhiteLabelingCompositeKey.forCustomer(tenantId, customerId, WhiteLabelingType.LOGIN),
                LoginWhiteLabelingParams.class);
    }

    // --- Merged getters ---

    @Override
    public WhiteLabelingParams getMergedTenantWhiteLabelingParams(TenantId tenantId) {
        WhiteLabelingParams system = getSystemWhiteLabelingParams();
        WhiteLabelingParams base = system != null ? new WhiteLabelingParams(system) : WhiteLabelingParams.defaultParams();

        WhiteLabelingParams tenant = getTenantWhiteLabelingParams(tenantId);
        if (tenant != null) {
            WhiteLabelingParams merged = new WhiteLabelingParams(tenant);
            merged.merge(base);
            return merged;
        }
        return base;
    }

    @Override
    public WhiteLabelingParams getMergedCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId) {
        WhiteLabelingParams tenantMerged = getMergedTenantWhiteLabelingParams(tenantId);

        WhiteLabelingParams customer = getCustomerWhiteLabelingParams(tenantId, customerId);
        if (customer != null) {
            WhiteLabelingParams merged = new WhiteLabelingParams(customer);
            merged.merge(tenantMerged);
            return merged;
        }
        return tenantMerged;
    }

    @Override
    public LoginWhiteLabelingParams getMergedLoginWhiteLabelingParams(String serverName) {
        LoginWhiteLabelingParams system = getSystemLoginWhiteLabelingParams();
        LoginWhiteLabelingParams base = system != null
                ? new LoginWhiteLabelingParams(system)
                : LoginWhiteLabelingParams.defaultLoginParams();
        return base;
    }

    // --- Save ---

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public WhiteLabelingParams saveSystemWhiteLabelingParams(WhiteLabelingParams params) {
        saveWl(TenantId.SYS_TENANT_ID, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.GENERAL, params);
        return params;
    }

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public WhiteLabelingParams saveTenantWhiteLabelingParams(TenantId tenantId, WhiteLabelingParams params) {
        saveWl(tenantId, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.GENERAL, params);
        return params;
    }

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public WhiteLabelingParams saveCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId, WhiteLabelingParams params) {
        saveWl(tenantId, customerId, WhiteLabelingType.GENERAL, params);
        return params;
    }

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public LoginWhiteLabelingParams saveSystemLoginWhiteLabelingParams(LoginWhiteLabelingParams params) {
        saveWl(TenantId.SYS_TENANT_ID, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.LOGIN, params);
        return params;
    }

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public LoginWhiteLabelingParams saveTenantLoginWhiteLabelingParams(TenantId tenantId, LoginWhiteLabelingParams params) {
        saveWl(tenantId, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.LOGIN, params);
        return params;
    }

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public LoginWhiteLabelingParams saveCustomerLoginWhiteLabelingParams(TenantId tenantId, CustomerId customerId, LoginWhiteLabelingParams params) {
        saveWl(tenantId, customerId, WhiteLabelingType.LOGIN, params);
        return params;
    }

    // --- Preview (merge without save) ---

    @Override
    public WhiteLabelingParams mergeSystemWhiteLabelingParams(WhiteLabelingParams params) {
        WhiteLabelingParams base = WhiteLabelingParams.defaultParams();
        WhiteLabelingParams merged = new WhiteLabelingParams(params);
        merged.merge(base);
        return merged;
    }

    @Override
    public WhiteLabelingParams mergeTenantWhiteLabelingParams(TenantId tenantId, WhiteLabelingParams params) {
        WhiteLabelingParams system = getSystemWhiteLabelingParams();
        WhiteLabelingParams base = system != null ? new WhiteLabelingParams(system) : WhiteLabelingParams.defaultParams();
        WhiteLabelingParams merged = new WhiteLabelingParams(params);
        merged.merge(base);
        return merged;
    }

    @Override
    public WhiteLabelingParams mergeCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId, WhiteLabelingParams params) {
        WhiteLabelingParams tenantMerged = getMergedTenantWhiteLabelingParams(tenantId);
        WhiteLabelingParams merged = new WhiteLabelingParams(params);
        merged.merge(tenantMerged);
        return merged;
    }

    // --- Delete ---

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public void deleteWhiteLabeling(TenantId tenantId, CustomerId customerId, WhiteLabelingType type) {
        WhiteLabelingCompositeKey key;
        if (tenantId.isSysTenantId()) {
            key = WhiteLabelingCompositeKey.forSystem(type);
        } else if (customerId != null && !customerId.isNullUid()) {
            key = WhiteLabelingCompositeKey.forCustomer(tenantId, customerId, type);
        } else {
            key = WhiteLabelingCompositeKey.forTenant(tenantId, type);
        }
        whiteLabelingDao.removeByCompositeKey(key);
    }

    @Override
    @CacheEvict(cacheNames = CacheConstants.WHITE_LABELING_CACHE, allEntries = true)
    public void deleteAllTenantWhiteLabeling(TenantId tenantId) {
        whiteLabelingDao.deleteByTenantId(tenantId);
    }

    // --- Permission helpers (always true, no license gating) ---

    @Override
    public boolean isWhiteLabelingAllowed(TenantId tenantId, CustomerId customerId) {
        return true;
    }

    @Override
    public boolean isCustomerWhiteLabelingAllowed(TenantId tenantId) {
        return true;
    }

    // --- Private helpers ---

    private <T> T loadParams(WhiteLabelingCompositeKey key, Class<T> clazz) {
        WhiteLabeling wl = whiteLabelingDao.findByCompositeKey(key);
        if (wl == null || wl.getSettings() == null) {
            return null;
        }
        try {
            return MAPPER.treeToValue(wl.getSettings(), clazz);
        } catch (Exception e) {
            log.error("Failed to deserialize white-labeling params for key {}", key, e);
            return null;
        }
    }

    private void saveWl(TenantId tenantId, CustomerId customerId, WhiteLabelingType type, Object params) {
        try {
            JsonNode settingsNode = MAPPER.valueToTree(params);
            WhiteLabeling wl = WhiteLabeling.builder()
                    .tenantId(tenantId)
                    .customerId(customerId)
                    .type(type)
                    .settings(settingsNode)
                    .build();
            whiteLabelingDao.save(wl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save white-labeling params", e);
        }
    }
}
