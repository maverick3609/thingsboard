// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.entitiy.role;

import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.role.Role;

import java.util.List;

public interface TbRoleService {

    Role save(Role role, User user) throws ThingsboardException;

    void delete(Role role, User user) throws ThingsboardException;

    void updateUserRoles(User targetUser, List<RoleId> roleIds, User user) throws ThingsboardException;

}
