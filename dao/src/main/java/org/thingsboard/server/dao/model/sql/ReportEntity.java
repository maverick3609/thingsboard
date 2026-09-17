// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.model.sql;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.report.Report;

import static org.thingsboard.server.dao.model.ModelConstants.REPORT_TABLE_NAME;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = REPORT_TABLE_NAME)
public final class ReportEntity extends AbstractReportEntity<Report> {

    public ReportEntity() {
        super();
    }

    public ReportEntity(Report report) {
        super(report);
    }

    public Report toData() {
        return toReport();
    }

}
