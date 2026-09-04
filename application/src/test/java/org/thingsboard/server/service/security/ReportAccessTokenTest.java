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
package org.thingsboard.server.service.security;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.MailService;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.UserAuthDetails;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.security.model.JwtSettings;
import org.thingsboard.server.dao.audit.AuditLogService;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.settings.AdminSettingsService;
import org.thingsboard.server.dao.settings.SecuritySettingsService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.security.auth.jwt.settings.DefaultJwtSettingsService;
import org.thingsboard.server.service.security.auth.jwt.settings.DefaultJwtSettingsValidator;
import org.thingsboard.server.service.security.auth.jwt.settings.JwtSettingsService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.permission.UserPermissionsService;
import org.thingsboard.server.service.security.system.DefaultSystemSecurityService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers Task 10: server-minted user access tokens used by the report renderer to log the
 * headless browser in AS the report's configured user. Verifies the token
 * {@link DefaultSystemSecurityService#createUserAccessToken(TenantId, UserId)} and
 * {@link DefaultSystemSecurityService#createUserAccessTokenFromPublicId(TenantId, String)} mint
 * round-trips through the real {@link JwtTokenFactory} back to the correct principal.
 */
@ExtendWith(MockitoExtension.class)
class ReportAccessTokenTest {

    @Mock
    private AdminSettingsService adminSettingsService;
    @Mock
    private BCryptPasswordEncoder encoder;
    @Mock
    private UserService userService;
    @Mock
    private MailService mailService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private SecuritySettingsService securitySettingsService;
    @Mock
    private CustomerService customerService;

    private JwtTokenFactory jwtTokenFactory;
    private DefaultSystemSecurityService systemSecurityService;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        JwtSettings jwtSettings = new JwtSettings();
        jwtSettings.setTokenIssuer("tb");
        jwtSettings.setTokenSigningKey(Base64.getEncoder().encodeToString(
                RandomStringUtils.secure().nextAlphanumeric(64).getBytes(StandardCharsets.UTF_8)));
        jwtSettings.setTokenExpirationTime((int) TimeUnit.HOURS.toSeconds(2));
        jwtSettings.setRefreshTokenExpTime((int) TimeUnit.DAYS.toSeconds(7));

        AdminSettings adminJwtSettings = new AdminSettings();
        adminJwtSettings.setJsonValue(JacksonUtil.valueToTree(jwtSettings));
        when(adminSettingsService.findAdminSettingsByKey(TenantId.SYS_TENANT_ID, JwtSettingsService.ADMIN_SETTINGS_JWT_KEY))
                .thenReturn(adminJwtSettings);

        JwtSettingsService jwtSettingsService = new DefaultJwtSettingsService(
                adminSettingsService, Optional.empty(), new DefaultJwtSettingsValidator(), Optional.empty());
        jwtTokenFactory = new JwtTokenFactory(jwtSettingsService, mock(UserPermissionsService.class));

        systemSecurityService = new DefaultSystemSecurityService(adminSettingsService, encoder, userService,
                mailService, auditLogService, securitySettingsService, customerService, jwtTokenFactory);
    }

    @Test
    void mintedTokenCarriesUserId() {
        User user = new User(userId);
        user.setTenantId(tenantId);
        user.setEmail("report-viewer@example.com");
        user.setFirstName("Report");
        user.setLastName("Viewer");
        user.setAuthority(Authority.TENANT_ADMIN);
        when(userService.findUserAuthDetailsByUserId(tenantId, userId)).thenReturn(new UserAuthDetails(user, true));

        String token = systemSecurityService.createUserAccessToken(tenantId, userId);
        SecurityUser parsed = jwtTokenFactory.parseAccessJwtToken(token);

        assertThat(parsed.getId()).isEqualTo(userId);
        assertThat(parsed.getTenantId()).isEqualTo(tenantId);
        assertThat(parsed.getAuthority()).isEqualTo(Authority.TENANT_ADMIN);
        assertThat(parsed.getEmail()).isEqualTo("report-viewer@example.com");
    }

    @Test
    void mintedPublicTokenCarriesCustomerId() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        Customer publicCustomer = new Customer(customerId);
        publicCustomer.setTenantId(tenantId);
        publicCustomer.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(customerService.findCustomerById(tenantId, customerId)).thenReturn(publicCustomer);

        String token = systemSecurityService.createUserAccessTokenFromPublicId(tenantId, customerId.toString());
        SecurityUser parsed = jwtTokenFactory.parseAccessJwtToken(token);

        assertThat(parsed.getTenantId()).isEqualTo(tenantId);
        assertThat(parsed.getCustomerId()).isEqualTo(customerId);
        assertThat(parsed.getAuthority()).isEqualTo(Authority.CUSTOMER_USER);
    }

}
