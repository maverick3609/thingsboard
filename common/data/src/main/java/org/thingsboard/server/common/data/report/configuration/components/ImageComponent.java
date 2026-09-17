// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ImageComponent extends AbstractImageComponent {

    private ImageSourceType sourceType;
    private String imageUrl;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.IMAGE;
    }
}
