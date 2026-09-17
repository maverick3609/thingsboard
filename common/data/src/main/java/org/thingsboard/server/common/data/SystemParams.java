// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.thingsboard.server.common.data.trendz.TrendzSettings;

import java.util.List;

@Data
public class SystemParams {
    boolean userTokenAccessEnabled;
    List<String> allowedDashboardIds;
    boolean edgesSupportEnabled;
    boolean hasRepository;
    boolean tbelEnabled;
    boolean persistDeviceStateToTelemetry;
    JsonNode userSettings;
    long maxDatapointsLimit;
    long maxResourceSize;
    boolean mobileQrEnabled;
    int maxDebugModeDurationMinutes;
    String ruleChainDebugPerTenantLimitsConfiguration;
    String calculatedFieldDebugPerTenantLimitsConfiguration;
    long maxArgumentsPerCF;
    long maxDataPointsPerRollingArg;
    int minAllowedScheduledUpdateIntervalInSecForCF;
    int maxRelationLevelPerCfArgument;
    int maxRelatedEntitiesToReturnPerCfArgument;
    long minAllowedDeduplicationIntervalInSecForCF;
    long minAllowedAggregationIntervalInSecForCF;
    long intermediateAggregationIntervalInSecForCF;
    TrendzSettings trendzSettings;
    String nullsOrderStrategy;
    boolean edqsEnabled;
    String iotHubBaseUrl;
    // Inferrix RBAC: the caller's merged role permissions ({"genericPermissions": {...}}), or null
    // for a role-less user (legacy full access). Carried here so the UI can hide what a role
    // denies without a second request; the server remains the enforcer.
    JsonNode userPermissions;
}
