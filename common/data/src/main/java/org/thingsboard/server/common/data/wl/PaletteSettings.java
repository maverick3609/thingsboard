// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.wl;

import lombok.Data;
import java.io.Serializable;

@Data
public class PaletteSettings implements Serializable {
    private Palette primaryPalette;
    private Palette accentPalette;

    public void merge(PaletteSettings parent) {
        if (parent == null) return;
        if (this.primaryPalette == null) this.primaryPalette = parent.primaryPalette;
        if (this.accentPalette == null) this.accentPalette = parent.accentPalette;
    }
}
