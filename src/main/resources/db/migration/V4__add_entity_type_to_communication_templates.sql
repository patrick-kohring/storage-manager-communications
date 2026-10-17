-- V4__add_entity_type_to_communication_templates.sql
-- Adds the nullable entity-type association to communication_templates.
--
-- A Communication Template MAY be associated with a single upstream domain entity type
-- (EntityType, e.g. LEAD) so that template content placeholders can be validated against the
-- EntityPropertyMapping placeholders defined for that type. The column is NULLABLE: a null value
-- denotes a template with no entity-type association, which (per the placeholder-validation rules)
-- must then contain no {{...}} placeholders.
--
-- Continues this service's linear Flyway history after V1 (communication_templates),
-- V2 (messaging_inbox_outbox), and V3 (entity_property_mappings). The plain ADD COLUMN carries
-- no DEFAULT, no NOT NULL, and no backfill, so existing rows keep a null entity_type and the
-- statement runs identically under PostgreSQL (prod) and H2 PostgreSQL mode (tests).

ALTER TABLE communication_templates ADD COLUMN entity_type VARCHAR(64);
