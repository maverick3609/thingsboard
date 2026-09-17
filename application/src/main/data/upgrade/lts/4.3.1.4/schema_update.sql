--
-- SPDX-FileCopyrightText: Copyright The Inferrix Authors
-- SPDX-License-Identifier: Apache-2.0
--

-- Reporting R2a (4.3.1.4) has NO schema change. The report_template.configuration flat->PE-shape rewrite is a
-- pure-Java (Jackson) transform in V4_3_1_4Migration.apply(). This file exists only so the on-disk migration
-- directory matches the registered LtsMigration bean (dir<->bean consistency guard in LtsMigrationIntegrationTest).
-- Intentionally a no-op: a comment-only body is executed by LtsMigrationService.runSchemaUpdate as an empty query.
