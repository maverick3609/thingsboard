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
package org.thingsboard.server.service.report.render;

import org.thingsboard.server.common.data.query.AlarmData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.TsValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Small read helpers over the {@code latest} value map that {@link EntityData}/{@link AlarmData} carry, and
 * over an {@link EntityKey} projection list — the subset of PE's {@code common/data} {@code DataSourceUtils}
 * that R2b's shared data layer ({@link AbstractReportService}, {@link ReportQueryUtils}) needs.
 * <p>
 * <b>CE deviation:</b> PE ships {@code DataSourceUtils} in {@code common/data/util} with a wider surface;
 * Inferrix reconstructs only the three methods the report engine uses and keeps them in {@code
 * application/.../service/report/render} so the R2b change set stays inside {@code application} (bar the one
 * {@code ScriptType} enum constant). Semantics are PE-faithful.
 */
public final class DataSourceUtils {

    private DataSourceUtils() {
    }

    /** The {@code name}/{@code label}/… value for {@code key} of {@code keyType} from an entity's latest map, if present and non-null. */
    public static Optional<String> getEntityLatestValue(EntityData entityData, EntityKeyType keyType, String key) {
        return entityData == null ? Optional.empty() : getLatestValue(entityData.getLatest(), keyType, key);
    }

    /** As {@link #getEntityLatestValue} but for an {@link AlarmData}'s originator latest map (same {@code latest} shape). */
    public static Optional<String> getAlarmLatestValue(AlarmData alarmData, EntityKeyType keyType, String key) {
        return alarmData == null ? Optional.empty() : getLatestValue(alarmData.getLatest(), keyType, key);
    }

    private static Optional<String> getLatestValue(Map<EntityKeyType, Map<String, TsValue>> latest, EntityKeyType keyType, String key) {
        if (latest == null) {
            return Optional.empty();
        }
        Map<String, TsValue> byKey = latest.get(keyType);
        if (byKey == null) {
            return Optional.empty();
        }
        TsValue tsValue = byKey.get(key);
        if (tsValue == null || tsValue.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(tsValue.getValue());
    }

    /**
     * Returns a list containing every key in {@code keys} plus {@code (keyType, key)} if it is not already
     * present. Never mutates the input (it may be an immutable {@code Stream.toList()} result — PE calls this
     * to guarantee {@code name}/{@code label} entity fields are always projected).
     */
    public static List<EntityKey> setEntityKeyIfNotExists(List<EntityKey> keys, EntityKeyType keyType, String key) {
        List<EntityKey> result = keys == null ? new ArrayList<>() : new ArrayList<>(keys);
        boolean exists = result.stream().anyMatch(ek -> ek.getType() == keyType && key.equals(ek.getKey()));
        if (!exists) {
            result.add(new EntityKey(keyType, key));
        }
        return result;
    }
}
