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
package org.thingsboard.server.service.report.datasource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.ImageDescriptor;
import org.thingsboard.server.common.data.ResourceType;
import org.thingsboard.server.common.data.TbResource;
import org.thingsboard.server.common.data.TbResourceInfo;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.SortOrder;
import org.thingsboard.server.common.data.query.DeviceTypeFilter;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.report.configuration.timewindow.Interval;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.resource.ImageService;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.render.PlaywrightWebReportRenderer;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.UserPrincipal;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The permission-boundary test for the report engine's server-side data layer ({@link
 * LocalReportDataService}). The whole point of the R2b query context is that a report can only ever read
 * what its task user is permitted to read — this proves it against a live DB with two tenants.
 * <p>
 * Runs renderer-on ({@code reports.renderer.enabled=true}) so the gated {@link LocalReportDataService} bean
 * (and the rest of the engine graph) actually wire; {@link PlaywrightWebReportRenderer} is mocked so no
 * headless Chromium launches — the queries here never touch it.
 */
@DaoSqlTest
@TestPropertySource(properties = {"reports.renderer.enabled=true"})
public class LocalReportDataServiceTest extends AbstractControllerTest {

    @MockitoBean
    private PlaywrightWebReportRenderer playwrightWebReportRenderer;

    @Autowired
    private ReportDataService reportDataService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private UserService userService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ImageService imageService;

    private TenantId tenantIdA;
    private SecurityUser userA;
    private Device deviceA;

    private TenantId tenantIdB;
    private SecurityUser userB;
    private Device deviceB;

    @Before
    public void setUpTenants() throws Exception {
        loginSysAdmin();
        tenantIdA = saveTenant("Report Boundary Tenant A", "report-boundary-a@thingsboard.org");
        userA = tenantAdmin(tenantIdA, "report-boundary-a-admin@thingsboard.org");
        deviceA = createDevice(tenantIdA, "Report Device A");

        tenantIdB = saveTenant("Report Boundary Tenant B", "report-boundary-b@thingsboard.org");
        userB = tenantAdmin(tenantIdB, "report-boundary-b-admin@thingsboard.org");
        deviceB = createDevice(tenantIdB, "Report Device B");
    }

    @After
    public void deleteTenants() {
        if (tenantIdA != null) {
            tenantService.deleteTenant(tenantIdA);
        }
        if (tenantIdB != null) {
            tenantService.deleteTenant(tenantIdB);
        }
    }

    @Test
    public void entityQueryIsScopedToCtxSecurityUserAcrossTenants() {
        // The SECURITY-CRITICAL assertion. The same query, run under two different ctx users, must return only
        // each user's own tenant's data — a report for tenant A can never see tenant B's device, and vice versa.
        EntityDataQuery query = allDefaultDevicesQuery();

        Set<UUID> visibleToA = deviceIds(reportDataService.findEntityDataByQuery(query, ctxFor(userA)));
        assertThat(visibleToA).as("tenant A's report sees tenant A's device").contains(deviceA.getId().getId());
        assertThat(visibleToA).as("tenant A's report must NOT see tenant B's device").doesNotContain(deviceB.getId().getId());

        // Swap only the ctx user — the visible set flips to the other tenant's data.
        Set<UUID> visibleToB = deviceIds(reportDataService.findEntityDataByQuery(query, ctxFor(userB)));
        assertThat(visibleToB).as("tenant B's report sees tenant B's device").contains(deviceB.getId().getId());
        assertThat(visibleToB).as("tenant B's report must NOT see tenant A's device").doesNotContain(deviceA.getId().getId());
    }

    @Test
    public void queryIsScopedByCtxSecurityUserNotCtxTenantId() {
        // Nails down WHERE the scope comes from: the trusted SecurityUser, never any other ctx/query field. A
        // ctx whose tenantId was set to A but whose SecurityUser is tenant B's user reads tenant B's data —
        // getSecurityUser(ctx) is the only thing that decides scope, so tampering another field changes nothing.
        TbReportCtx spoofedTenantCtx = TbReportCtx.builder()
                .tenantId(tenantIdA)      // says "tenant A"
                .securityUser(userB)      // but the trust anchor is tenant B's user
                .build();

        Set<UUID> visible = deviceIds(reportDataService.findEntityDataByQuery(allDefaultDevicesQuery(), spoofedTenantCtx));
        assertThat(visible).as("scope follows ctx.securityUser (tenant B), not ctx.tenantId (tenant A)")
                .contains(deviceB.getId().getId())
                .doesNotContain(deviceA.getId().getId());
    }

