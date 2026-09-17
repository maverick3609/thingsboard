// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.scheduler;

import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.dao.Dao;
import org.thingsboard.server.dao.ExportableEntityDao;
import org.thingsboard.server.dao.TenantEntityDao;

public interface SchedulerEventDao extends Dao<SchedulerEvent>, TenantEntityDao<SchedulerEvent>, ExportableEntityDao<SchedulerEventId, SchedulerEvent> {
}
