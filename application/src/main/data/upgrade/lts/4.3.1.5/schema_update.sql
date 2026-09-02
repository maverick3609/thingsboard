--
-- Copyright © 2016-2026 The Inferrix Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
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