    @Test
    public void queryWithoutSecurityUserFailsClosed() {
        // A ctx with no security user must never run an unscoped read — the chokepoint fails closed.
        DeviceTypeFilter filter = new DeviceTypeFilter();
        filter.setDeviceTypes(List.of("default"));
        filter.setDeviceNameFilter("");
        EntityCountQuery countQuery = new EntityCountQuery(filter);
        TbReportCtx noUserCtx = TbReportCtx.builder().tenantId(tenantIdA).build();

        assertThatThrownBy(() -> reportDataService.countEntitiesByQuery(countQuery, noUserCtx))
                .as("no security user in ctx -> fail closed")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void timeseriesReadIsGatedByCtxSecurityUser() {
        // Timeseries reads (ts_kv is keyed by entityId, not tenant) are gated ONLY by the READ_TELEMETRY
        // validation. AccessValidator reports a cross-tenant entity as not-found via onSuccess(non-OK), so the
        // gate must turn that into a throw — never a foreign read. Both timeseries methods must fail closed.
        Interval interval = new Interval();
        interval.setIntervalType(IntervalType.MILLISECONDS);
        interval.setInterval(0L);
        long now = System.currentTimeMillis();

        assertThatThrownBy(() -> reportDataService.getTimeseries(
                deviceB.getId(), List.of("temperature"), 0L, now, interval, "UTC",
                Aggregation.NONE, SortOrder.Direction.DESC, 100, false, ctxFor(userA)))
                .as("tenant A's report cannot read tenant B's device timeseries")
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> reportDataService.findTimeseriesByQueries(
                deviceB.getId(),
                List.<ReadTsKvQuery>of(new BaseReadTsKvQuery("temperature", 0L, now)),
                ctxFor(userA)))
                .as("tenant A's report cannot read tenant B's device timeseries via read-queries")
                .isInstanceOf(RuntimeException.class);

        // The legitimate own-tenant read is allowed (returns a — here empty — result, does not throw).
        assertThat(reportDataService.getTimeseries(
                deviceA.getId(), List.of("temperature"), 0L, now, interval, "UTC",
                Aggregation.NONE, SortOrder.Direction.DESC, 100, false, ctxFor(userA)))
                .as("tenant A's report can read tenant A's own device timeseries").isNotNull();
    }

    @Test
    public void downloadImageResolvesTenantImageForCtxUserOnly() throws Exception {
        // A legitimate image download resolves the report user's tenant image; another tenant's ctx cannot
        // fetch it by key (it is not in that tenant's scope) — the same permission boundary, on the image path.
        byte[] pngData = pngBytes();
        TbResourceInfo imageInfo = saveTenantImage(tenantIdA, "report-a-logo.png", pngData);
        String key = imageInfo.getResourceKey();

        byte[] resolved = reportDataService.downloadImage("tenant", key, ctxFor(userA));
        assertThat(resolved).as("tenant A's report resolves tenant A's image").isNotEmpty();

        assertThatThrownBy(() -> reportDataService.downloadImage("tenant", key, ctxFor(userB)))
                .as("tenant B's report cannot resolve tenant A's image by key")
                .isInstanceOf(ThingsboardException.class);
    }

    // --- fixtures ---

    private TbReportCtx ctxFor(SecurityUser securityUser) {
        return TbReportCtx.builder()
                .tenantId(securityUser.getTenantId())
                .securityUser(securityUser)
                .build();
    }

    private static EntityDataQuery allDefaultDevicesQuery() {
        DeviceTypeFilter filter = new DeviceTypeFilter();
        filter.setDeviceTypes(List.of("default"));
        filter.setDeviceNameFilter("");
        EntityDataPageLink pageLink = new EntityDataPageLink(100, 0, null, null);
        return new EntityDataQuery(filter, pageLink, List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")), null, null);
    }

    private static Set<UUID> deviceIds(PageData<EntityData> result) {
        return result.getData().stream().map(ed -> ed.getEntityId().getId()).collect(Collectors.toSet());
    }

    private TenantId saveTenant(String title, String email) {
        Tenant tenant = new Tenant();
        tenant.setTitle(title);
        tenant.setEmail(email);
        return tenantService.saveTenant(tenant).getId();
    }

    private SecurityUser tenantAdmin(TenantId tenantId, String email) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setAuthority(Authority.TENANT_ADMIN);
        user.setEmail(email);
        User saved = userService.saveUser(tenantId, user);
        return new SecurityUser(saved, true, new UserPrincipal(UserPrincipal.Type.USER_NAME, saved.getEmail()));
    }

    private Device createDevice(TenantId tenantId, String name) {
        Device device = new Device();
        device.setTenantId(tenantId);
        device.setName(name);
        device.setType("default");
        return deviceService.saveDevice(device);
    }

    private TbResourceInfo saveTenantImage(TenantId tenantId, String fileName, byte[] data) {
        TbResource image = new TbResource();
        image.setTenantId(tenantId);
        image.setResourceType(ResourceType.IMAGE);
        image.setFileName(fileName);
        image.setTitle(fileName);
        ImageDescriptor descriptor = new ImageDescriptor();
        descriptor.setMediaType("image/png");
        image.setDescriptorValue(descriptor);
        image.setData(data);
        return imageService.saveImage(image);
    }

    /** A real (decodable) 8x8 PNG so ImageService.processImage accepts it. */
    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                image.setRGB(x, y, 0x2b388f);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
