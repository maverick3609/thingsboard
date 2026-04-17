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
package org.thingsboard.server.common.data.wl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhiteLabelingParams implements Serializable {
    private String logoImageUrl;
    private Integer logoImageHeight;
    private String appTitle;
    private Favicon favicon;
    private PaletteSettings paletteSettings;
    private String helpLinkBaseUrl;
    private String uiHelpBaseUrl;
    private Boolean enableHelpLinks;
    private boolean whiteLabelingEnabled = true;
    private Boolean showNameVersion;
    private String platformName;
    private String platformVersion;
    private String customCss;
    private Boolean hideConnectivityDialog;

    public WhiteLabelingParams(WhiteLabelingParams other) {
        if (other == null) return;
        this.logoImageUrl = other.logoImageUrl;
        this.logoImageHeight = other.logoImageHeight;
        this.appTitle = other.appTitle;
        this.favicon = other.favicon;
        this.paletteSettings = other.paletteSettings;
        this.helpLinkBaseUrl = other.helpLinkBaseUrl;
        this.uiHelpBaseUrl = other.uiHelpBaseUrl;
        this.enableHelpLinks = other.enableHelpLinks;
        this.whiteLabelingEnabled = other.whiteLabelingEnabled;
        this.showNameVersion = other.showNameVersion;
        this.platformName = other.platformName;
        this.platformVersion = other.platformVersion;
        this.customCss = other.customCss;
        this.hideConnectivityDialog = other.hideConnectivityDialog;
    }

    public void merge(WhiteLabelingParams parent) {
        if (parent == null) return;
        if (this.logoImageUrl == null) this.logoImageUrl = parent.logoImageUrl;
        if (this.logoImageHeight == null) this.logoImageHeight = parent.logoImageHeight;
        if (this.appTitle == null) this.appTitle = parent.appTitle;
        if (this.favicon == null) this.favicon = parent.favicon;
        if (this.helpLinkBaseUrl == null) this.helpLinkBaseUrl = parent.helpLinkBaseUrl;
        if (this.uiHelpBaseUrl == null) this.uiHelpBaseUrl = parent.uiHelpBaseUrl;
        if (this.enableHelpLinks == null) this.enableHelpLinks = parent.enableHelpLinks;
        if (this.showNameVersion == null) this.showNameVersion = parent.showNameVersion;
        if (this.platformName == null) this.platformName = parent.platformName;
        if (this.platformVersion == null) this.platformVersion = parent.platformVersion;
        if (this.customCss == null) this.customCss = parent.customCss;
        if (this.hideConnectivityDialog == null) this.hideConnectivityDialog = parent.hideConnectivityDialog;
        if (this.paletteSettings == null) {
            this.paletteSettings = parent.paletteSettings;
        } else if (parent.paletteSettings != null) {
            this.paletteSettings.merge(parent.paletteSettings);
        }
    }

    public static WhiteLabelingParams defaultParams() {
        WhiteLabelingParams params = new WhiteLabelingParams();
        params.setHelpLinkBaseUrl("https://thingsboard.io");
        params.setEnableHelpLinks(true);
        params.setWhiteLabelingEnabled(true);
        return params;
    }
}
