// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.query.EntityFilter;

@Schema
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityAlias {

    private String id;
    private String alias;
    private EntityFilter filter;
}
