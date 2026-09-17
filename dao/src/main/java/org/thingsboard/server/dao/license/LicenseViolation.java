// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.license;

/**
 * A licence check failure and the process exit code it terminates the platform with.
 * Codes start at 13 to stay clear of the 0-2 range Spring Boot and the shell already use.
 */
public enum LicenseViolation {

    NO_KEY(13, "Inferrix licence key is not provided"),
    MALFORMED(14, "Inferrix licence key is malformed"),
    BAD_SIGNATURE(15, "Inferrix licence signature is not valid"),
    WRONG_INSTANCE(16, "Inferrix licence was issued for a different instance"),
    EXPIRED(17, "Inferrix licence has expired"),
    CLOCK_ROLLBACK(18, "System clock moved backwards past the recorded high-water mark");

    private final int exitCode;
    private final String message;

    LicenseViolation(int exitCode, String message) {
        this.exitCode = exitCode;
        this.message = message;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getMessage() {
        return message;
    }
}
