// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.script.api;

public enum ScriptType {
    RULE_NODE_SCRIPT, CALCULATED_FIELD_SCRIPT,
    // Inferrix (reporting R2b): per-type identity for report data-key post-processing scripts (its own
    // stats/limits + arg names ["time","value"]). Faithful to PE's ScriptType.REPORT_DATA_KEY_SCRIPT.
    REPORT_DATA_KEY_SCRIPT
}
