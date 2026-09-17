// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.thingsboard.server.common.data.id.CustomerId;

@Data
@SuperBuilder
public class SchedulerEventFilter {

    private final CustomerId customerId;
    private final String type;

}
