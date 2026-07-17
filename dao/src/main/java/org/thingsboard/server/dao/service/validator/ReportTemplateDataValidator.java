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
package org.thingsboard.server.dao.service.validator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.exception.DataValidationException;

@Component
@Slf4j
public class ReportTemplateDataValidator extends DataValidator<ReportTemplate> {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private CustomerDao customerDao;

    @Override
    protected void validateCreate(TenantId tenantId, ReportTemplate data) {
        log.debug("Executing validateCreate, tenantId [{}]", tenantId);
        validateNumberOfEntitiesPerTenant(tenantId, EntityType.REPORT_TEMPLATE);
    }

    @Override
    protected void validateDataImpl(TenantId tenantId, ReportTemplate reportTemplate) {
        log.debug("Executing validateDataImpl, tenantId [{}]", tenantId);
        if (StringUtils.isEmpty(reportTemplate.getName())) {
            log.warn("Report template name should be specified, tenantId [{}]", tenantId);
            throw new DataValidationException("Report template name should be specified!");
        }
        if (reportTemplate.getFormat() == null) {
            log.warn("Report template format should be specified, tenantId [{}], name [{}]", tenantId, reportTemplate.getName());
            throw new DataValidationException("Report template format should be specified!");
        }
        if (reportTemplate.getType() == null) {
            log.warn("Report template type should be specified, tenantId [{}], name [{}]", tenantId, reportTemplate.getName());
            throw new DataValidationException("Report template type should be specified!");
        }
        if (reportTemplate.getTenantId() == null) {
            log.warn("Report template should be assigned to tenant, name [{}]", reportTemplate.getName());
            throw new DataValidationException("Report template should be assigned to tenant!");
        }
        if (!tenantService.tenantExists(reportTemplate.getTenantId())) {
            log.warn("Report template is referencing to non-existent tenant [{}]", tenantId);
            throw new DataValidationException("Report template is referencing to non-existent tenant!");
        }
        if (reportTemplate.getCustomerId() == null) {
            reportTemplate.setCustomerId(new CustomerId(ModelConstants.NULL_UUID));
        } else if (!reportTemplate.getCustomerId().getId().equals(ModelConstants.NULL_UUID)) {
            Customer customer = customerDao.findById(tenantId, reportTemplate.getCustomerId().getId());
            if (customer == null) {
                log.warn("Can't assign report template to non-existent customer, tenantId [{}], customerId [{}]", tenantId, reportTemplate.getCustomerId());
                throw new DataValidationException("Can't assign report template to non-existent customer!");
            }
            if (!customer.getTenantId().equals(reportTemplate.getTenantId())) {
                log.warn("Can't assign report template to customer from different tenant, tenantId [{}], customerId [{}]", tenantId, reportTemplate.getCustomerId());
                throw new DataValidationException("Can't assign report template to customer from different tenant!");
            }
        }
    }

}
