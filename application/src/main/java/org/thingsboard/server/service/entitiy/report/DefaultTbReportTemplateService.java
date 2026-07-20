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
package org.thingsboard.server.service.entitiy.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.AbstractTbEntityService;

@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class DefaultTbReportTemplateService extends AbstractTbEntityService implements TbReportTemplateService {

    private final ReportTemplateService reportTemplateService;

    @Override
    public ReportTemplate save(ReportTemplate reportTemplate, User user) throws ThingsboardException {
        ActionType actionType = reportTemplate.getId() == null ? ActionType.ADDED : ActionType.UPDATED;
        log.debug("[{}] Saving report template [{}]", user.getTenantId(), reportTemplate.getName());
        try {
            ReportTemplate savedReportTemplate = checkNotNull(reportTemplateService.saveReportTemplate(reportTemplate));
            logEntityActionService.logEntityAction(user.getTenantId(), savedReportTemplate.getId(), savedReportTemplate,
                    savedReportTemplate.getCustomerId(), actionType, user);
            log.info("[{}][{}] Saved report template [{}]", user.getTenantId(), savedReportTemplate.getId(), savedReportTemplate.getName());
            return savedReportTemplate;
        } catch (Exception e) {
            logEntityActionService.logEntityAction(user.getTenantId(), emptyId(EntityType.REPORT_TEMPLATE), (HasName) reportTemplate,
                    actionType, user, e);
            throw e;
        }
    }

    @Override
    public void delete(ReportTemplate reportTemplate, User user) throws ThingsboardException {
        ActionType actionType = ActionType.DELETED;
        ReportTemplateId reportTemplateId = reportTemplate.getId();
        log.debug("[{}][{}] Deleting report template [{}]", user.getTenantId(), reportTemplateId, reportTemplate.getName());
        try {
            reportTemplateService.deleteReportTemplate(user.getTenantId(), reportTemplateId);
            logEntityActionService.logEntityAction(user.getTenantId(), (EntityId) reportTemplateId, reportTemplate,
                    reportTemplate.getCustomerId(), actionType, user, reportTemplateId.getId());
            log.info("[{}][{}] Deleted report template [{}]", user.getTenantId(), reportTemplateId, reportTemplate.getName());
        } catch (Exception e) {
            logEntityActionService.logEntityAction(user.getTenantId(), emptyId(EntityType.REPORT_TEMPLATE), actionType, user, e,
                    reportTemplateId.getId());
            throw e;
        }
    }

}
