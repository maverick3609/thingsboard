// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.wl;

import com.fasterxml.jackson.databind.JsonNode;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.LoginWhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabelingParams;
import org.thingsboard.server.common.data.wl.WhiteLabelingType;

public interface WhiteLabelingService {

    // Merged (runtime consumption)
    WhiteLabelingParams getMergedTenantWhiteLabelingParams(TenantId tenantId);
    WhiteLabelingParams getMergedCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId);
    LoginWhiteLabelingParams getMergedLoginWhiteLabelingParams(String serverName);

    // Raw (editing)
    WhiteLabelingParams getSystemWhiteLabelingParams();
    WhiteLabelingParams getTenantWhiteLabelingParams(TenantId tenantId);
    WhiteLabelingParams getCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId);
    LoginWhiteLabelingParams getSystemLoginWhiteLabelingParams();
    LoginWhiteLabelingParams getTenantLoginWhiteLabelingParams(TenantId tenantId);
    LoginWhiteLabelingParams getCustomerLoginWhiteLabelingParams(TenantId tenantId, CustomerId customerId);

    // Save
    WhiteLabelingParams saveSystemWhiteLabelingParams(WhiteLabelingParams params);
    WhiteLabelingParams saveTenantWhiteLabelingParams(TenantId tenantId, WhiteLabelingParams params);
    WhiteLabelingParams saveCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId, WhiteLabelingParams params);
    LoginWhiteLabelingParams saveSystemLoginWhiteLabelingParams(LoginWhiteLabelingParams params);
    LoginWhiteLabelingParams saveTenantLoginWhiteLabelingParams(TenantId tenantId, LoginWhiteLabelingParams params);
    LoginWhiteLabelingParams saveCustomerLoginWhiteLabelingParams(TenantId tenantId, CustomerId customerId, LoginWhiteLabelingParams params);

    // Preview
    WhiteLabelingParams mergeSystemWhiteLabelingParams(WhiteLabelingParams params);
    WhiteLabelingParams mergeTenantWhiteLabelingParams(TenantId tenantId, WhiteLabelingParams params);
    WhiteLabelingParams mergeCustomerWhiteLabelingParams(TenantId tenantId, CustomerId customerId, WhiteLabelingParams params);

    // Privacy Policy & Terms of Use
    JsonNode getTenantPrivacyPolicy(TenantId tenantId);
    JsonNode getWebPrivacyPolicy(String serverName);
    JsonNode savePrivacyPolicy(TenantId tenantId, JsonNode policy);
    JsonNode getTenantTermsOfUse(TenantId tenantId);
    JsonNode getWebTermsOfUse(String serverName);
    JsonNode saveTermsOfUse(TenantId tenantId, JsonNode terms);

    // Mail templates (system scope only - the mail service has no tenant context at send time)
    JsonNode getSystemMailTemplates();
    JsonNode saveSystemMailTemplates(JsonNode templates);

    // Delete
    void deleteWhiteLabeling(TenantId tenantId, CustomerId customerId, WhiteLabelingType type);
    void deleteAllTenantWhiteLabeling(TenantId tenantId);

    // Permission helpers (always true)
    boolean isWhiteLabelingAllowed(TenantId tenantId, CustomerId customerId);
    boolean isCustomerWhiteLabelingAllowed(TenantId tenantId);
}
