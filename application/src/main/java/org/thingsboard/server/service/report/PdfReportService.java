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
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.rule.engine.api.DashboardReportService;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.HeaderFooter;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.DataReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ErrorComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.components.SubReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * dashboard reports keep working through the new engine.
 * <p>
 * R2b: {@code extends} {@link AbstractReportService} — the shared server-side data layer (entity/alarm/
 * timeseries builds + TBEL post-processing). <b>Task G</b> wires the entity/alarm/time-series tables:
 * {@link #buildComponentData} dispatches each to the inherited F2 builder and the three table renderers lay
 * the rows out (cell values escaped by {@code table-template}). <b>Task H</b> wires SUB_REPORT: {@link
 * #renderSubReport} recursively renders a referenced child template once per resolved entity, bounded by
 * the shared {@link TbReportCtx#MAX_SUB_REPORT_DEPTH} (an Inferrix hardening over PE — see the method). PDF keeps its own
 * HTML-assembly specifics (page/header/footer layout, the DASHBOARD Playwright-PNG capture).
 * <p>
 * Gated by {@code reports.renderer.enabled} (opt-in invariant, mirrors R1): its dashboard collaborator is
 * likewise gated, so the platform boots renderer-off. {@link AbstractReportService} is abstract (no bean), so
 * the gate on this concrete engine keeps the opt-in intact.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class PdfReportService extends AbstractReportService {

    /** pt→px at 96/72 dpi. */
    private static final float PT_TO_PX = 4.0f / 3.0f;
    private static final int DEFAULT_PAGE_MARGIN = 20;

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
            // Hand flying-saucer a parsed DOM, never a re-serialised XHTML string. jsoup's XML output
            // syntax wraps <style>/<script> bodies in /*<![CDATA[*/ ... /*]]>*/; the XML parser then
            // unwraps the CDATA and leaves /**/ @page { ... } /**/ as the element's text, which loses the
            // @page rule and prints the CSS into the report. parseDom skips serialisation entirely, and is
            // the same path measureHtmlHeight already uses.
            renderer.setDocument(HtmlRenderUtils.parseDom(renderedHtmlContent));
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
            // Top-level safety net: per-component failures now degrade to error boxes (renderComponent), so
            // this only catches a fatal render error thrown outside component rendering — surface it unwrapped.
            throw e;
        } catch (Exception e) {
            throw new ReportRenderException("Failed to render PDF report for template " + task.getReportTemplateId(), e);
        }
    }

    private String renderContent(TbReportCtx ctx, List<ReportComponent> components, int usablePageWidthPx) {
        // Top-level content (and header/footer) carries no state entity — sub-report / originator scoping is Task H.
        return renderContent(ctx, components, usablePageWidthPx, null);
    }

    private String renderContent(TbReportCtx ctx, List<ReportComponent> components, int usablePageWidthPx, EntityData stateEntity) {
        StringBuilder content = new StringBuilder();
        if (components == null) {
            return "";
        }
        EntityId stateEntityId = stateEntity != null ? stateEntity.getEntityId() : null;
        for (ReportComponent component : components) {
            if (component == null) {
                continue;
            }
            if (component.getType() == ReportComponentType.TIME_SERIES_TABLE) {
                // PE dispatches the "render once PER resolved entity" types here, not through renderComponent: a
                // time-series table shows one entity's series, and a sub-report renders a child template's
                // components (recursively) per entity. Everything else renders a single fragment.
                content.append(renderTimeseriesTables(ctx, (TimeseriesTableComponent) component, usablePageWidthPx, stateEntityId));
            } else if (component.getType() == ReportComponentType.SUB_REPORT) {
                content.append(renderSubReport(ctx, (SubReportComponent) component, usablePageWidthPx, stateEntityId));
            } else {
                content.append(renderComponent(ctx, component, usablePageWidthPx, stateEntity));
            }
        }
        return content.toString();
    }

    /**
     * Renders a TIME_SERIES_TABLE as one table per entity (PE {@code renderTimeseriesTables}): it resolves the
     * source's entities through their LATEST projection, then delegates each to {@link #renderComponent}, which
     * builds that entity's series via the inherited F2 {@code buildTsComponentData}. The entity resolution runs
     * inside a try/catch so a malformed source (bad device id, a filter id not in the template) degrades to a
     * single error box rather than failing the whole report — the same graceful-degradation contract that keeps
     * {@code buildComponentData} inside {@link #renderComponent}'s try/catch.
     */
    private String renderTimeseriesTables(TbReportCtx ctx, TimeseriesTableComponent component, int usablePageWidthPx, EntityId stateEntityId) {
        try {
            Optional<DataSource> dataSource = ReportUtils.getSingleDataSource(component);
            if (dataSource.isEmpty()) {
                return renderError(usablePageWidthPx, "Data source is not configured for time series table", null);
            }
            DataSource ds = dataSource.get();
            if (ds.getDataKeys() == null || ds.getDataKeys().isEmpty()) {
                return renderError(usablePageWidthPx, "At least one time series column should be specified for time series table", null);
            }
            DataSource latestDataSource = DataSource.builder()
                    .type(ds.getType())
                    .deviceId(ds.getDeviceId())
                    .entityAliasId(ds.getEntityAliasId())
                    .filterId(ds.getFilterId())
                    .dataKeys(ds.getLatestDataKeys())
                    .build();
            List<EntityData> entities = fetchEntities(ctx, latestDataSource, stateEntityId);
            StringBuilder content = new StringBuilder();
            for (EntityData entity : entities) {
                content.append(renderComponent(ctx, component, usablePageWidthPx, entity));
            }
            return content.toString();
        } catch (ReportRenderException e) {
            log.debug("Rendering time series table as error box: {}", e.getMessage());
            return renderError(usablePageWidthPx, e.getMessage(), null);
        } catch (Exception e) {
            log.error("Failed to render time series table", e);
            return renderError(usablePageWidthPx, "Failed to render component of type: " + ReportComponentType.TIME_SERIES_TABLE, e);
        }
    }

    private String renderComponent(TbReportCtx ctx, ReportComponent component, int usablePageWidthPx, EntityData stateEntity) {
        ReportComponentType type = component.getType();
        try {
            PdfReportComponentRenderer<ReportComponent> renderer = componentRenderers.get(type);
            if (renderer == null) {
                throw new ReportRenderException("No renderer registered for component type: " + type);
            }
            // Data fetching for the tables happens here, INSIDE the try/catch: a malformed datasource makes an
            // F2 builder throw, which must degrade to an error box for this one component, never fail the report.
            ComponentData componentData = buildComponentData(ctx, component, usablePageWidthPx, stateEntity);
            return renderer.render(component, componentData);
        } catch (ReportRenderException e) {
            // A known-unsupported feature inside a supported component (e.g. entity-key IMAGE source) or a
            // clear render failure carrying its own message — degrade to an error box, don't fail the report.
            log.debug("Rendering component of type [{}] as error box: {}", type, e.getMessage());
            return renderError(usablePageWidthPx, e.getMessage(), null);
        } catch (Exception e) {
            log.error("Failed to render component of type [{}]", type, e);
            return renderError(usablePageWidthPx, "Failed to render component of type: " + type, e);
        }
    }

    /**
     * Renders a SUB_REPORT component (PE {@code renderSubReport}): loads the referenced child template and
     * renders its components once per resolved entity, recursively. The child template is loaded through {@link
     * ReportDataService#findReportTemplate} — permission-scoped under {@code ctx.securityUser}, so a sub-report
     * can only ever load a template the task user may read (cross-tenant load is already blocked in F1). The
     * per-entity list comes from the inherited F2 {@link #getSubReportEntities} (also permission-scoped), and
     * each entity renders through a child ctx from {@link TbReportCtx#createSubReportCxt}, which inherits the
     * parent's {@code securityUser} — a sub-report can never escape the parent report's permission scope. Each
     * entity's block is optionally wrapped in a {@code no-page-break} div per {@link
     * SubReportComponent#isAvoidPageBreakInside()}.
     * <p>
     * <b>Inferrix hardening over PE — {@link TbReportCtx#MAX_SUB_REPORT_DEPTH} recursion bound (shared with the
     * CSV engine).</b> PE's {@code renderSubReport} has NO bound, so a self-referential template ({@code
     * templateId} = its own id), a cycle (A→B→A) or pathologically deep nesting recurses forever → {@code
     * StackOverflowError} + per-level amplified DB reads (findReportTemplate + getSubReportEntities) = a DoS.
     * Because {@link TbReportCtx#createSubReportCxt} increments {@link TbReportCtx#getSubReportDepth()} each
     * level, {@link TbReportCtx#isSubReportDepthExceeded()} once the cap is reached bounds self-reference,
     * cycles AND deep nesting in one check (same precedent as the F1-hang / R2a-baseUrl / G-fontFamily
     * hardenings). {@link TbReportCtx#consumeSubReportBudget()} sits before {@code findReportTemplate}, so it
     * bounds the amplified DB reads too. {@code CsvReportService} uses the SAME shared checks.
     * <p>
     * <b>Degradation:</b> a null/unknown template id, a non-PDF child template ({@code instanceof} guards the
     * cast, so a CSV/other-format template degrades instead of throwing {@code ClassCastException}), or any
     * render failure become an error box, never a whole-report failure (the F2 degradation contract). An
     * interrupt still surfaces through {@link #renderError} rather than being swallowed into an error box.
     */
    private String renderSubReport(TbReportCtx ctx, SubReportComponent component, int usablePageWidthPx, EntityId stateEntityId) {
        ReportTemplateId templateId = component.getTemplateId();
        if (templateId == null) {
            return renderError(usablePageWidthPx, "Report template id is not configured for SubReport", null);
        }
        if (ctx.isSubReportDepthExceeded()) {
            return renderError(usablePageWidthPx, "Sub-report nesting exceeds the maximum depth of " + TbReportCtx.MAX_SUB_REPORT_DEPTH, null);
        }
        // The depth cap bounds a single chain, but a sub-report fans out to one child render per resolved entity,
        // so the render tree is E-ary — E^depth total renders would OOM the shared render heap even within the
        // depth cap. This shared, per-report budget (threaded by reference through createSubReportCxt) caps TOTAL
        // sub-report renders across every branch. Consume before findReportTemplate so it also bounds DB reads.
        if (!ctx.consumeSubReportBudget()) {
            return renderError(usablePageWidthPx, "Sub-report render budget of " + TbReportCtx.MAX_TOTAL_SUB_REPORT_RENDERS
                    + " exceeded (too many sub-report renders in one report)", null);
        }
        try {
            ReportTemplate reportTemplate = dataService.findReportTemplate(templateId, ctx);
            if (reportTemplate == null) {
                return renderError(usablePageWidthPx, "Template with id " + templateId + " not found. Please check the configuration.", null);
            }
            if (!(reportTemplate.getConfiguration() instanceof PdfReportTemplateConfig childConfig)) {
                return renderError(usablePageWidthPx, "Sub-report template " + templateId + " is not a PDF report template", null);
            }
            TbReportCtx subReportCtx = ctx.createSubReportCxt(childConfig);
            List<EntityData> entities = getSubReportEntities(ctx, component, stateEntityId);
            StringBuilder content = new StringBuilder();
            for (EntityData entity : entities) {
                if (component.isAvoidPageBreakInside()) {
                    content.append("<div class=\"no-page-break\">");
                }
                content.append(renderContent(subReportCtx, childConfig.getComponents(), usablePageWidthPx, entity));
                if (component.isAvoidPageBreakInside()) {
                    content.append("</div>");
                }
            }
            return content.toString();
        } catch (Exception e) {
            log.error("Failed to render sub-report, template id [{}]", templateId, e);
            return renderError(usablePageWidthPx, "Failed to render sub-report " + templateId, e);
        }
    }

    /**
     * Dispatches a component to its {@link ComponentData} build (PE {@code getComponentData}). The table types
     * call the inherited F2 builders — permission-scoped through {@code ReportDataService} under {@code
     * ctx.securityUser}, so a table renderer never fetches data itself; DASHBOARD keeps R1's Playwright-PNG
     * capture; everything else (IMAGE, HEADING, RICH_TEXT, DIVIDER, PAGE_BREAK) needs no data. {@code
     * stateEntity} is the per-entity scope threaded by {@link #renderTimeseriesTables} (null elsewhere until
     * Task H). {@link #populateReportVars} then stamps the shared report vars (e.g. {@code reportCreatedTime}).
     */
    private ComponentData buildComponentData(TbReportCtx ctx, ReportComponent component, int usablePageWidthPx, EntityData stateEntity) {
        ComponentData componentData = switch (component.getType()) {
            case TIME_SERIES_TABLE -> buildTsComponentData(usablePageWidthPx, ctx, (TimeseriesTableComponent) component, stateEntity);
            case ALARM_TABLE -> buildAlarmComponentData(usablePageWidthPx, ctx, (AlarmTableComponent) component, stateEntity);
            case ENTITY_TABLE -> buildEntityTableComponentData(usablePageWidthPx, ctx, (DataReportComponent) component, stateEntity);
            case DASHBOARD -> buildDashboardComponentData(ctx, (DashboardComponent) component, usablePageWidthPx);
            default -> new ComponentData(usablePageWidthPx);
        };
        populateReportVars(componentData, ctx);
        return componentData;
    }

    /**
     * Builds an ENTITY_TABLE's rows from its single {@link DataSource} — one row per permission-scoped entity
     * via the inherited F2 {@code collectEntityDatas}. <b>CE deviation from PE:</b> PE's {@code
     * buildMultipleDataSourceData} iterates and merges every datasource and its {@code DataSourceType} switch
     * also builds ENTITY_COUNT/ALARM_COUNT counts. CE resolves the one datasource the table's columns come from
     * ({@link ReportUtils#getSingleDataSource}) and needs neither the merge nor the count builds: an entity
     * table's rows are always DEVICE/ENTITY entities (a COUNT source produces a scalar variable, not rows — not
     * a table concern), so {@code ComponentData#merge} and {@code toEntityCountQuery}/{@code toAlarmCountQuery}
     * are deliberately not ported here (clean seam). A COUNT source configured on a table throws in {@code
     * collectEntityDatas} and degrades to an error box.
     */
    private ComponentData buildEntityTableComponentData(int usablePageWidthPx, TbReportCtx ctx, DataReportComponent component, EntityData stateEntity) {
        Optional<DataSource> dataSource = ReportUtils.getSingleDataSource(component);
        if (dataSource.isEmpty()) {
            return new ComponentData(usablePageWidthPx);
        }
        EntityId stateEntityId = stateEntity != null ? stateEntity.getEntityId() : null;
        List<Map<String, String>> entityDatas = collectEntityDatas(ctx, dataSource.get(), stateEntityId);
        Map<String, Object> variables = new HashMap<>();
        if (stateEntityId == null && !entityDatas.isEmpty()) {
            variables.putAll(entityDatas.get(0));
        }
        return new ComponentData(usablePageWidthPx, dataSource.get(), entityDatas, variables);
    }

    /**
     * Captures the DASHBOARD component's dashboard as a PNG through R1's Playwright renderer ({@link
     * DashboardReportService}), forcing {@code type=png} (PE-verbatim). The component's own {@code config}
     * supplies dashboard id/state/timewindow; the render user/timezone come from the ctx. The base URL is
     * ALWAYS the trusted server value ({@code reports.renderer.base_url}) — never the component JSON: letting
     * template config pick the host would let it steer the render access token to an attacker (token
     * exfiltration + SSRF), the same reason {@code DashboardReportController} forces {@code
     * constructBaseUrl(request)}. Config problems become an error box, not a hard failure.
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
        // SECURITY: always the trusted server base URL — never componentConfig.getBaseUrl(). The access
        // token minted for this render is sent to this host, so a component must not be able to pick it.
        config.setBaseUrl(rendererBaseUrl);
        ReportData dashboardPng = dashboardReportService.generateReport(ctx.getTenantId(), config);
        return new ComponentData(usablePageWidthPx, dashboardPng.getData());
    }

    /**
     * Renders one component's failure as an inline error box (PE {@code renderError}). Restores PE's interrupt
     * surfacing that the earlier CE port had dropped: if the failure is (or wraps) an {@link
     * InterruptedException}, a render was cancelled/timed out — rethrow so the whole render aborts instead of
     * silently error-boxing every component. Task H's sub-report recursion is the first path where swallowing
     * an interrupt would matter (a cancelled deep render would otherwise emit an error box at each level).
     */
    private String renderError(int usablePageWidthPx, String errorMessage, Exception e) {
        if (e instanceof InterruptedException || ExceptionUtils.getRootCause(e) instanceof InterruptedException) {
            // Restore the interrupt flag the JDK cleared when InterruptedException was thrown, so an upstream
            // executor's isInterrupted() check (the task cancel path) still sees the cancellation.
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
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
        // Page-level color is normalized here, outside any per-component try/catch — guard it so a bad
        // color string degrades to the default instead of failing the entire render.
        String pageBackground = "#fff";
        if (configuration.getPageBackground() != null) {
            try {
                pageBackground = ColorUtils.normalizeCssColor(configuration.getPageBackground());
            } catch (RuntimeException e) {
                log.debug("Invalid page background color [{}]; using default", configuration.getPageBackground(), e);
            }
        }
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
