// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Render output DTO: the bridge between a report renderer and the DAO. Not persisted as-is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportData {

    private byte[] data;
    private String name;
    private String contentType;

}
