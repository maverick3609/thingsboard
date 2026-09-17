// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.wl;

import lombok.Data;
import org.thingsboard.server.dao.model.sql.WhiteLabelingCompositeKey;

import java.io.Serializable;

@Data
public class WhiteLabelingCacheKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private final WhiteLabelingCompositeKey compositeKey;

    public static WhiteLabelingCacheKey forKey(WhiteLabelingCompositeKey key) {
        return new WhiteLabelingCacheKey(key);
    }

    @Override
    public String toString() {
        return compositeKey.getTenantId() + ":" + compositeKey.getCustomerId() + ":" + compositeKey.getType();
    }
}
