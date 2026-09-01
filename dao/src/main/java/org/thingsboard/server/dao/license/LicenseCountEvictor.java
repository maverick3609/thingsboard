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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;

/**
 * Evicts the install-wide licensed-entity count cache whenever a device or asset is saved or deleted,
 * ignoring which tenant changed.
 * <p>
 * Listens to {@link SaveEntityEvent}/{@link DeleteEntityEvent} rather than TB's own (package-private)
 * {@code EntityCountCacheEvictEvent}, and deliberately with {@code fallbackExecution = true}. Device creates
 * run inside a transaction ({@code DeviceServiceImpl.saveDevice} is {@code @Transactional}), but asset
 * creates do not -- neither {@code BaseAssetService.saveAsset} nor its {@code DefaultTbAssetService} caller
 * opens one. {@code AbstractCachedEntityService.publishEvictEvent}'s no-transaction fallback calls
 * {@code handleEvictEvent} as a direct self-invocation rather than through the event publisher, which is
 * harmless for TB's own single listener but means a second, independent listener on that event -- this one
 * -- would silently never run for asset creates specifically. Confirmed by a failing test before this design
 * was adopted, not by inspection alone. {@code SaveEntityEvent}/{@code DeleteEntityEvent} are published
 * unconditionally either way, and {@code fallbackExecution = true} is the same pattern already used for this
 * exact reason by {@code EntityStateSourcingListener} and {@code EdqsListener}.
 * <p>
 * {@code onSave} evicts on every save, not only on {@code SaveEntityEvent.getCreated() == true}: an update
 * evicting the count costs one harmless extra query on the next read, while trusting every current and
 * future DEVICE/ASSET publisher to set {@code created} correctly is a correctness dependency this class does
 * not need to take on, for a saving that does not matter.
 */
@Component
@RequiredArgsConstructor
public class LicenseCountEvictor {

    private final TbTransactionalCache<LicenseCountCacheKey, Long> cache;

    @TransactionalEventListener(fallbackExecution = true)
    public void onSave(SaveEntityEvent<?> event) {
        evictIfLicensed(event.getEntityId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onDelete(DeleteEntityEvent<?> event) {
        evictIfLicensed(event.getEntityId());
    }

    private void evictIfLicensed(EntityId entityId) {
        EntityType type = entityId.getEntityType();
        if (type == EntityType.DEVICE || type == EntityType.ASSET) {
            cache.evict(new LicenseCountCacheKey(type));
        }
    }
}
