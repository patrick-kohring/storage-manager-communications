-- V2__messaging_inbox_outbox.sql
-- Applies the storage-manager-messaging enriched reference schema (greenfield shape) as the
-- communications service's own numbered migration (continuing after V1). The starter ships this
-- DDL as a non-executing reference resource and runs no migrations itself; the host owns it here.
--
-- Scope declaration storagemanager.messaging.scope-fields=[organization_id, facility_id] binds
-- POSITIONALLY to the starter entity columns scope_0 (organization_id) and scope_1 (facility_id).
-- This migration uses the PHYSICAL column names scope_0/scope_1 that the OutboxEntry/InboxEntry
-- @Column mappings declare, so hibernate ddl-auto=validate passes without per-column
-- @AttributeOverride wiring (same decision as the api V18 migration).
--
-- BOTH tables are created: the starter @EntityScans InboxEntry AND OutboxEntry unconditionally
-- (entity mapping is NOT gated by inbox.enabled/outbox.enabled), so validate requires BOTH tables
-- to exist. Both halves are enabled here anyway.

CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    scope_0         UUID,   -- organization_id (declared scope field 0)
    scope_1         UUID,   -- facility_id      (declared scope field 1)
    scope_2 UUID, scope_3 UUID, scope_4 UUID, scope_5 UUID, scope_6 UUID, scope_7 UUID,
    scope_8 UUID, scope_9 UUID, scope_10 UUID, scope_11 UUID, scope_12 UUID, scope_13 UUID,
    scope_14 UUID, scope_15 UUID,
    correlation_id  UUID,
    headers         JSONB,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);
CREATE INDEX idx_outbox_unpublished    ON outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_scope_0        ON outbox (scope_0);
CREATE INDEX idx_outbox_scope_1        ON outbox (scope_1);
CREATE INDEX idx_outbox_correlation_id ON outbox (correlation_id);
-- headers is intentionally UNINDEXED.

CREATE TABLE inbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id      UUID NOT NULL,
    payload         JSONB NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE,
    scope_0         UUID,   -- organization_id
    scope_1         UUID,   -- facility_id
    scope_2 UUID, scope_3 UUID, scope_4 UUID, scope_5 UUID, scope_6 UUID, scope_7 UUID,
    scope_8 UUID, scope_9 UUID, scope_10 UUID, scope_11 UUID, scope_12 UUID, scope_13 UUID,
    scope_14 UUID, scope_15 UUID,
    correlation_id  UUID,
    headers         JSONB,
    CONSTRAINT uq_inbox_message_id UNIQUE (message_id)
);
CREATE INDEX idx_inbox_unprocessed     ON inbox (processed_at) WHERE processed_at IS NULL;
CREATE INDEX idx_inbox_scope_0         ON inbox (scope_0);
CREATE INDEX idx_inbox_scope_1         ON inbox (scope_1);
CREATE INDEX idx_inbox_correlation_id  ON inbox (correlation_id);
