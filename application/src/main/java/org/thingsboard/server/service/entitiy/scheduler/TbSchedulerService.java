// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.entitiy.scheduler;

import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;

public interface TbSchedulerService {

    SchedulerEvent save(SchedulerEvent schedulerEvent, User user) throws ThingsboardException;

    void delete(SchedulerEvent schedulerEvent, User user) throws ThingsboardException;

}
