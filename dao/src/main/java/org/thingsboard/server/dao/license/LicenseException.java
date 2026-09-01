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
