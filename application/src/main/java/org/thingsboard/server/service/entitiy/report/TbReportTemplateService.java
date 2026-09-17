// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.entitiy.report;

import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.report.ReportTemplate;

public interface TbReportTemplateService {

    ReportTemplate save(ReportTemplate reportTemplate, User user) throws ThingsboardException;

    void delete(ReportTemplate reportTemplate, User user) throws ThingsboardException;

}
