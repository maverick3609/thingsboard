// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
        // an empty / missing permission set would deny everything for every assignee
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("{}")));
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(null));
        assertThrows(ThingsboardException.class, () -> DefaultTbRoleService.validatePermissionsJson(
                JacksonUtil.toJsonNode("[\"DEVICE\"]")));
    }

}
