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
package org.thingsboard.server.service.entitiy.role;

import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.exception.ThingsboardException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DefaultTbRoleServiceTest {

    @Test
    public void testValidPermissionsJson() {
        assertDoesNotThrow(() -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{\"DEVICE\": [\"READ\", \"WRITE\"], \"ALL\": [\"READ\"]}")));
        assertDoesNotThrow(() -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{}")));
        assertDoesNotThrow(() -> DefaultTbRoleService.validatePermissionsJson(null));
        assertDoesNotThrow(() -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{\"ALL\": [\"ALL\"]}")));
    }

    @Test
    public void testInvalidPermissionsJson() {
        // unknown resource
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{\"BOGUS\": [\"READ\"]}")));
        // unknown operation
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{\"DEVICE\": [\"FLY\"]}")));
        // operations not an array
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{\"DEVICE\": \"READ\"}")));
        // non-textual operation entry
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{\"DEVICE\": [42]}")));
    }

}
