// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLJsonPGObjectJsonbType;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

import java.util.UUID;

/**
 * A saved controller configuration: the config plane sections, a logic program, or both.
 *
 * <p>Not a {@code BaseSqlEntity} subclass and not an EntityType: a template is a blob the IO
 * Controller pages write and read back, nothing in the platform relates to it, and it is outside
 * export, audit and the permission model beyond its tenant. The columns that are not the payload
 * exist so the picker can list templates without dragging a thousand point records per row.
 */
@Data
@Entity
@Table(name = "inferrix_controller_template")
public class InferrixControllerTemplateEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "created_time")
    private long createdTime;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "name")
    private String name;

    /** The controller this was taken from, as it was named then. Provenance only; never resolved. */
    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "record_count")
    private int recordCount;

    @Column(name = "has_logic")
    private boolean hasLogic;

    @Convert(converter = JsonConverter.class)
    @JdbcType(PostgreSQLJsonPGObjectJsonbType.class)
    @Column(name = "config", columnDefinition = "jsonb")
    private JsonNode config;

    @Convert(converter = JsonConverter.class)
    @JdbcType(PostgreSQLJsonPGObjectJsonbType.class)
    @Column(name = "logic", columnDefinition = "jsonb")
    private JsonNode logic;

}
