// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.wl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.DomainId;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginWhiteLabelingParams extends WhiteLabelingParams {
    private String pageBackgroundColor;
    private boolean darkForeground;
    private Boolean showNameBottom;
    private String loginCardColor;
    private DomainId domainId;
    private String baseUrl;

    public LoginWhiteLabelingParams(LoginWhiteLabelingParams other) {
        super(other);
        if (other == null) return;
        this.pageBackgroundColor = other.pageBackgroundColor;
        this.darkForeground = other.darkForeground;
        this.showNameBottom = other.showNameBottom;
        this.loginCardColor = other.loginCardColor;
        this.domainId = other.domainId;
        this.baseUrl = other.baseUrl;
    }

    public void merge(LoginWhiteLabelingParams parent) {
        if (parent == null) return;
        super.merge(parent);
        if (this.pageBackgroundColor == null) this.pageBackgroundColor = parent.pageBackgroundColor;
        if (this.showNameBottom == null) this.showNameBottom = parent.showNameBottom;
        if (this.loginCardColor == null) this.loginCardColor = parent.loginCardColor;
        if (this.domainId == null) this.domainId = parent.domainId;
        if (this.baseUrl == null) this.baseUrl = parent.baseUrl;
    }

    public static LoginWhiteLabelingParams defaultLoginParams() {
        LoginWhiteLabelingParams params = new LoginWhiteLabelingParams();
        params.setHelpLinkBaseUrl("https://thingsboard.io");
        params.setEnableHelpLinks(true);
        params.setWhiteLabelingEnabled(true);
        return params;
    }
}
