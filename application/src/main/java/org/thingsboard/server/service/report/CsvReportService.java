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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.CsvReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.components.SubReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.TableReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.render.ReportUtils;
import org.thingsboard.server.service.report.renderer.CsvReportComponentRenderer;
import org.thingsboard.server.service.report.util.CsvUtils;

import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The R2b CSV render engine (PE {@code report.service.CsvReportService}, adapted) — the CSV sibling of {@link
 * PdfReportService}. Turns one {@link ReportTask}'s typed {@link CsvReportTemplateConfig} into CSV bytes
 * entirely server-side: it iterates the template's components, renders each tabular one to rows via the
 * matching {@link CsvReportComponentRenderer}, and serialises everything through {@link CsvUtils#generateCsv}.
 * <p>
 * <b>Reuses the shared data layer.</b> {@code extends} {@link AbstractReportService} (like the PDF engine), so
 * every entity/alarm/timeseries read runs through the same permission-scoped F2 builders under {@code
 * ctx.securityUser} — there is no parallel data path. Only the three tabular component types have a CSV form
 * ({@code ENTITY_TABLE}, {@code ALARM_TABLE}, {@code TIME_SERIES_TABLE}); every other component (HEADING,
 * RICH_TEXT, IMAGE, DASHBOARD, DIVIDER, PAGE_BREAK) has no CSV representation and is <b>silently excluded</b>
 * from the output (see {@link #renderContent}).
 * <p>
 * <b>Gated by {@code reports.renderer.enabled}</b> (opt-in invariant, mirrors R1/R2a): when the property is
 * off this bean is absent, so the platform boots renderer-off. Auto-registers into {@link TbReportService}'s
 * format→engine map by {@link #getFormat()} == {@link TbReportFormat#CSV}.
 * <p>
 * <b>Two Inferrix hardenings over PE</b> (PE lacks both):
 * <ul>
 *   <li><b>CSV formula injection</b> — neutralised centrally in {@link CsvUtils} (every cell), not here.</li>
 *   <li><b>Bounded sub-report recursion</b> — {@link #renderSubReport} honours the SAME depth + fan-out budget
 *       as the PDF path via the shared {@link TbReportCtx#MAX_SUB_REPORT_DEPTH} / {@link
 *       TbReportCtx#isSubReportDepthExceeded()} / {@link TbReportCtx#consumeSubReportBudget()} (PE's CSV
 *       sub-report path is also unbounded — the same {@code E^depth} cross-tenant OOM Task H fixed for PDF).</li>
 * </ul>
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class CsvReportService extends AbstractReportService {

    private final Map<ReportComponentType, CsvReportComponentRenderer<TableReportComponent>> componentRenderers =
            new EnumMap<>(ReportComponentType.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public CsvReportService(List<CsvReportComponentRenderer> renderers) {
        renderers.forEach(renderer -> {
            ReportComponentType type = renderer.getType();
            if (type != null) {
                this.componentRenderers.put(type, (CsvReportComponentRenderer<TableReportComponent>) renderer);
            }
        });
    }

    @Override
    public TbReportFormat getFormat() {
        return TbReportFormat.CSV;
    }

    @Override
    public ReportData generateReport(ReportTask task, TbReportCtx ctx) {
        long startTs = System.currentTimeMillis();
        ReportTemplateConfig configuration = ctx.getConfiguration();
        List<ReportComponent> components = configuration.getComponents();
        int componentCount = components != null ? components.size() : 0;
        log.debug("[{}] Rendering CSV report for template [{}], {} component(s)",
                task.getTenantId(), task.getReportTemplateId(), componentCount);
        // Top-level content carries no state entity. PE threads task.getOriginator() here (via
        // DataSourceUtils.entityDataFromEntityId), but CE's ReportQueryUtils#buildEntityFilter ignores
        // stateEntityId — state-entity/originator threading is inert in CE (PE-only alias subsystems), exactly
        // as PdfReportService documents for its own top-level null. A null here keeps the CSV and PDF engines
        // behaviourally identical for the same template.
        List<List<String>> content = renderContent(ctx, null);
        byte[] csvBytes = CsvUtils.generateCsv(content);
        String reportName = ReportUtils.prepareReportName(configuration.getNamePattern(), new Date(), task.getTimezone());
        log.info("[{}] Rendered CSV report [{}] for template [{}]: {} row(s), {} bytes, {} ms",
                task.getTenantId(), reportName, task.getReportTemplateId(), content.size(), csvBytes.length,
                System.currentTimeMillis() - startTs);
        return ReportData.builder()
                .data(csvBytes)
                .contentType(configuration.getFormat().getContentType())
                .name(reportName)
                .build();
    }

    /**
     * Renders the components of {@code ctx}'s current template (the top template, or a child template in a
     * sub-report) to CSV rows. Only the three tabular types produce output; the rest are silently excluded.
     * <p>
     * <b>CE deviation from PE:</b> PE's decompiled {@code renderContent} switch <i>throws</i> {@code
     * IllegalArgumentException} on any non-table component, which would fail the whole CSV report if a template
     * mixed a HEADING with a table. CE degrades gracefully instead — a non-tabular component simply contributes
     * no rows — matching the F2/G/H graceful-degradation posture (and the "HEADING + ENTITY_TABLE → only the
     * table's rows" contract): a mixed template still yields its tabular data rather than 500-ing.
     */
    private List<List<String>> renderContent(TbReportCtx ctx, EntityData stateEntity) {
        LinkedList<List<String>> content = new LinkedList<>();
        List<ReportComponent> components = ctx.getConfiguration().getComponents();
        if (components == null) {
            return content;
        }
        EntityId stateEntityId = stateEntity != null ? stateEntity.getEntityId() : null;
        for (ReportComponent component : components) {
            if (component == null) {
                continue;
            }
            switch (component.getType()) {
                case SUB_REPORT -> content.addAll(renderSubReport(ctx, (SubReportComponent) component, stateEntityId));
                case TIME_SERIES_TABLE -> content.addAll(renderTimeseriesTables(ctx, (TimeseriesTableComponent) component, stateEntityId));
                case ALARM_TABLE, ENTITY_TABLE -> content.addAll(renderTableComponent(ctx, (TableReportComponent) component, stateEntity));
                default -> { /* non-tabular component: no CSV representation, silently excluded */ }
            }
        }
        return content;
    }

    /**
     * Renders one {@code ENTITY_TABLE}/{@code ALARM_TABLE} to rows. The F2 data build sits INSIDE the try/catch:
     * a malformed datasource makes a builder throw, which must degrade to a single error row for this one
     * component, never fail the report (the F2/G/H graceful-degradation contract).
     */
    private List<List<String>> renderTableComponent(TbReportCtx ctx, TableReportComponent component, EntityData stateEntity) {
        try {
            ComponentData componentData = getComponentData(ctx, component, stateEntity);
            return componentRenderers.get(component.getType()).render(component, componentData);
        } catch (Exception e) {
            log.error("Failed to render CSV component of type [{}]", component.getType(), e);
            return renderError("Failed to render component of type: " + component.getType(), e);
        }
    }

    /**
     * Renders a {@code TIME_SERIES_TABLE} as one block per resolved entity (PE {@code renderTimeseriesTables}):
     * it resolves the source's entities through their LATEST projection, then renders each entity's series via
     * {@link #renderTableComponent}. Entity resolution runs inside the try/catch so a malformed source (bad
     * device id, a filter id not in the template) degrades to a single error row rather than failing the whole
     * report — the same graceful-degradation contract the PDF path's {@code renderTimeseriesTables} documents
     * (PE's unwrapped CSV version would let such a failure kill the report).
     */
    private List<List<String>> renderTimeseriesTables(TbReportCtx ctx, TimeseriesTableComponent component, EntityId stateEntityId) {
        try {
            Optional<DataSource> dataSource = ReportUtils.getSingleDataSource(component);
            if (dataSource.isEmpty()) {
                return renderError("Data source is not configured for time series table");
            }
            DataSource ds = dataSource.get();
            if (ds.getDataKeys() == null || ds.getDataKeys().isEmpty()) {
                return renderError("At least one time series column should be specified for time series table");
            }
            DataSource latestDataSource = DataSource.builder()
                    .type(ds.getType())
                    .deviceId(ds.getDeviceId())
                    .entityAliasId(ds.getEntityAliasId())
                    .filterId(ds.getFilterId())
                    .dataKeys(ds.getLatestDataKeys())
                    .build();
            List<EntityData> entities = fetchEntities(ctx, latestDataSource, stateEntityId);
            LinkedList<List<String>> content = new LinkedList<>();
            for (EntityData entity : entities) {
                content.addAll(renderTableComponent(ctx, component, entity));
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to render time series table", e);
            return renderError("Failed to render component of type: " + ReportComponentType.TIME_SERIES_TABLE, e);
        }
    }

    /**
     * Renders a {@code SUB_REPORT} to CSV: loads the referenced child template and renders its components once
     * per resolved entity, recursively (PE {@code renderSubReport}). The child template is loaded through the
     * permission-scoped {@code ReportDataService#findReportTemplate} under {@code ctx.securityUser}, the
     * per-entity list comes from the inherited F2 {@link #getSubReportEntities} (also scoped), and each entity
     * renders through a child ctx from {@link TbReportCtx#createSubReportCxt}, which inherits the parent's
     * {@code securityUser} — a sub-report can never escape the parent report's permission scope.
     * <p>
     * <b>Inferrix hardening over PE — bounded recursion (identical to Task H's PDF fix).</b> PE's CSV
     * sub-report path has NO depth or fan-out bound, so a self-referential template ({@code templateId} = its
     * own id), a cycle (A→B→A) or an E-ary fan-out recurses without bound → {@code StackOverflowError} /
     * {@code E^depth} renders + amplified DB reads = a cross-tenant DoS. This honours the SAME bounds as {@code
     * PdfReportService} via the shared {@link TbReportCtx}: {@link TbReportCtx#isSubReportDepthExceeded()}
     * (depth cap {@link TbReportCtx#MAX_SUB_REPORT_DEPTH}) and {@link TbReportCtx#consumeSubReportBudget()}
     * (total-render budget {@link TbReportCtx#MAX_TOTAL_SUB_REPORT_RENDERS}). The budget is consumed BEFORE
     * {@code findReportTemplate}, so it also bounds the amplified DB reads. One shared constant + one shared
     * check per bound means the CSV and PDF recursion paths cannot diverge.
     * <p>
     * <b>Degradation:</b> a null/unknown template id, a non-CSV child template ({@code instanceof} guards the
     * config — a PDF/other-format child degrades instead of being force-rendered as CSV), or any render failure
     * become an error row, never a whole-report failure. An interrupt still surfaces through {@link
     * #renderError} rather than being swallowed.
     */
    private List<List<String>> renderSubReport(TbReportCtx ctx, SubReportComponent component, EntityId stateEntityId) {
        ReportTemplateId templateId = component.getTemplateId();
        if (templateId == null) {
            return renderError("Report template id is not configured for SubReport");
        }
        if (ctx.isSubReportDepthExceeded()) {
            return renderError("Sub-report nesting exceeds the maximum depth of " + TbReportCtx.MAX_SUB_REPORT_DEPTH);
        }
        // The depth cap bounds a single chain, but a sub-report fans out to one child render per resolved entity
        // (an E-ary tree), so the shared, per-report budget caps TOTAL renders across every branch. Consume it
        // before findReportTemplate so it also bounds the amplified DB reads.
        if (!ctx.consumeSubReportBudget()) {
            return renderError("Sub-report render budget of " + TbReportCtx.MAX_TOTAL_SUB_REPORT_RENDERS
                    + " exceeded (too many sub-report renders in one report)");
        }
        try {
            ReportTemplate reportTemplate = dataService.findReportTemplate(templateId, ctx);
            if (reportTemplate == null) {
                return renderError("Template with id " + templateId + " not found. Please check the configuration.");
            }
            if (!(reportTemplate.getConfiguration() instanceof CsvReportTemplateConfig childConfig)) {
                return renderError("Sub-report template " + templateId + " is not a CSV report template");
            }
            TbReportCtx subReportCtx = ctx.createSubReportCxt(childConfig);
            List<EntityData> entities = getSubReportEntities(ctx, component, stateEntityId);
            LinkedList<List<String>> content = new LinkedList<>();
            for (EntityData entity : entities) {
                content.addAll(renderContent(subReportCtx, entity));
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to render sub-report, template id [{}]", templateId, e);
            return renderError("Failed to render sub-report " + templateId, e);
        }
    }

    /**
     * Dispatches a table component to its {@link ComponentData} build (PE {@code getComponentData}): the
     * inherited F2 builders for time-series/alarm (permission-scoped through {@code ReportDataService}), and
     * the CSV-local {@link #buildEntityComponentData} for entity tables. Only ever called for the three tabular
     * types from {@link #renderTableComponent} / {@link #renderTimeseriesTables}. {@link #populateReportVars}
     * then stamps the shared report vars (e.g. {@code reportCreatedTime}).
     */
    private ComponentData getComponentData(TbReportCtx ctx, TableReportComponent component, EntityData stateEntity) {
        ComponentData componentData = switch (component.getType()) {
            case TIME_SERIES_TABLE -> buildTsComponentData(0, ctx, (TimeseriesTableComponent) component, stateEntity);
            case ALARM_TABLE -> buildAlarmComponentData(0, ctx, (AlarmTableComponent) component, stateEntity);
            case ENTITY_TABLE -> buildEntityComponentData(ctx, (EntityTableComponent) component, stateEntity);
            default -> throw new IllegalArgumentException("Unsupported component type: " + component.getType());
        };
        populateReportVars(componentData, ctx);
        return componentData;
    }

    /**
     * Builds an ENTITY_TABLE's rows from its single {@link DataSource} — one row per permission-scoped entity
     * via the inherited F2 {@code collectEntityDatas} (PE {@code CsvReportService#buildEntityComponentData}).
     * The heading-interpolation variables come from the state entity's own values ({@code toStringMap}, which
     * is null-safe — a null state entity yields an empty map). CSV has no page width, so 0 is passed.
     */
    private ComponentData buildEntityComponentData(TbReportCtx ctx, EntityTableComponent component, EntityData stateEntity) {
        Optional<DataSource> singleDataSource = ReportUtils.getSingleDataSource(component);
        if (singleDataSource.isEmpty()) {
            return new ComponentData(0);
        }
        EntityId stateEntityId = stateEntity != null ? stateEntity.getEntityId() : null;
        List<Map<String, String>> entityDatas = collectEntityDatas(ctx, singleDataSource.get(), stateEntityId);
        Map<String, Object> variables = new HashMap<>(toStringMap(stateEntity, singleDataSource.get().getDataKeys(), ctx, null));
        return new ComponentData(0, null, entityDatas, variables);
    }

    private List<List<String>> renderError(String errorDescription) {
        return renderError(errorDescription, null);
    }

    /**
     * Renders one failure as a single-cell CSV error row (PE {@code renderError}). Restores PE's interrupt
     * surfacing (mirrors {@code PdfReportService#renderError}): if the failure is (or wraps) an {@link
     * InterruptedException}, a render was cancelled/timed out — restore the interrupt flag and rethrow so the
     * whole render aborts, instead of emitting an error row at every level of a cancelled deep render.
     */
    private List<List<String>> renderError(String errorDescription, Exception e) {
        if (e instanceof InterruptedException || ExceptionUtils.getRootCause(e) instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        String message = e != null ? errorDescription + " Error: " + e.getMessage() : errorDescription;
        return List.of(List.of(message));
    }

}
