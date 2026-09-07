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
package org.thingsboard.server.service.inferrix;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.settings.AdminSettingsService;
import org.thingsboard.server.service.entitiy.device.TbDeviceService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The discovery listener is off by default, so adoption must not hard-depend on it. It used to, and
 * that fails the entire application context on a default boot — loud, but only once the build is on
 * a server. This keeps the failure here instead.
 */
class InferrixControllerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class)
            .withBean(InferrixControllerClient.class)
            .withBean(InferrixAdoptionService.class)
            .withBean(InferrixControllerAccess.class)
            .withBean(InferrixUploadService.class)
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of())
            // @TbCoreComponent is a ConditionalOnExpression on service.type; without it these
            // beans are filtered out and the test would prove nothing.
            .withPropertyValues("service.type=monolith", "inferrix.controller.credentials_key=");

    @Test
    void adoptionStartsWithDiscoveryDisabledWhichIsTheDefault() {
        runner.withUserConfiguration(DiscoveryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InferrixAdoptionService.class);
            assertThat(context).doesNotHaveBean(InferrixDiscoveryService.class);
        });
    }

    @Test
    void adoptionAlsoStartsWithDiscoveryEnabled() {
        runner.withUserConfiguration(DiscoveryConfig.class)
                .withPropertyValues("inferrix.controller.discovery.enabled=true",
                        "inferrix.controller.discovery.bind_address=127.0.0.1",
                        "inferrix.controller.discovery.port=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InferrixDiscoveryService.class);
                    assertThat(context).hasSingleBean(InferrixAdoptionService.class);
                });
    }

    @Test
    void theUploadServiceStartsWithoutDiscovery() {
        // Firmware and logic uploads are a first-class endpoint rather than a proxied route, so a
        // missing bean here is a 500 on the upload endpoint rather than a startup failure — exactly
        // the kind of thing that is only noticed on a server.
        runner.withUserConfiguration(DiscoveryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InferrixUploadService.class);
        });
    }

    @Test
    void aBlankKeyLeavesTheCodecUnconfiguredRatherThanFailingStartup() {
        // The platform must boot without a credentials key; only adoption itself is unavailable.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InferrixSecretCodec.class).isConfigured()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        InferrixSecretCodec secretCodec() {
            return new InferrixSecretCodec("");
        }

        @Bean
        DeviceService deviceService() {
            return mock(DeviceService.class);
        }

        @Bean
        DeviceCredentialsService deviceCredentialsService() {
            return mock(DeviceCredentialsService.class);
        }

        @Bean
        DeviceProfileService deviceProfileService() {
            return mock(DeviceProfileService.class);
        }

        @Bean
        AttributesService attributesService() {
            return mock(AttributesService.class);
        }

        @Bean
        TbDeviceService tbDeviceService() {
            return mock(TbDeviceService.class);
        }

        @Bean
        AdminSettingsService adminSettingsService() {
            return mock(AdminSettingsService.class);
        }

        @Bean
        TelemetrySubscriptionService telemetrySubscriptionService() {
            return mock(TelemetrySubscriptionService.class);
        }
    }

    /** Mirrors the condition on the real service so the default-off behaviour is what is tested. */
    @Configuration(proxyBeanMethods = false)
    static class DiscoveryConfig {

        @Bean(initMethod = "start", destroyMethod = "stop")
        @ConditionalOnProperty(prefix = "inferrix.controller.discovery", value = "enabled", havingValue = "true")
        InferrixDiscoveryService discoveryService() {
            return new InferrixDiscoveryService();
        }
    }

}
