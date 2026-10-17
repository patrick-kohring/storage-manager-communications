-- V1__create_communication_templates.sql
-- Creates the communication_templates table backing the Communication Template API,
-- relocated from storage-manager-api (its V19 migration) into the communications service.
--
-- A Communication Template always carries an owning organization_id (NOT NULL) and
-- MAY optionally be scoped to a single facility_id (nullable). A row with a null
-- facility_id is an Org_Level_Template; a row with a non-null facility_id is a
-- Facility_Level_Template.
--
-- Template-name uniqueness is scoped to the (organization_id, facility_id) pair.
-- Facility-level rows are covered by the composite unique constraint. Because
-- PostgreSQL treats NULLs as distinct in a unique constraint, org-level rows
-- (facility_id IS NULL) are covered by a dedicated partial unique index instead.

CREATE TABLE communication_templates (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    facility_id     UUID,
    template_name   VARCHAR(255) NOT NULL,
    email_content   TEXT,
    sms_content     VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_comm_tmpl_org_facility_name
        UNIQUE (organization_id, facility_id, template_name)
);

-- Org-level uniqueness: enforce one template_name per organization among rows
-- where facility_id IS NULL (the composite constraint above cannot, since NULLs
-- are considered distinct).
CREATE UNIQUE INDEX uq_comm_tmpl_org_name_facility_null
    ON communication_templates (organization_id, template_name)
    WHERE facility_id IS NULL;

-- Supports the org-scoped, newest-first list query.
CREATE INDEX idx_comm_tmpl_org_created_at
    ON communication_templates (organization_id, created_at DESC);
