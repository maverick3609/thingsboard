// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.SchedulerEventId;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerEventTest {

    @Test
    void entityIdFactory_createsSchedulerEventId() {
        UUID uuid = UUID.randomUUID();
        EntityId id = EntityIdFactory.getByTypeAndUuid(EntityType.SCHEDULER_EVENT, uuid);
        assertThat(id).isInstanceOf(SchedulerEventId.class);
        assertThat(id.getId()).isEqualTo(uuid);
        assertThat(id.getEntityType()).isEqualTo(EntityType.SCHEDULER_EVENT);
    }

    @Test
    void entityType_hasPeProtoNumber() {
        assertThat(EntityType.SCHEDULER_EVENT.getProtoNumber()).isEqualTo(103);
    }

    @Test
    void schedulerEvent_jsonRoundTrip_and_toDescriptor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
            {
              "name": "Nightly sync",
              "type": "updateAttributes",
              "enabled": true,
              "schedule": {"timezone": "UTC", "startTime": 1780000000000, "repeat": {"type": "DAILY", "endsOn": 1790000000000}},
              "configuration": {"originatorId": null, "msgType": null, "msgBody": {"a": 1}, "metadata": null}
            }""";
        SchedulerEvent event = mapper.readValue(json, SchedulerEvent.class);
        assertThat(event.getName()).isEqualTo("Nightly sync");
        assertThat(event.isEnabled()).isTrue();
        assertThat(event.getConfiguration().get("msgBody").get("a").asInt()).isEqualTo(1);

        SchedulerEventDescriptor d = event.toDescriptor();
        assertThat(d.startTime()).isEqualTo(1780000000000L);
        assertThat(d.timezone()).isEqualTo("UTC");
        assertThat(d.repeat()).isInstanceOf(DailyRepeat.class);

        String out = mapper.writeValueAsString(event);
        SchedulerEvent back = mapper.readValue(out, SchedulerEvent.class);
        assertThat(back.getConfiguration()).isEqualTo(event.getConfiguration());
    }
}
