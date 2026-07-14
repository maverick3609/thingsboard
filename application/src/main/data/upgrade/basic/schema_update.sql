--
-- Copyright © 2016-2026 The Thingsboard Authors
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

-- UPDATE TENANT PROFILE CONFIGURATION START

UPDATE tenant_profile
SET profile_data = jsonb_set(
    profile_data,
    '{configuration}',
    jsonb_build_object(
        'minAllowedScheduledUpdateIntervalInSecForCF', 10,
        'maxRelationLevelPerCfArgument', 2,
        'maxRelatedEntitiesToReturnPerCfArgument', 100,
        'minAllowedDeduplicationIntervalInSecForCF', 10,
        'minAllowedAggregationIntervalInSecForCF', 60,
        'intermediateAggregationIntervalInSecForCF', 300,
        'cfReevaluationCheckInterval', 60,
        'alarmsReevaluationInterval', 60
    )
    ||
    jsonb_strip_nulls(profile_data -> 'configuration')
)
WHERE NOT (
    jsonb_strip_nulls(profile_data -> 'configuration') ?& ARRAY[
        'minAllowedScheduledUpdateIntervalInSecForCF',
        'maxRelationLevelPerCfArgument',
        'maxRelatedEntitiesToReturnPerCfArgument',
        'minAllowedDeduplicationIntervalInSecForCF',
        'minAllowedAggregationIntervalInSecForCF',
        'intermediateAggregationIntervalInSecForCF',
        'cfReevaluationCheckInterval',
        'alarmsReevaluationInterval'
    ]
);

-- UPDATE TENANT PROFILE CONFIGURATION END

-- CALCULATED FIELD UNIQUE CONSTRAINT UPDATE START

ALTER TABLE calculated_field DROP CONSTRAINT IF EXISTS calculated_field_unq_key;
ALTER TABLE calculated_field ADD CONSTRAINT calculated_field_unq_key UNIQUE (entity_id, type, name);

-- CALCULATED FIELD UNIQUE CONSTRAINT UPDATE END

-- CALCULATED FIELD OUTPUT STRATEGY UPDATE START

UPDATE calculated_field
SET configuration = jsonb_set(
        configuration::jsonb,
        '{output}',
        (configuration::jsonb -> 'output')
            || jsonb_build_object(
                'strategy',
                jsonb_build_object(
                        'type', 'RULE_CHAIN'
                )
               ),
        false
                    )
WHERE (configuration::jsonb -> 'output' -> 'strategy') IS NULL;

-- CALCULATED FIELD OUTPUT STRATEGY UPDATE END

-- REMOVAL OF CALCULATED FIELD LINKS PERSISTENCE START

DROP TABLE IF EXISTS calculated_field_link;
ANALYZE calculated_field;

-- REMOVAL OF CALCULATED FIELD LINKS PERSISTENCE END

-- WHITE LABELING TABLE START

CREATE TABLE IF NOT EXISTS white_labeling (
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    type varchar(32) NOT NULL,
    settings jsonb,
    domain_id uuid,
    CONSTRAINT white_labeling_pkey PRIMARY KEY (tenant_id, customer_id, type)
);

-- WHITE LABELING TABLE END

-- SCHEDULER EVENT TABLE START

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

-- SCHEDULER EVENT TABLE END
