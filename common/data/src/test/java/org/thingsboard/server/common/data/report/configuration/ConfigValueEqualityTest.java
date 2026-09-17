// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
