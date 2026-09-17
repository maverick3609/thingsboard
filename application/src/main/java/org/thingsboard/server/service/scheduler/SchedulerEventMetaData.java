// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.scheduler;

import com.google.common.util.concurrent.ListenableScheduledFuture;
import lombok.Data;
import org.thingsboard.server.common.data.scheduler.SchedulerEventDescriptor;

@Data
class SchedulerEventMetaData {

    private final SchedulerEventDescriptor descriptor;
    private volatile ListenableScheduledFuture<?> nextTaskFuture;

}
