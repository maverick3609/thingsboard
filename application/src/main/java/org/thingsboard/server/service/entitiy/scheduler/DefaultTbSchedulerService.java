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
package org.thingsboard.server.service.entitiy.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.dao.scheduler.SchedulerEventService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.AbstractTbEntityService;

@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class DefaultTbSchedulerService extends AbstractTbEntityService implements TbSchedulerService {

    private final SchedulerEventService schedulerEventService;

    @Override
    public SchedulerEvent save(SchedulerEvent schedulerEvent, User user) throws ThingsboardException {
        ActionType actionType = schedulerEvent.getId() == null ? ActionType.ADDED : ActionType.UPDATED;
        log.debug("[{}] Saving scheduler event [{}]", user.getTenantId(), schedulerEvent.getName());
        try {
            SchedulerEvent savedSchedulerEvent = checkNotNull(schedulerEventService.saveSchedulerEvent(schedulerEvent));
            logEntityActionService.logEntityAction(user.getTenantId(), savedSchedulerEvent.getId(), savedSchedulerEvent,
                    savedSchedulerEvent.getCustomerId(), actionType, user);
            log.info("[{}][{}] Saved scheduler event [{}]", user.getTenantId(), savedSchedulerEvent.getId(), savedSchedulerEvent.getName());
            return savedSchedulerEvent;
        } catch (Exception e) {
            logEntityActionService.logEntityAction(user.getTenantId(), emptyId(EntityType.SCHEDULER_EVENT), (HasName) schedulerEvent,
                    actionType, user, e);
            throw e;
        }
    }

    @Override
    public void delete(SchedulerEvent schedulerEvent, User user) throws ThingsboardException {
        ActionType actionType = ActionType.DELETED;
        SchedulerEventId schedulerEventId = schedulerEvent.getId();
        log.debug("[{}][{}] Deleting scheduler event [{}]", user.getTenantId(), schedulerEventId, schedulerEvent.getName());
        try {
            schedulerEventService.deleteSchedulerEvent(user.getTenantId(), schedulerEventId);
            logEntityActionService.logEntityAction(user.getTenantId(), (EntityId) schedulerEventId, schedulerEvent,
                    schedulerEvent.getCustomerId(), actionType, user, schedulerEventId.getId());
            log.info("[{}][{}] Deleted scheduler event [{}]", user.getTenantId(), schedulerEventId, schedulerEvent.getName());
        } catch (Exception e) {
            logEntityActionService.logEntityAction(user.getTenantId(), emptyId(EntityType.SCHEDULER_EVENT), actionType, user, e,
                    schedulerEventId.getId());
            throw e;
        }
    }

}
