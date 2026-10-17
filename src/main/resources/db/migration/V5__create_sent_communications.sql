-- V5__create_sent_communications.sql
-- Creates the sent_communications table backing the send-and-log (Communications API) feature,
-- continuing this service's linear Flyway history after V1 (communication_templates),
-- V2 (messaging_inbox_outbox), V3 (entity_property_mappings), and
-- V4 (add entity_type to communication_templates).
--
-- Each row is one immutable audit entry of a communication the service dispatched. Column
-- nullability matches the SentCommunication entity field designations (Requirement 5) one-for-one
-- so Hibernate ddl-auto=validate passes; a mismatch fails application start.
--
-- Exactly-one-of phone_number/email_address is a request/service invariant (Req 5.8), not a column
-- constraint, so both contact columns are nullable.
--
-- The table uses only UUID / VARCHAR / TIMESTAMP WITH TIME ZONE types, so the DDL runs identically
-- under PostgreSQL (prod) and H2 PostgreSQL mode (tests), matching the V1/V3 convention.

CREATE TABLE sent_communications (
    id                   UUID PRIMARY KEY,
    public_id            VARCHAR(64) NOT NULL,
    facility_id          UUID NOT NULL,
    date_sent            TIMESTAMP WITH TIME ZONE NOT NULL,
    message_content      VARCHAR(10000) NOT NULL,
    message_type         VARCHAR(16) NOT NULL,
    phone_number         VARCHAR(32),
    email_address        VARCHAR(254),
    status               VARCHAR(32) NOT NULL,
    tenant_id            UUID,
    user_id              UUID,
    organization_id      UUID NOT NULL,
    recipient_type       VARCHAR(16),
    email_subject        VARCHAR(255),
    correlation_id       UUID,
    delivery_provider    VARCHAR(64),
    provider_message_id  VARCHAR(255),
    failure_reason       VARCHAR(1000),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_sent_comm_public_id UNIQUE (public_id)
);

-- Supports the newest-first, facility-filtered list query (Requirement 4.1/4.2):
-- facility_id leading for the filter, date_sent DESC for the ordering.
CREATE INDEX idx_sent_comm_facility_date_sent
    ON sent_communications (facility_id, date_sent DESC);
