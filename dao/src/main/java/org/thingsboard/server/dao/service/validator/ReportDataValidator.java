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
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.exception.DataValidationException;

@Component
@Slf4j
public class ReportDataValidator extends DataValidator<Report> {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private CustomerDao customerDao;

    @Override
    protected void validateDataImpl(TenantId tenantId, Report report) {
        log.debug("Executing validateDataImpl, tenantId [{}]", tenantId);
        // name/format/tenantId/userId are @NotNull/@NotBlank on Report itself and are already
        // enforced by ConstraintValidator.validateFields() before this method runs (see
        // DataValidator#validate) - re-checking them here would be unreachable dead code.
        if (!tenantService.tenantExists(tenantId)) {
            log.warn("Report is referencing to non-existent tenant [{}]", tenantId);
            throw new DataValidationException("Report is referencing to non-existent tenant!");
        }
        if (report.getCustomerId() == null) {
            report.setCustomerId(new CustomerId(ModelConstants.NULL_UUID));
        } else if (!report.getCustomerId().getId().equals(ModelConstants.NULL_UUID)) {
            Customer customer = customerDao.findById(tenantId, report.getCustomerId().getId());
            if (customer == null) {
                log.warn("Can't assign report to non-existent customer, tenantId [{}], customerId [{}]", tenantId, report.getCustomerId());
                throw new DataValidationException("Can't assign report to non-existent customer!");
            }
            if (!customer.getTenantId().equals(report.getTenantId())) {
                log.warn("Can't assign report to customer from different tenant, tenantId [{}], customerId [{}]", tenantId, report.getCustomerId());
                throw new DataValidationException("Can't assign report to customer from different tenant!");
            }
        }
    }

    @Override
    protected Report validateUpdate(TenantId tenantId, Report report) {
        log.warn("Attempt to update immutable report, tenantId [{}], id [{}]", tenantId, report.getId());
        throw new DataValidationException("Report can't be updated");
    }

    /**
     * Size-guard hook, called explicitly by {@code DefaultReportService#createReport} (the byte
     * payload isn't part of {@link Report}, so it can't flow through {@link #validate}).
     *
     * R2: report-size limit (ApiUsageState.isReportCreationEnabled / max size) — stubbed
     * permissive in R1 per spec §15 #3. PE gates this on
     * {@code DefaultTenantProfileConfiguration.getMaxReportSizeInBytes()}; that profile field
     * does not exist on this fork yet, so no size check is enforced here.
     */
    public void validateReportSize(TenantId tenantId, byte[] data) {
        log.debug("Executing validateReportSize, tenantId [{}]", tenantId);
    }

}
