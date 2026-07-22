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
package org.thingsboard.server.service.report;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.HeaderFooter;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.ErrorComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.style.Insets;
import org.thingsboard.server.common.data.report.configuration.style.PageOrientation;
import org.thingsboard.server.common.data.report.configuration.style.PageSize;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.context.HeaderFooterRenderLayout;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.render.ReportRenderException;
import org.thingsboard.server.service.report.render.ReportUtils;
import org.thingsboard.server.service.report.renderer.PdfReportComponentRenderer;
import org.thingsboard.server.service.report.util.ColorUtils;
import org.thingsboard.server.service.report.util.HtmlRenderUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The R2a HTML→PDF render engine (PE {@code report.service.PdfReportService}, adapted). Turns one
 * {@link ReportTask}'s typed {@link PdfReportTemplateConfig} into a PDF entirely server-side: it
 * dispatches each {@link ReportComponent} to the matching {@link PdfReportComponentRenderer}, collects the
 * HTML fragments, measures the running header/footer, assembles everything into {@code html/report-template}
 * (page size/orientation/margins/background from the config), and rasterises via flying-saucer + openpdf
 * (C4 plumbing, {@link HtmlRenderUtils#createRenderer}).
 * <p>
 * <b>R2a scope:</b> the non-data components — HEADING, RICH_TEXT, DIVIDER, PAGE_BREAK, IMAGE, DASHBOARD.
 * The DASHBOARD component reuses R1's Playwright path: {@link #buildDashboardComponentData} captures the
 * dashboard as a PNG through {@link DashboardReportService} and the {@link
 * org.thingsboard.server.service.report.renderer.DashboardRenderer} base64-embeds it — so R1's
 * dashboard reports keep working through the new engine. Table components (entity/alarm/time-series) and
 * sub-reports need the server-side data layer and are guarded as <b>R2b</b>.
 * <p>
 * Deliberately <b>not</b> an {@code AbstractReportService} subclass: with one implementor in R2a a shared
 * base is speculative — R2b introduces it alongside {@code CsvReportService} + the shared data layer.
 * <p>
 * Gated by {@code reports.renderer.enabled} (opt-in invariant, mirrors R1): its dashboard collaborator is
 * likewise gated, so the platform boots renderer-off.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class PdfReportService implements TbReportRenderService {

    /** pt→px at 96/72 dpi. */
    private static final float PT_TO_PX = 4.0f / 3.0f;
    private static final int DEFAULT_PAGE_MARGIN = 20;

    /** Components that need the server-side data layer (spec §18) — guarded until R2b. */
    private static final Set<ReportComponentType> R2B_DATA_TYPES = EnumSet.of(
            ReportComponentType.ENTITY_TABLE,
            ReportComponentType.ALARM_TABLE,
            ReportComponentType.TIME_SERIES_TABLE,
            ReportComponentType.SUB_REPORT);

    private final Map<ReportComponentType, PdfReportComponentRenderer<ReportComponent>> componentRenderers =
            new EnumMap<>(ReportComponentType.class);
    private final DashboardReportService dashboardReportService;

    /**
     * Base URL the DASHBOARD component's headless capture navigates to (design spec §13 {@code
     * reports.renderer.base_url}). A scheduled/templated render is driven off a job, not an inbound
     * request, so there's no {@code HttpServletRequest} to derive a base URL from — it comes from config.
     */
    private final String rendererBaseUrl;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public PdfReportService(List<PdfReportComponentRenderer> renderers,
                            DashboardReportService dashboardReportService,
                            @Value("${reports.renderer.base_url:http://localhost:8080}") String rendererBaseUrl) {
        renderers.forEach(renderer -> {
            ReportComponentType type = renderer.getType();
            if (type != null) {
                this.componentRenderers.put(type, (PdfReportComponentRenderer<ReportComponent>) renderer);
            }
        });
        this.dashboardReportService = dashboardReportService;
        this.rendererBaseUrl = rendererBaseUrl;
    }

    @Override
    public TbReportFormat getFormat() {
        return TbReportFormat.PDF;
    }

    @Override
    public ReportData generateReport(ReportTask task, TbReportCtx ctx) {
        long startTs = System.currentTimeMillis();
        PdfReportTemplateConfig configuration = (PdfReportTemplateConfig) ctx.getConfiguration();
        List<ReportComponent> components = configuration.getComponents();
        int componentCount = components != null ? components.size() : 0;
        log.debug("[{}] Rendering PDF report for template [{}], {} component(s)",
                task.getTenantId(), task.getReportTemplateId(), componentCount);
        try {
            Dimension pageSize = computePageSize(configuration);
            Insets pageMargins = computePageMargins(configuration);
            int usablePageWidthPx = (int) ((float) (pageSize.width - pageMargins.getLeft() - pageMargins.getRight()) * PT_TO_PX);

            ITextRenderer renderer = HtmlRenderUtils.createRenderer(ctx.getImageResolver(), usablePageWidthPx);
            HeaderFooterRenderLayout headerLayout = renderHeaderFooter(renderer, ctx, configuration.getHeader(), usablePageWidthPx);
            HeaderFooterRenderLayout footerLayout = renderHeaderFooter(renderer, ctx, configuration.getFooter(), usablePageWidthPx);

            Map<String, Object> reportVariables = new HashMap<>();
            fillPageLayoutVariables(reportVariables, configuration, headerLayout, footerLayout, pageSize, pageMargins);
            reportVariables.put("pageContent", renderContent(ctx, components, usablePageWidthPx));

            String renderedHtmlContent = ThymeleafUtil.renderFromHtmlTemplate("html/report-template", reportVariables);
            String xHtml = HtmlRenderUtils.convertToXhtml(renderedHtmlContent);
            renderer.setDocumentFromString(xHtml);
            renderer.layout();

            byte[] reportBytes;
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                renderer.createPDF(outputStream);
                reportBytes = outputStream.toByteArray();
            }
            String reportName = ReportUtils.prepareReportName(configuration.getNamePattern(), new Date(), task.getTimezone());
            log.info("[{}] Rendered PDF report [{}] for template [{}]: {} component(s), {} bytes, {} ms",
                    task.getTenantId(), reportName, task.getReportTemplateId(), componentCount, reportBytes.length,
                    System.currentTimeMillis() - startTs);
            return ReportData.builder()
                    .data(reportBytes)
                    .contentType(configuration.getFormat().getContentType())
                    .name(reportName)
                    .build();
        } catch (ReportRenderException e) {
            // R2b guards + hard render errors carry a clear message — surface them unwrapped.
            throw e;
        } catch (Exception e) {
            throw new ReportRenderException("Failed to render PDF report for template " + task.getReportTemplateId(), e);
        }
    }

    private String renderContent(TbReportCtx ctx, List<ReportComponent> components, int usablePageWidthPx) {
        StringBuilder content = new StringBuilder();
        if (components == null) {
            return "";
        }
        for (ReportComponent component : components) {
            if (component != null) {
                content.append(renderComponent(ctx, component, usablePageWidthPx));
            }
        }
        return content.toString();
    }

    private String renderComponent(TbReportCtx ctx, ReportComponent component, int usablePageWidthPx) {
        ReportComponentType type = component.getType();
        if (R2B_DATA_TYPES.contains(type)) {
            // Hard guard: these need the server-side data layer (entity/alarm/timeseries queries,
            // sub-report recursion). Fail clearly rather than emit an empty/broken component.
            throw new ReportRenderException("table components are R2b");
        }
        try {
            PdfReportComponentRenderer<ReportComponent> renderer = componentRenderers.get(type);
            if (renderer == null) {
                throw new ReportRenderException("No renderer registered for component type: " + type);
            }
            ComponentData componentData = buildComponentData(ctx, component, usablePageWidthPx);
            return renderer.render(component, componentData);
        } catch (ReportRenderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to render component of type [{}]", type, e);
            return renderError(usablePageWidthPx, "Failed to render component of type: " + type, e);
        }
    }

    /**
     * R2a builds only what the non-data renderers read: the DASHBOARD component's captured PNG, and an
     * empty {@link ComponentData} for everything else.
     * <p>
     * // R2b: server-side data queries populate {@code variables}/{@code entityDatas} here (entity/alarm/
     * time-series data binding for HEADING/RICH_TEXT interpolation + table rows).
     */
    private ComponentData buildComponentData(TbReportCtx ctx, ReportComponent component, int usablePageWidthPx) {
        if (component.getType() == ReportComponentType.DASHBOARD) {
            return buildDashboardComponentData(ctx, (DashboardComponent) component, usablePageWidthPx);
        }
        return new ComponentData(usablePageWidthPx);
    }

    /**
     * Captures the DASHBOARD component's dashboard as a PNG through R1's Playwright renderer ({@link
     * DashboardReportService}), forcing {@code type=png} (PE-verbatim). The component's own {@code config}
     * supplies dashboard id/state/timewindow; the render user/timezone come from the ctx; the base URL from
     * config (falling back to {@code reports.renderer.base_url}). Config problems become an error box, not
     * a hard failure.
     */
    private ComponentData buildDashboardComponentData(TbReportCtx ctx, DashboardComponent component, int usablePageWidthPx) {
        DashboardReportConfig componentConfig = component.getConfig();
        if (componentConfig == null) {
            return new ComponentData(usablePageWidthPx, "Dashboard report config is empty");
        }
        if (StringUtils.isBlank(componentConfig.getDashboardId())) {
            return new ComponentData(usablePageWidthPx, "Dashboard id is not configured for dashboard report");
        }
        DashboardReportConfig config = new DashboardReportConfig();
        config.setDashboardId(componentConfig.getDashboardId());
        config.setState(componentConfig.getState());
        config.setTimewindow(componentConfig.getTimewindow());
        config.setUseDashboardTimewindow(componentConfig.isUseDashboardTimewindow());
        config.setUserId(ctx.getUserId() != null ? ctx.getUserId().toString() : null);
        config.setTimezone(ctx.getTimeZone());
        config.setType("png");
        config.setBaseUrl(StringUtils.isNotBlank(componentConfig.getBaseUrl()) ? componentConfig.getBaseUrl() : rendererBaseUrl);
        ReportData dashboardPng = dashboardReportService.generateReport(ctx.getTenantId(), config);
        return new ComponentData(usablePageWidthPx, dashboardPng.getData());
    }

    private String renderError(int usablePageWidthPx, String errorMessage, Exception e) {
        return componentRenderers.get(ReportComponentType.ERROR)
                .render(new ErrorComponent(errorMessage, e), new ComponentData(usablePageWidthPx));
    }

    /**
     * Renders a running header/footer's components to HTML and measures its pixel height (via a throwaway
     * layout on the shared {@code renderer}) so the assembler can reserve the matching {@code @page}
     * margin. PE-faithful, including the separate first-page variant.
     */
    private HeaderFooterRenderLayout renderHeaderFooter(ITextRenderer renderer, TbReportCtx ctx, HeaderFooter headerFooter, int usablePageWidthPx) throws Exception {
        HeaderFooterRenderLayout layout = new HeaderFooterRenderLayout();
        if (headerFooter == null) {
            return layout;
        }
        layout.setEnabled(headerFooter.isEnabled());
        if (headerFooter.isEnabled()) {
            String htmlContent = renderContent(ctx, headerFooter.getComponents(), usablePageWidthPx);
            layout.setHtmlContent(htmlContent);
            layout.setHeightPx(HtmlRenderUtils.measureHtmlHeight(renderer, htmlContent, usablePageWidthPx));
        }
        layout.setFirstPageEnabled(headerFooter.getFirstPage() != null && headerFooter.getFirstPage().isEnabled());
        if (layout.isFirstPageEnabled()) {
            String htmlContent = renderContent(ctx, headerFooter.getFirstPage().getComponents(), usablePageWidthPx);
            layout.setFirstPageHtmlContent(htmlContent);
            layout.setFirstPageHeightPx(HtmlRenderUtils.measureHtmlHeight(renderer, htmlContent, usablePageWidthPx));
        }
        return layout;
    }

    private Dimension computePageSize(PdfReportTemplateConfig config) {
        PageSize pageSize = Optional.ofNullable(config.getPageSize()).orElse(PageSize.A4);
        return config.getPageOrientation() == PageOrientation.LANDSCAPE
                ? new Dimension(pageSize.getHeight(), pageSize.getWidth())
                : new Dimension(pageSize.getWidth(), pageSize.getHeight());
    }

    private Insets computePageMargins(PdfReportTemplateConfig configuration) {
        if (configuration.getPageMargins() != null) {
            return configuration.getPageMargins();
        }
        return new Insets(DEFAULT_PAGE_MARGIN);
    }

    private void fillPageLayoutVariables(Map<String, Object> reportVariables, PdfReportTemplateConfig configuration,
                                         HeaderFooterRenderLayout headerLayout, HeaderFooterRenderLayout footerLayout,
                                         Dimension pageSize, Insets pageMargins) {
        reportVariables.put("pageWidth", pageSize.getWidth() + "pt");
        reportVariables.put("pageHeight", pageSize.getHeight() + "pt");
        reportVariables.put("pageMarginLeft", pageMargins.getLeft() + "pt");
        reportVariables.put("pageMarginRight", pageMargins.getRight() + "pt");
        String pageBackground = configuration.getPageBackground() != null
                ? ColorUtils.normalizeCssColor(configuration.getPageBackground()) : "#fff";
        reportVariables.put("pageBackground", pageBackground);
        int minContentHeight = 100;
        int minHalfPageContentHeight = Math.max((pageSize.height - pageMargins.getTop() - pageMargins.getBottom() - minContentHeight) / 2, 0);
        int maxTopMargin = pageMargins.getTop() + minHalfPageContentHeight;
        int maxBottomMargin = pageMargins.getBottom() + minHalfPageContentHeight;
        int pageMarginTop = fillHeaderFooterVariables(reportVariables, headerLayout, pageMargins, maxTopMargin, true);
        reportVariables.put("pageMarginTop", pageMarginTop + "pt");
        int pageMarginBottom = fillHeaderFooterVariables(reportVariables, footerLayout, pageMargins, maxBottomMargin, false);
        reportVariables.put("pageMarginBottom", pageMarginBottom + "pt");
    }

    private int fillHeaderFooterVariables(Map<String, Object> reportVariables, HeaderFooterRenderLayout layout,
                                          Insets pageMargins, int maxMargin, boolean headerElseFooter) {
        String prefix = headerElseFooter ? "Header" : "Footer";
        String marginPrefix = headerElseFooter ? "Top" : "Bottom";
        reportVariables.put("enable" + prefix, layout.isEnabled());
        int startMargin = headerElseFooter ? pageMargins.getTop() : pageMargins.getBottom();
        int margin = startMargin;
        reportVariables.put("page" + prefix + "Padding", startMargin + "pt");
        if (layout.isEnabled()) {
            reportVariables.put("page" + prefix, layout.getHtmlContent());
            margin = Math.min((int) ((float) startMargin + (float) layout.getHeightPx() * 3.0f / 4.0f), maxMargin);
            int height = margin - startMargin;
            reportVariables.put("page" + prefix + "Height", height + "pt");
        }
        reportVariables.put("enableFirstPage" + prefix, layout.isFirstPageEnabled());
        if (layout.isFirstPageEnabled()) {
            int firstPageMargin = Math.min((int) ((float) startMargin + (float) layout.getFirstPageHeightPx() * 3.0f / 4.0f), maxMargin);
            reportVariables.put("firstPageMargin" + marginPrefix, firstPageMargin + "pt");
            reportVariables.put("firstPage" + prefix, layout.getFirstPageHtmlContent());
            int height = firstPageMargin - startMargin;
            reportVariables.put("firstPage" + prefix + "Height", height + "pt");
        }
        return margin;
    }
}
