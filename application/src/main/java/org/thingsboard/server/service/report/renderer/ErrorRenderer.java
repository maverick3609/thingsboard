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
package org.thingsboard.server.service.report.renderer;

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.report.configuration.components.ErrorComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.HashMap;

/**
 * Renders an {@code ERROR} component (PE {@code report.renderer.ErrorRenderer}) via {@code
 * html/components/error-template} — an error box with the message and, when present, the unwrapped root
 * exception detail. Used as the engine's per-component fallback: when a component renderer throws an
 * unexpected error, {@code PdfReportService} swallows it and emits an {@link ErrorComponent} here so the
 * rest of the report still renders.
 */
@Component
public class ErrorRenderer implements PdfReportComponentRenderer<ErrorComponent> {

    @Override
    public String render(ErrorComponent component, ComponentData componentData) {
        HashMap<String, Object> componentVariables = new HashMap<>();
        componentVariables.put("errorMessage", component.getErrorMessage());
        Exception exception = extractRootException(component.getException());
        if (exception != null) {
            componentVariables.put("exception", formatExceptionMessage(exception));
        }
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/error-template", componentVariables);
    }

    private Exception extractRootException(Exception exception) {
        if (exception instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof Exception cause) {
            return cause;
        }
        return exception;
    }

    private String formatExceptionMessage(Exception exception) {
        if (exception instanceof ThingsboardException tbException) {
            return "[" + tbException.getErrorCode().name() + "] " + tbException.getMessage();
        }
        return exception.getMessage();
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ERROR;
    }
}
