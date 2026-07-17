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

--
-- Inferrix schema overlay — single source of truth for all Inferrix-owned DDL.
--
-- Every statement here MUST be idempotent (CREATE ... IF NOT EXISTS, ALTER ... IF NOT EXISTS,
-- guarded DO blocks) because this file is (re)applied on EVERY fresh install AND EVERY upgrade.
--
-- Loaded by three consumers, all reaching this same file:
--   * fresh install  — SqlEntityDatabaseSchemaService.createDatabaseSchema()  (<dataDir>/sql/)
--   * upgrade        — SqlDatabaseUpgradeService.upgradeDatabase()            (<dataDir>/sql/)
--   * DAO tests      — PostgreSqlInitializer / TimescaleSqlInitializer        (classpath sql/)
--
-- Add all future Inferrix tables / columns / indexes below — no upstream ThingsBoard
-- schema file (schema-entities.sql, schema-entities-idx.sql, upgrade/basic/schema_update.sql)
-- needs to change again.
--

-- Scheduler (PE-parity feature) --------------------------------------------------------------

CREATE TABLE IF NOT EXISTS scheduler_event (
    id uuid NOT NULL CONSTRAINT scheduler_event_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    additional_info varchar,
    customer_id uuid,
    originator_id uuid,
    originator_type varchar(255),
    name varchar(255),
    tenant_id uuid,
    type varchar(255),
    schedule varchar,
    configuration varchar(10000000),
    enabled boolean,
    external_id uuid,
    version BIGINT DEFAULT 1,
    CONSTRAINT scheduler_event_external_id_unq_key UNIQUE (tenant_id, external_id)
);

CREATE INDEX IF NOT EXISTS idx_scheduler_event_originator_id ON scheduler_event(tenant_id, originator_id);

-- REPORTING (R1)
CREATE TABLE IF NOT EXISTS report_template (
    id uuid NOT NULL CONSTRAINT report_template_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid,
    name varchar(255) NOT NULL,
    format varchar(32) NOT NULL,
    type varchar(32) NOT NULL,
    description varchar,
    configuration jsonb,
    additional_info varchar,
    external_id uuid,
    version bigint DEFAULT 1,
    CONSTRAINT report_template_external_id_unq_key UNIQUE (tenant_id, external_id)
);
CREATE TABLE IF NOT EXISTS report (
    id uuid NOT NULL CONSTRAINT report_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid,
    template_id uuid,
    format varchar(32) NOT NULL,
    name varchar(255) NOT NULL,
    user_id uuid NOT NULL,
    data bytea
);
CREATE INDEX IF NOT EXISTS idx_report_tenant_id ON report(tenant_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_report_template_id ON report(tenant_id, template_id);
