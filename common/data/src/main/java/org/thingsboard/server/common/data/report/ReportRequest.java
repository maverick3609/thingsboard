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
package org.thingsboard.server.common.data.report;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportTemplateId;

import java.util.List;
import java.util.UUID;

/**
 * {@code /api/v2/report/{test,request}} body (design spec §2.3/§10). Unlike PE,
 * {@code reportTemplateConfig} is a raw {@link JsonNode} tree (R1 passthrough) rather than a
 * typed {@code ReportTemplateConfig} bean — see the reporting design spec §2.4.
 * {@code userId} is a plain, nullable {@link String} that falls back to the calling user when
 * absent — PE's compiled signature, verified via {@code javap} against the reference jar.
 */
@Data
public class ReportRequest {

    private ReportTemplateId reportTemplateId;
    private JsonNode reportTemplateConfig;
    private String timezone;
    private String userId;
    private EntityId originator;
    private List<UUID> targets;
    private NotificationTemplateId notificationTemplateId;

}
