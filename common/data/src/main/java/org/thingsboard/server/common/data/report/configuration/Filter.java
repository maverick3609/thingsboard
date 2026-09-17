// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.query.KeyFilter;

import java.util.List;

@Schema
@Data
@NoArgsConstructor
public class Filter {

    private String id;
    private String filter;
    private List<KeyFilter> keyFilters;
}
