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
package org.thingsboard.server.common.data.report.configuration;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.report.configuration.components.ErrorComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks R2a correctness fix #8: config objects use value-equality even when they carry non-value render
 * state. {@link ErrorComponent} excludes its transient {@code exception} from equals/hashCode/toString, and
 * {@link DefaultDataKeySettings} (field-less) compares by value — so two structurally-identical configs are
 * {@code .equals()}.
 */
class ConfigValueEqualityTest {

    @Test
    void errorComponent_excludesExceptionFromEquality() {
        ErrorComponent a = new ErrorComponent("boom", new RuntimeException("stack-a"));
        ErrorComponent b = new ErrorComponent("boom", new IllegalStateException("different-cause"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        // The carried exception must not leak into toString either.
        assertThat(a.toString()).doesNotContain("stack-a");
    }

    @Test
    void defaultDataKeySettings_comparesByValue() {
        assertThat(new DefaultDataKeySettings()).isEqualTo(new DefaultDataKeySettings());
        assertThat(new DefaultDataKeySettings().hashCode()).isEqualTo(new DefaultDataKeySettings().hashCode());
    }

    @Test
    void dataKeyWithDefaultSettings_isEqual() {
        DataKey a = new DataKey("temperature", "timeseries", "Temp");
        a.setSettings(new DefaultDataKeySettings());
        DataKey b = new DataKey("temperature", "timeseries", "Temp");
        b.setSettings(new DefaultDataKeySettings());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
