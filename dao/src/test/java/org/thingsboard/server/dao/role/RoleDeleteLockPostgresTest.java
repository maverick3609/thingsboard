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
package org.thingsboard.server.dao.role;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lock semantics {@code BaseRoleService.deleteRoleAndCollectAssignees} rests on, exercised
 * against a real PostgreSQL over two connections. The unit tests can only assert that the calls
 * are made in the right order; whether {@code FOR UPDATE} actually keeps a concurrent assignment
 * out, and whether the two paths deadlock, is a property of the database.
 *
 * <p>The table definitions are read from the shipping overlay rather than restated here, so a
 * change to the real foreign key cannot leave these passing against a schema nobody runs.
 *
 * <p>Skipped when there is no Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
public class RoleDeleteLockPostgresTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static PostgreSQLContainer<?> postgres;

    private UUID roleId;
    private UUID holderId;
    private UUID newcomerId;
    private ExecutorService executor;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:15");
        postgres.start();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tb_user (id uuid PRIMARY KEY)");
            statement.execute(createTable("role"));
            statement.execute(createTable("user_role"));
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        roleId = UUID.randomUUID();
        holderId = UUID.randomUUID();
        newcomerId = UUID.randomUUID();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM user_role");
            statement.execute("DELETE FROM role");
            statement.execute("DELETE FROM tb_user");
            statement.execute("INSERT INTO tb_user (id) VALUES ('" + holderId + "'), ('" + newcomerId + "')");
            statement.execute("INSERT INTO role (id, created_time, tenant_id, name, type) VALUES ('"
                    + roleId + "', 1, '" + TENANT_ID + "', 'operators', 'GENERIC')");
            statement.execute("INSERT INTO user_role (user_id, role_id, tenant_id, created_time) VALUES ('"
                    + holderId + "', '" + roleId + "', '" + TENANT_ID + "', 1)");
        }
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    /**
     * The claim the whole fix rests on: an assignment cannot commit while the role is locked, so
     * the assignee list read under that lock cannot go stale before the delete.
     */
    @Test
    public void testTheLockKeepsAConcurrentAssignmentOutAndTheListComplete() throws Exception {
        try (Connection deleter = transaction(); Connection assigner = transaction()) {
            assertTrue(lockRole(deleter, roleId), "the role exists, so the lock finds it");
            List<UUID> assignees = assignees(deleter, roleId);
            assertEquals(List.of(holderId), assignees);

            Future<SQLException> assignment = executor.submit(() -> assign(assigner, newcomerId, roleId));
            // blocked: the insert's foreign key check wants FOR KEY SHARE on the locked role row
            assertThrows(TimeoutException.class, () -> assignment.get(1500, TimeUnit.MILLISECONDS));

            deleteRole(deleter, roleId);
            deleter.commit();

            SQLException failure = assignment.get(10, TimeUnit.SECONDS);
            assertNotNull(failure, "assigning a role that was just deleted must not succeed");
            assertEquals("23503", failure.getSQLState(), "foreign key violation");
            assigner.rollback();
        }
        // the newcomer never held the role, so the list the delete collected was complete
        assertEquals(List.of(), assigneesOfDeletedRole());
    }

    /**
     * Without the lock the same interleaving loses the newcomer: they are assigned, then cascaded
     * off by the delete, and never appear in the list anyone evicts.
     */
    @Test
    public void testWithoutTheLockTheAssigneeListGoesStale() throws Exception {
        try (Connection deleter = transaction(); Connection assigner = transaction()) {
            List<UUID> assignees = assignees(deleter, roleId);
            assertEquals(List.of(holderId), assignees);

            assertNull(assign(assigner, newcomerId, roleId), "nothing blocks the assignment");
            assigner.commit();

            deleteRole(deleter, roleId);
            deleter.commit();

            // the newcomer held the role, was cascaded off it, and is missing from the list
            assertFalse(assignees.contains(newcomerId));
        }
    }

    /**
     * The order the assignment path used before: user row, then its assignment rows, then the role
     * through the foreign key. Against a delete that takes the role first, that is a cycle.
     */
    @Test
    public void testTheOldLockOrderDeadlocks() throws Exception {
        try (Connection deleter = transaction(); Connection assigner = transaction()) {
            assertTrue(lockRole(deleter, roleId));
            lockUser(assigner, holderId);
            clearAssignments(assigner, holderId);

            Future<SQLException> delete = executor.submit(() -> deleteRoleForResult(deleter, roleId));
            // waits on the assignment row the other transaction just deleted
            assertThrows(TimeoutException.class, () -> delete.get(1000, TimeUnit.MILLISECONDS));

            Future<SQLException> assignment = executor.submit(() -> assign(assigner, holderId, roleId));

            SQLException deleteFailure = delete.get(20, TimeUnit.SECONDS);
            SQLException assignmentFailure = assignment.get(20, TimeUnit.SECONDS);
            assertTrue(isDeadlock(deleteFailure) || isDeadlock(assignmentFailure),
                    "one of the two must be chosen as the deadlock victim");
            rollbackQuietly(deleter);
            rollbackQuietly(assigner);
        }
    }

    /**
     * The order the assignment path takes now: the roles it is about to assign, before it touches
     * any assignment row. Same interleaving, no cycle — the delete simply waits its turn, and the
     * user assigned in the meantime is in the list it then reads.
     */
    @Test
    public void testTheNewLockOrderDoesNotDeadlockAndStillSeesTheNewAssignment() throws Exception {
        try (Connection deleter = transaction(); Connection assigner = transaction()) {
            lockUser(assigner, newcomerId);
            assertTrue(lockRole(assigner, roleId));

            Future<SQLException> delete = executor.submit(() -> lockRoleForResult(deleter, roleId));
            // the delete waits for the role, which is a wait and not a cycle
            assertThrows(TimeoutException.class, () -> delete.get(1000, TimeUnit.MILLISECONDS));

            clearAssignments(assigner, newcomerId);
            assertNull(assign(assigner, newcomerId, roleId));
            assigner.commit();

            assertNull(delete.get(20, TimeUnit.SECONDS), "the delete must acquire the lock, not deadlock");
            List<UUID> assignees = assignees(deleter, roleId);
            assertTrue(assignees.contains(newcomerId), "the assignment that won the race is in the list");
            assertTrue(assignees.contains(holderId));
            deleteRole(deleter, roleId);
            deleter.commit();
        }
    }

    // --- the statements the production code issues -------------------------------------------

    private static boolean lockRole(Connection connection, UUID roleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM role WHERE id = ? AND tenant_id = ? FOR UPDATE")) {
            statement.setObject(1, roleId);
            statement.setObject(2, TENANT_ID);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void lockUser(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tb_user WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, userId);
            statement.executeQuery().close();
        }
    }

    private static List<UUID> assignees(Connection connection, UUID roleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM user_role WHERE role_id = ? ORDER BY user_id")) {
            statement.setObject(1, roleId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UUID> userIds = new ArrayList<>();
                while (rs.next()) {
                    userIds.add(rs.getObject(1, UUID.class));
                }
                return userIds;
            }
        }
    }

    private static void clearAssignments(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM user_role WHERE user_id = ?")) {
            statement.setObject(1, userId);
            statement.executeUpdate();
        }
    }

    private static SQLException assign(Connection connection, UUID userId, UUID roleId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_role (user_id, role_id, tenant_id, created_time) VALUES (?, ?, ?, 1)")) {
            statement.setObject(1, userId);
            statement.setObject(2, roleId);
            statement.setObject(3, TENANT_ID);
            statement.executeUpdate();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static void deleteRole(Connection connection, UUID roleId) throws SQLException {
        SQLException failure = deleteRoleForResult(connection, roleId);
        if (failure != null) {
            throw failure;
        }
    }

    private static SQLException deleteRoleForResult(Connection connection, UUID roleId) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM role WHERE id = ?")) {
            statement.setObject(1, roleId);
            statement.executeUpdate();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static SQLException lockRoleForResult(Connection connection, UUID roleId) {
        try {
            lockRole(connection, roleId);
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    // --- plumbing -----------------------------------------------------------------------------

    private List<UUID> assigneesOfDeletedRole() throws Exception {
        try (Connection connection = connect()) {
            return assignees(connection, roleId);
        }
    }

    private static boolean isDeadlock(SQLException e) {
        return e != null && "40P01".equals(e.getSQLState());
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection transaction() throws SQLException {
        Connection connection = connect();
        connection.setAutoCommit(false);
        return connection;
    }

    /**
     * Pulls one CREATE TABLE out of the shipping overlay, so these tests run against the real
     * foreign key rather than a restatement of it that could drift.
     */
    private static String createTable(String table) throws IOException {
        try (InputStream in = RoleDeleteLockPostgresTest.class.getResourceAsStream("/sql/schema-inferrix.sql")) {
            assertNotNull(in, "schema-inferrix.sql must be on the test classpath");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(
                    "CREATE TABLE IF NOT EXISTS " + table + "\\s*\\((?:[^;])*\\);", Pattern.CASE_INSENSITIVE).matcher(sql);
            assertTrue(matcher.find(), "no CREATE TABLE for " + table + " in the overlay");
            return matcher.group();
        }
    }

}
