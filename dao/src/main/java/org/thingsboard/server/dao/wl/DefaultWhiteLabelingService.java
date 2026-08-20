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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.LoginWhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabeling;
import org.thingsboard.server.common.data.wl.WhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabelingType;
import org.thingsboard.server.dao.entity.AbstractCachedService;
import org.thingsboard.server.dao.model.sql.WhiteLabelingCompositeKey;

@Service
@Slf4j
public class DefaultWhiteLabelingService
    extends AbstractCachedService<WhiteLabelingCacheKey, WhiteLabeling, WhiteLabelingEvictEvent>
    implements WhiteLabelingService {

    private final WhiteLabelingDao whiteLabelingDao;

    public DefaultWhiteLabelingService(WhiteLabelingDao whiteLabelingDao) {
        this.whiteLabelingDao = whiteLabelingDao;
    }

    // --- Cache event handling ---

    @TransactionalEventListener(classes = WhiteLabelingEvictEvent.class)
    @Override
    public void handleEvictEvent(WhiteLabelingEvictEvent event) {
        if (event.getKey() != null) {
            cache.evict(event.getKey());
        }
    }

    // --- Raw getters (cached via transactional cache) ---

    @Override
    public WhiteLabelingParams getSystemWhiteLabelingParams() {
        return loadParamsCached(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.GENERAL),
                WhiteLabelingParams.class);
    }

    @Override
    public WhiteLabelingParams getTenantWhiteLabelingParams(TenantId tenantId) {
        return loadParamsCached(WhiteLabelingCompositeKey.forTenant(tenantId, WhiteLabelingType.GENERAL),
                WhiteLabelingParams.class);
    }

    @Override
    public WhiteLabelingParams getCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId) {
        return loadParamsCached(WhiteLabelingCompositeKey.forCustomer(tenantId, customerId, WhiteLabelingType.GENERAL),
                WhiteLabelingParams.class);
    }

    @Override
    public LoginWhiteLabelingParams getSystemLoginWhiteLabelingParams() {
        return loadParamsCached(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.LOGIN),
                LoginWhiteLabelingParams.class);
    }

    @Override
    public LoginWhiteLabelingParams getTenantLoginWhiteLabelingParams(TenantId tenantId) {
        return loadParamsCached(WhiteLabelingCompositeKey.forTenant(tenantId, WhiteLabelingType.LOGIN),
                LoginWhiteLabelingParams.class);
    }

    @Override
    public LoginWhiteLabelingParams getCustomerLoginWhiteLabelingParams(TenantId tenantId, CustomerId customerId) {
        return loadParamsCached(WhiteLabelingCompositeKey.forCustomer(tenantId, customerId, WhiteLabelingType.LOGIN),
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
        // Login-specific values win, but general system branding (logo, title,
        // favicon, palette) must reach the pre-auth login page too.
        WhiteLabelingParams general = getSystemWhiteLabelingParams();
        if (general != null) {
            base.merge(general);
        }
        return base;
    }

    // --- Save ---

    @Override
    public WhiteLabelingParams saveSystemWhiteLabelingParams(WhiteLabelingParams params) {
        saveWlAndEvict(TenantId.SYS_TENANT_ID, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.GENERAL, params);
        return params;
    }

    @Override
    public WhiteLabelingParams saveTenantWhiteLabelingParams(TenantId tenantId, WhiteLabelingParams params) {
        saveWlAndEvict(tenantId, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.GENERAL, params);
        return params;
    }

    @Override
    public WhiteLabelingParams saveCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId, WhiteLabelingParams params) {
        saveWlAndEvict(tenantId, customerId, WhiteLabelingType.GENERAL, params);
        return params;
    }

    @Override
    public LoginWhiteLabelingParams saveSystemLoginWhiteLabelingParams(LoginWhiteLabelingParams params) {
        saveWlAndEvict(TenantId.SYS_TENANT_ID, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.LOGIN, params);
        return params;
    }

    @Override
    public LoginWhiteLabelingParams saveTenantLoginWhiteLabelingParams(TenantId tenantId, LoginWhiteLabelingParams params) {
        saveWlAndEvict(tenantId, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.LOGIN, params);
        return params;
    }

    @Override
    public LoginWhiteLabelingParams saveCustomerLoginWhiteLabelingParams(TenantId tenantId, CustomerId customerId, LoginWhiteLabelingParams params) {
        saveWlAndEvict(tenantId, customerId, WhiteLabelingType.LOGIN, params);
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

    // --- Privacy Policy & Terms of Use ---

    @Override
    public JsonNode getTenantPrivacyPolicy(TenantId tenantId) {
        return loadSettingsCached(WhiteLabelingCompositeKey.forTenant(tenantId, WhiteLabelingType.PRIVACY_POLICY));
    }

    @Override
    public JsonNode getWebPrivacyPolicy(String serverName) {
        JsonNode system = loadSettingsCached(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.PRIVACY_POLICY));
        return system;
    }

    @Override
    public JsonNode savePrivacyPolicy(TenantId tenantId, JsonNode policy) {
        saveRawSettingsAndEvict(tenantId, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.PRIVACY_POLICY, policy);
        return policy;
    }

    @Override
    public JsonNode getTenantTermsOfUse(TenantId tenantId) {
        return loadSettingsCached(WhiteLabelingCompositeKey.forTenant(tenantId, WhiteLabelingType.TERMS_OF_USE));
    }

    @Override
    public JsonNode getWebTermsOfUse(String serverName) {
        JsonNode system = loadSettingsCached(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.TERMS_OF_USE));
        return system;
    }

    @Override
    public JsonNode saveTermsOfUse(TenantId tenantId, JsonNode terms) {
        saveRawSettingsAndEvict(tenantId, new CustomerId(EntityId.NULL_UUID), WhiteLabelingType.TERMS_OF_USE, terms);
        return terms;
    }

    // --- Mail templates ---

    @Override
    public JsonNode getSystemMailTemplates() {
        return loadSettingsCached(WhiteLabelingCompositeKey.forSystem(WhiteLabelingType.MAIL_TEMPLATES));
    }

    @Override
    public JsonNode saveSystemMailTemplates(JsonNode templates) {
        saveRawSettingsAndEvict(TenantId.SYS_TENANT_ID, new CustomerId(EntityId.NULL_UUID),
                WhiteLabelingType.MAIL_TEMPLATES, templates);
        return templates;
    }

    // --- Delete ---

    @Override
    public void deleteWhiteLabeling(TenantId tenantId, CustomerId customerId, WhiteLabelingType type) {
        WhiteLabelingCompositeKey key = resolveCompositeKey(tenantId, customerId, type);
        whiteLabelingDao.removeByCompositeKey(key);
        publishEvictEvent(new WhiteLabelingEvictEvent(WhiteLabelingCacheKey.forKey(key)));
    }

    @Override
    public void deleteAllTenantWhiteLabeling(TenantId tenantId) {
        whiteLabelingDao.deleteByTenantId(tenantId);
        for (WhiteLabelingType type : WhiteLabelingType.values()) {
            WhiteLabelingCompositeKey ck = WhiteLabelingCompositeKey.forTenant(tenantId, type);
            publishEvictEvent(new WhiteLabelingEvictEvent(WhiteLabelingCacheKey.forKey(ck)));
        }
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

    private <T> T loadParamsCached(WhiteLabelingCompositeKey compositeKey, Class<T> clazz) {
        WhiteLabelingCacheKey cacheKey = WhiteLabelingCacheKey.forKey(compositeKey);
        WhiteLabeling wl = cache.getAndPutInTransaction(cacheKey,
                () -> whiteLabelingDao.findByCompositeKey(compositeKey), false);
        if (wl == null || wl.getSettings() == null) {
            return null;
        }
        try {
            return JacksonUtil.treeToValue(wl.getSettings(), clazz);
        } catch (Exception e) {
            log.error("Failed to deserialize white-labeling params for key {}", compositeKey, e);
            return null;
        }
    }

    private JsonNode loadSettingsCached(WhiteLabelingCompositeKey compositeKey) {
        WhiteLabelingCacheKey cacheKey = WhiteLabelingCacheKey.forKey(compositeKey);
        WhiteLabeling wl = cache.getAndPutInTransaction(cacheKey,
                () -> whiteLabelingDao.findByCompositeKey(compositeKey), false);
        return wl != null ? wl.getSettings() : null;
    }

    private void saveWlAndEvict(TenantId tenantId, CustomerId customerId, WhiteLabelingType type, Object params) {
        try {
            JsonNode settingsNode = JacksonUtil.valueToTree(params);
            saveRawSettingsAndEvict(tenantId, customerId, type, settingsNode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save white-labeling params", e);
        }
    }

    private void saveRawSettingsAndEvict(TenantId tenantId, CustomerId customerId, WhiteLabelingType type, JsonNode settings) {
        WhiteLabelingCompositeKey compositeKey = new WhiteLabelingCompositeKey(
                tenantId.getId(), customerId.getId(), type);
        WhiteLabelingEvictEvent evictEvent = new WhiteLabelingEvictEvent(WhiteLabelingCacheKey.forKey(compositeKey));
        try {
            WhiteLabeling wl = WhiteLabeling.builder()
                    .tenantId(tenantId)
                    .customerId(customerId)
                    .type(type)
                    .settings(settings)
                    .build();
            whiteLabelingDao.save(wl);
            publishEvictEvent(evictEvent);
        } catch (Exception e) {
            handleEvictEvent(evictEvent);
            throw e;
        }
    }

    private WhiteLabelingCompositeKey resolveCompositeKey(TenantId tenantId, CustomerId customerId, WhiteLabelingType type) {
        if (tenantId.isSysTenantId()) {
            return WhiteLabelingCompositeKey.forSystem(type);
        } else if (customerId != null && !customerId.isNullUid()) {
            return WhiteLabelingCompositeKey.forCustomer(tenantId, customerId, type);
        } else {
            return WhiteLabelingCompositeKey.forTenant(tenantId, type);
        }
    }
}
