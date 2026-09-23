// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.service.inferrix.InferrixSecretCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A bean that cannot be constructed fails the entire application context, which is loud but only
 * once the build is on a server. This keeps that failure here.
 *
 * <p>The specific thing worth pinning: the gateway beans must start with
 * {@code inferrix.controller.credentials_key} <b>unset</b>. That is the default, and a platform
 * that refuses to boot until an operator sets a key would be a worse failure than the one the key
 * exists to prevent. Refusal belongs at the point a credential is actually opened — where it is a
 * clear error about one gateway — not at startup, where it is an outage.
 */
class InferrixGatewayWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class)
            .withBean(InferrixGatewayClient.class)
            .withBean(InferrixGatewayAccess.class)
            // @TbCoreComponent is a ConditionalOnExpression on service.type; without it these beans
            // are filtered out and the test would prove nothing.
            .withPropertyValues("service.type=monolith", "inferrix.controller.credentials_key=");

    @Test
    void theGatewayBeansStartWithNoSealingKeyConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InferrixGatewayAccess.class);
            assertThat(context).hasSingleBean(InferrixGatewayClient.class);
        });
    }

    @Test
    void theGatewayBeansStartWithASealingKeyConfigured() {
        runner.withPropertyValues(
                        "inferrix.controller.credentials_key=" + "A".repeat(43) + "=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InferrixGatewayAccess.class);
                });
    }

    @Configuration
    static class Collaborators {

        @Bean
        AttributesService attributesService() {
            return mock(AttributesService.class);
        }

        @Bean
        InferrixSecretCodec secretCodec() {
            // The gateway shares the controller's sealing primitive rather than introducing a
            // second key: a separate one adds an operator prerequisite and buys nothing, since
            // anything holding the yml holds both.
            return new InferrixSecretCodec("");
        }
    }
}
