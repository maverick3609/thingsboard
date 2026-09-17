// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.wl;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.WhiteLabeling;
import org.thingsboard.server.dao.TenantEntityDao;
import org.thingsboard.server.dao.model.sql.WhiteLabelingCompositeKey;

public interface WhiteLabelingDao extends TenantEntityDao<WhiteLabeling> {

    WhiteLabeling save(WhiteLabeling whiteLabeling);

    WhiteLabeling findByCompositeKey(WhiteLabelingCompositeKey key);

    boolean removeByCompositeKey(WhiteLabelingCompositeKey key);

    void deleteByTenantId(TenantId tenantId);
}
