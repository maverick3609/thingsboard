// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.license;

import lombok.Getter;

/** Raised on any licence check failure. Every instance is critical: the platform terminates. */
@Getter
public class LicenseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient LicenseViolation violation;

    public LicenseException(LicenseViolation violation) {
        super(violation.getMessage());
        this.violation = violation;
    }

    public LicenseException(LicenseViolation violation, String detail) {
        super(violation.getMessage() + ": " + detail);
        this.violation = violation;
    }

    public LicenseException(LicenseViolation violation, Throwable cause) {
        super(violation.getMessage(), cause);
        this.violation = violation;
    }
}
