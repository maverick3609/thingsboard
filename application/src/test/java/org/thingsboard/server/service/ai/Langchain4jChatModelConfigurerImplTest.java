/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
package org.thingsboard.server.service.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.thingsboard.common.util.SsrfProtectionValidator;
import org.thingsboard.server.common.data.ai.model.chat.AzureOpenAiChatModelConfig;
import org.thingsboard.server.common.data.ai.model.chat.OllamaChatModelConfig;
import org.thingsboard.server.common.data.ai.model.chat.OpenAiChatModelConfig;
import org.thingsboard.server.common.data.ai.provider.AzureOpenAiProviderConfig;
import org.thingsboard.server.common.data.ai.provider.OllamaProviderConfig;
import org.thingsboard.server.common.data.ai.provider.OpenAiProviderConfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock("SsrfProtectionValidator")
class Langchain4jChatModelConfigurerImplTest {

    private final Langchain4jChatModelConfigurerImpl configurer = new Langchain4jChatModelConfigurerImpl();

    @BeforeEach
    void enableSsrfProtection() {
        SsrfProtectionValidator.setEnabled(true);
    }

    @AfterEach
    void disableSsrfProtection() {
        SsrfProtectionValidator.setEnabled(false);
    }

    @Test
    void configureChatModel_openAi_withPrivateIp_shouldThrow() {
        var config = OpenAiChatModelConfig.builder()
                .providerConfig(OpenAiProviderConfig.builder()
                        .baseUrl("http://172.17.0.1:8080/")
                        .apiKey("test")
                        .build())
                .modelId("gpt-4o")
                .build();

        assertThatThrownBy(() -> configurer.configureChatModel(config))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URI is invalid");
    }

    @Test
    void configureChatModel_openAi_withLocalhostUrl_shouldThrow() {
        var config = OpenAiChatModelConfig.builder()
                .providerConfig(OpenAiProviderConfig.builder()
                        .baseUrl("http://localhost:22/")
                        .apiKey("test")
                        .build())
                .modelId("gpt-4o")
                .build();

        assertThatThrownBy(() -> configurer.configureChatModel(config))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URI is invalid");
    }

    @Test
    void configureChatModel_azureOpenAi_withPrivateIp_shouldThrow() {
        var config = AzureOpenAiChatModelConfig.builder()
                .providerConfig(new AzureOpenAiProviderConfig(
                        "http://10.0.0.1:8080/", null, "test-key"))
                .modelId("gpt-4o")
                .build();

        assertThatThrownBy(() -> configurer.configureChatModel(config))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URI is invalid");
    }

    @Test
    void configureChatModel_ollama_withPrivateIp_shouldThrow() {
        var config = OllamaChatModelConfig.builder()
                .providerConfig(new OllamaProviderConfig(
                        "http://192.168.1.100:11434/", new OllamaProviderConfig.OllamaAuth.None()))
                .modelId("llama3")
                .build();

        assertThatThrownBy(() -> configurer.configureChatModel(config))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URI is invalid");
    }

}
