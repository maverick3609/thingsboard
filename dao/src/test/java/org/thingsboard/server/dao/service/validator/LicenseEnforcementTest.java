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
package org.thingsboard.server.dao.service.validator;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.license.LicenseService;
import org.thingsboard.server.dao.usagerecord.ApiLimitService;
import org.thingsboard.server.exception.EntitiesLimitExceededException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the validators actually consult the licence on create, and that a licence refusal propagates.
 * <p>
 * This lives in the validators' own package so the {@code protected validateCreate} hook is callable
 * directly — no Spring context, no database. It is deliberately behavioural: asserting only that a
 * {@code licenseService} field exists would still pass if the validator never called it, and no other test
 * in the suite exercises the blocking path (the test context configures no licence key, so the real service
 * short-circuits).
 */
public class LicenseEnforcementTest {

    private static final TenantId TENANT_ID = TenantId.SYS_TENANT_ID;

    private LicenseService licenseService;
    private ApiLimitService apiLimitService;
    private DeviceDataValidator deviceValidator;
    private AssetDataValidator assetValidator;

    @Before
    public void setUp() {
        licenseService = mock(LicenseService.class);
        apiLimitService = mock(ApiLimitService.class);
        // Permissive by default, so anything that reaches the tenant-profile check passes it.
        when(apiLimitService.checkEntitiesLimit(any(), any())).thenReturn(true);

        deviceValidator = new DeviceDataValidator();
        assetValidator = new AssetDataValidator();
        for (Object validator : new Object[]{deviceValidator, assetValidator}) {
            ReflectionTestUtils.setField(validator, "licenseService", licenseService);
            ReflectionTestUtils.setField(validator, "apiLimitService", apiLimitService);
        }
    }

    @Test
    public void deviceCreateConsultsTheLicence() {
        deviceValidator.validateCreate(TENANT_ID, new Device());
        verify(licenseService).checkCreateAllowed(TENANT_ID, EntityType.DEVICE);
    }

    @Test
    public void assetCreateConsultsTheLicence() {
        assetValidator.validateCreate(TENANT_ID, new Asset());
        verify(licenseService).checkCreateAllowed(TENANT_ID, EntityType.ASSET);
    }

    @Test
    public void deviceCreateIsBlockedWhenTheLicenceRefuses() {
        doThrow(new EntitiesLimitExceededException(TENANT_ID, EntityType.DEVICE, 5000L))
                .when(licenseService).checkCreateAllowed(TENANT_ID, EntityType.DEVICE);
        assertThatThrownBy(() -> deviceValidator.validateCreate(TENANT_ID, new Device()))
                .isInstanceOf(EntitiesLimitExceededException.class)
                .hasFieldOrPropertyWithValue("limit", 5000L);
    }

    @Test
    public void assetCreateIsBlockedWhenTheLicenceRefuses() {
        doThrow(new EntitiesLimitExceededException(TENANT_ID, EntityType.ASSET, 2000L))
                .when(licenseService).checkCreateAllowed(TENANT_ID, EntityType.ASSET);
        assertThatThrownBy(() -> assetValidator.validateCreate(TENANT_ID, new Asset()))
                .isInstanceOf(EntitiesLimitExceededException.class)
                .hasFieldOrPropertyWithValue("limit", 2000L);
    }

    @Test
    public void licenceIsCheckedBeforeTheTenantProfileLimit() {
        // The licence cap is install-wide and therefore the harder stop; it must be reported first.
        doThrow(new EntitiesLimitExceededException(TENANT_ID, EntityType.DEVICE, 1L))
                .when(licenseService).checkCreateAllowed(TENANT_ID, EntityType.DEVICE);
        assertThatThrownBy(() -> deviceValidator.validateCreate(TENANT_ID, new Device()))
                .isInstanceOf(EntitiesLimitExceededException.class);
        // If the licence check ran second, the tenant-profile path would have been consulted first.
        verify(apiLimitService, org.mockito.Mockito.never()).checkEntitiesLimit(any(), any());
    }

    @Test
    public void aPermissiveLicenceDoesNotBlock() {
        assertThatCode(() -> deviceValidator.validateCreate(TENANT_ID, new Device()))
                .doesNotThrowAnyException();
    }
}
