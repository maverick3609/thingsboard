// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.scheduler;

import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.msg.queue.TbCallback;
import org.thingsboard.server.gen.transport.TransportProtos;

public interface SchedulerService {

    void onSchedulerEventAdded(SchedulerEventInfo schedulerEventInfo);

    void onSchedulerEventUpdated(SchedulerEventInfo schedulerEventInfo);

    void onSchedulerEventDeleted(SchedulerEventInfo schedulerEventInfo);

    void onQueueMsg(TransportProtos.SchedulerServiceMsgProto msg, TbCallback callback);
}
