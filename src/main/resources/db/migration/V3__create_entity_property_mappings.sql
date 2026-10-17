-- V3__create_entity_property_mappings.sql
-- Creates the entity_property_mappings table backing the Entity Property Mapping API,
-- continuing this service's linear Flyway history after V1 (communication_templates)
-- and V2 (messaging_inbox_outbox).
--
-- An Entity Property Mapping translates a JSON property on an upstream domain entity
-- (identified by entity_type, e.g. LEAD) into a kebab-case template placeholder name,
-- for example (LEAD, firstName) -> first-name.
--
-- The (entity_type, placeholder) pair is unique, enforced here by a database-level
-- unique constraint (matching the entity @UniqueConstraint name exactly) and backed
-- by a service-level pre-check. There is deliberately NO unique constraint on
-- json_property, so many placeholders MAY source the same property.
--
-- The table uses only UUID / VARCHAR / TIMESTAMP with two NOT NULL unique-constrained
-- columns, so the DDL runs identically under PostgreSQL (prod) and H2 PostgreSQL mode
-- (tests) with no JSONB domain or partial-index shim.

CREATE TABLE entity_property_mappings (
    id            UUID PRIMARY KEY,
    entity_type   VARCHAR(64) NOT NULL,
    json_property VARCHAR(255) NOT NULL,
    placeholder   VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_entity_prop_mapping_type_placeholder
        UNIQUE (entity_type, placeholder)
);

-- Supports the newest-first list query (findAllByOrderByCreatedAtDesc and
-- findByEntityTypeOrderByCreatedAtDesc). entity_type is leading so the filtered
-- query is also index-supported.
CREATE INDEX idx_entity_prop_mapping_type_created_at
    ON entity_property_mappings (entity_type, created_at DESC);
