--
-- SPDX-FileCopyrightText: Copyright The Inferrix Authors
-- SPDX-License-Identifier: Apache-2.0
--

-- INFERRIX LICENSE STATE START
-- Upgrade path for the licence state table. The identical statement lives in schema-inferrix.sql,
-- which covers fresh installs; LTS migrations do not run on a fresh install. Duplicating the DDL
-- across both paths is the established pattern here (see iot_hub_installed_item in schema-entities.sql
-- and lts/4.2.2.3 + lts/4.3.1.3). Both are IF NOT EXISTS, so running both is harmless.
CREATE TABLE IF NOT EXISTS inferrix_license_state (
    singleton     boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    instance_id   uuid   NOT NULL,
    high_water_ts bigint NOT NULL DEFAULT 0
);
-- INFERRIX LICENSE STATE END
