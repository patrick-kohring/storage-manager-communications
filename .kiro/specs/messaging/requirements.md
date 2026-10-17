# Requirements Document

## Introduction

This document captures the requirements for adding the **messaging base** to the existing `storage-manager-communications` service by adopting the shared `storage-manager-messaging` starter.

The goal of this spec is narrow and deliberate: stand up the generic messaging mechanics end-to-end and prove they work, without any domain send behavior. Concretely, the Communications_Service enables **both** starter halves and wires them generically:

- **Inbox half**: a generic ingest endpoint accepts a canonical `EventEnvelope`, validates it, and records it idempotently in the inbox keyed on `eventId = message_id`. A **no-op / pass-through handler** is invoked through the starter's `processOnce` semantics so that the dedupe path is exercised without interpreting the message.
- **Outbox half**: the Outbox_Writer, Outbox_Publisher, and a host-provided Outbox_Transport are wired so that an appended Outbox row is published generically to the events ingest API with retry and backoff.

The central correctness goal is that the **messaging base works end-to-end**: receive -> dedupe -> no-op on the inbound side, and append-to-outbox -> publish -> transport on the outbound side, while the service still boots and validates (`ddl-auto=validate` passes).

**Explicitly OUT OF SCOPE and deferred to a follow-on communications spec:** all actual message-sending behavior. That includes `SendCommunication` command payload interpretation, recipient resolution, the real send provider (and the provider seam), send records, send-result-coupled outcome event semantics (`CommunicationSent` / `CommunicationFailed`), template rendering, and channel (`EMAIL` / `SMS`) semantics. In this base scope the ingest endpoint accepts a **generic** `EventEnvelope` and does **not** branch on or require any specific `eventType` value, and the outbox is exercised **generically**, not driven by a send result. The events-side `LeadCreated` reaction workflow also remains out of scope and is owned by a separate future spec.

## Glossary

- **Communications_Service**: The `storage-manager-communications` Spring Boot application (server port 8083, database `storage_manager_communications`), acting here as both a generic inbox consumer and a generic outbox producer.
- **Messaging_Starter**: The shared `com.storagemanager:storage-manager-messaging` library providing the transactional outbox, scheduled publisher, idempotent inbox, and envelope-enrichment mechanics. Referred to as the **Starter**.
- **Outbox**: A table in the Communications_Service database that durably records events to be published, written in the same transaction as the caller's work.
- **Inbox**: A table in the Communications_Service database that records inbound messages that have been received, used to deduplicate redeliveries.
- **Outbox_Writer**: The Starter-provided bean that appends an Outbox row within the caller's existing database transaction using `MANDATORY` propagation.
- **Outbox_Publisher**: The Starter-provided scheduled bean that polls the Outbox for unpublished rows and delivers them through the Outbox_Transport with retry and backoff.
- **Outbox_Transport**: The Starter-defined interface the Communications_Service implements to deliver an EventEnvelope to the events ingest API, returning a Transport_Result.
- **Transport_Result**: The Starter result type with the cases Success, Retryable_Failure, and Permanent_Failure that the Outbox_Publisher acts on.
- **Idempotent_Inbox**: The Starter-provided bean exposing `processOnce(messageId, handler)` semantics that deduplicates inbound messages on a stable `message_id` and stamps `processed_at` only on successful handler completion.
- **No_Op_Handler**: The pass-through handler supplied by the Communications_Service to `processOnce` in this base scope. It performs no domain work and completes successfully without side effects, so that the dedupe and `processed_at` stamping path is exercised.
- **EventEnvelope**: The events-canonical 13-field wire message: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `organizationId`, `facilityId`, `entityType`, `entityId`, `actorType` (SYSTEM|USER), `actorId` (nullable), `correlationId` (nullable), `causationId` (nullable), `payload`.
- **Ingest_Endpoint**: The HTTP endpoint `POST /api/v1/communications` exposed by the Communications_Service that receives a generic EventEnvelope. (Recommended default path; see Decision D1.)
- **Idempotency_Key**: A single stable identifier that originates with a message and is preserved across hops. Realized as the EventEnvelope `eventId` and used as the Inbox `message_id` on receive, and as the Outbox `event_id` on emission.
- **Ingest_Scope**: The OAuth2 scope value `events/ingest` carried in a JWT scope claim, identifying the trusted first-party caller. Mapped to the authority `SCOPE_events/ingest`.
- **Trusted_Ingest_Caller**: A caller whose validated JWT carries the Ingest_Scope. The only identity permitted to assert an arbitrary envelope `organizationId`.
- **End_User_Token**: A validated JWT that does not carry the Ingest_Scope and that carries an `organizationId` claim, representing an interactive user confined to that organization.
- **Organization_Scope**: The `organization_id` value identifying the owning organization, carried from the inbound envelope onto any enriched rows.
- **Facility_Scope**: The optional `facility_id` value identifying the owning facility, carried the same way as Organization_Scope.
- **Host_Migration**: A versioned Flyway migration owned and numbered by the Communications_Service (continuing after `V1`, i.e. `V2+`) that creates the Inbox and Outbox tables.
- **Reference_Schema**: The Starter's non-executing enriched inbox/outbox DDL (with `scope_0`..`scope_15`, `correlation_id`, `headers` JSONB, `payload` JSONB, and mechanics columns) that the Host_Migration mirrors.

## Requirements

### Requirement 1: Starter Adoption and Dual-Half Configuration

**User Story:** As a Communications_Service developer, I want the service to adopt the messaging starter configured with both the inbox and outbox halves enabled, so that it can receive and dedupe generic messages and publish generic outbox events without re-implementing messaging mechanics.

#### Acceptance Criteria

1. THE Communications_Service SHALL declare a Maven dependency on `com.storagemanager:storage-manager-messaging`.
2. THE Communications_Service SHALL set `storagemanager.messaging.inbox.enabled` to `true` so that the Idempotent_Inbox bean is active.
3. THE Communications_Service SHALL set `storagemanager.messaging.outbox.enabled` to `true` so that the Outbox_Writer and Outbox_Publisher beans are active.
4. THE Communications_Service SHALL set `storagemanager.messaging.enrichment.enabled` to `true` so that the Outbox_Writer persists `scope_0`, `scope_1`, `correlation_id`, and `headers`.
5. THE Communications_Service SHALL declare `storagemanager.messaging.scope-fields` as the ordered list `[organization_id, facility_id]` so that `organization_id` binds to `scope_0` and `facility_id` binds to `scope_1`.
6. THE Communications_Service SHALL supply an Outbox_Transport bean that satisfies the Outbox_Publisher dependency.
7. IF `storagemanager.messaging.outbox.enabled` is `true` and no Outbox_Transport bean is present, THEN THE Messaging_Starter SHALL fail application startup with an error identifying the missing Outbox_Transport bean.
8. WHEN a Messaging_Starter configuration property is not supplied by the Communications_Service, THE Messaging_Starter SHALL apply its documented default value for that property.

### Requirement 2: Host Flyway Migration for Inbox and Outbox Tables

**User Story:** As a Communications_Service developer, I want a Flyway migration that creates the enriched inbox and outbox tables in my own database, so that the starter entities validate under `ddl-auto=validate` and messaging rows persist correctly under both PostgreSQL and H2.

#### Acceptance Criteria

1. THE Host_Migration SHALL be numbered within the Communications_Service Flyway history continuing after `V1` (that is, `V2` or later).
2. THE Host_Migration SHALL create an `inbox` table using the Starter's enriched Reference_Schema shape.
3. THE Host_Migration SHALL create an `outbox` table using the Starter's enriched Reference_Schema shape.
4. THE Host_Migration SHALL name the scope columns using the physical names `scope_0` through `scope_15` so that `ddl-auto=validate` passes without per-column attribute overrides.
5. THE Host_Migration SHALL define the `inbox` `payload` column and the `outbox` `payload` column as `JSONB`, and SHALL define a `headers` `JSONB` column on each enriched table.
6. THE Host_Migration SHALL define a UNIQUE constraint named with the `uq_` prefix on the Inbox `message_id` column.
7. THE Host_Migration SHALL define a UNIQUE constraint named with the `uq_` prefix on the Outbox `event_id` column.
8. THE Host_Migration SHALL define the Outbox mechanics columns `created_at`, `published_at`, `attempts`, and `next_attempt_at`, and the Inbox mechanics columns `received_at` and `processed_at`.
9. THE Host_Migration SHALL name indexes using the `idx_` prefix convention.
10. WHERE tests run on H2, THE Communications_Service test datasource SHALL register a `JSONB` domain aliased to H2's native JSON type (`CREATE DOMAIN IF NOT EXISTS JSONB AS JSON`) so that the `payload` and `headers` columns validate.

### Requirement 3: Generic Ingest Endpoint Acceptance

**User Story:** As a producer, I want to POST a canonical EventEnvelope to the Communications_Service, so that the message is validated and recorded idempotently for processing by the messaging base.

#### Acceptance Criteria

1. THE Communications_Service SHALL expose the Ingest_Endpoint at `POST /api/v1/communications` to receive a generic EventEnvelope.
2. WHEN the Ingest_Endpoint receives a request body that is a well-formed EventEnvelope with all required fields present, THE Communications_Service SHALL accept the request for idempotent inbox recording.
3. THE Communications_Service SHALL accept the EventEnvelope generically and SHALL NOT branch on or require a specific `eventType` value.
4. IF the Ingest_Endpoint receives a request body missing a required EventEnvelope field, THEN THE Communications_Service SHALL reject the request with a `400 Bad Request` response and SHALL NOT write an Inbox row.
5. IF the Ingest_Endpoint receives a request body that is malformed and cannot be parsed as an EventEnvelope, THEN THE Communications_Service SHALL reject the request with a `400 Bad Request` response and SHALL NOT write an Inbox row.
6. WHEN the Ingest_Endpoint accepts and records an EventEnvelope keyed on `eventId = message_id`, THE Communications_Service SHALL return a `200 OK` response for both a newly accepted message and a duplicate message.

### Requirement 4: Service-to-Service Authorization Posture on Ingest

**User Story:** As a security engineer, I want the ingest endpoint to apply the same scope-based authorization posture used by the events service, so that only a verified first-party caller can assert an arbitrary organization and unauthenticated requests are rejected.

#### Acceptance Criteria

1. WHEN a request to the Ingest_Endpoint presents a JWT whose signature, expiration, and issuer are verified against the configured issuer and JWKS, THE Communications_Service SHALL accept the JWT as an authenticated identity.
2. IF a request to the Ingest_Endpoint arrives without a verified JWT, THEN THE Communications_Service SHALL reject the request with a `401 Unauthorized` response and SHALL NOT write an Inbox row.
3. WHEN an authenticated JWT carries the Ingest_Scope, THE Communications_Service SHALL classify the caller as a Trusted_Ingest_Caller and SHALL use the EventEnvelope `organizationId` as the authoritative Organization_Scope without comparison to any `organizationId` claim.
4. WHEN an authenticated JWT does not carry the Ingest_Scope, THE Communications_Service SHALL classify the caller as an End_User_Token and SHALL resolve the authorized organization from the token's `organizationId` claim.
5. IF an End_User_Token submits an EventEnvelope whose `organizationId` does not equal the token's `organizationId` claim, THEN THE Communications_Service SHALL reject the request with a `403 Forbidden` response and SHALL NOT write an Inbox row.
6. WHEN an EventEnvelope carrying `actorType` and `actorId` is recorded, THE Communications_Service SHALL preserve the EventEnvelope `actorType` and `actorId` on persistence and SHALL NOT overwrite them with the transport identity of a Trusted_Ingest_Caller.
7. WHEN the local profile is active, THE Communications_Service SHALL validate ingest-path JWTs against the storage-manager-api local issuer at `http://localhost:8080/api/v1/local`.

### Requirement 5: Idempotent Inbox Recording and No-Op Processing

**User Story:** As a Communications_Service developer, I want each inbound message processed exactly once through a no-op handler keyed on the preserved idempotency key, so that at-least-once redelivery exercises the dedupe path without any additional side effect.

#### Acceptance Criteria

1. WHEN the Communications_Service records an inbound EventEnvelope, THE Communications_Service SHALL use the EventEnvelope `eventId` as the Inbox `message_id` (the Idempotency_Key).
2. WHEN `processOnce` is invoked with a `message_id` not previously recorded, THE Idempotent_Inbox SHALL record an Inbox row with a null `processed_at` at receive time and SHALL then invoke the No_Op_Handler.
3. WHEN `processOnce` is invoked with a `message_id` whose Inbox row already has a non-null `processed_at`, THE Idempotent_Inbox SHALL skip the No_Op_Handler and SHALL return without producing an additional side effect.
4. WHEN the No_Op_Handler completes successfully during `processOnce`, THE Idempotent_Inbox SHALL stamp the `processed_at` timestamp on that `message_id` Inbox row.
5. IF the No_Op_Handler raises an error during `processOnce`, THEN THE Idempotent_Inbox SHALL NOT stamp `processed_at` for that `message_id` AND SHALL leave the Inbox row eligible for a subsequent `processOnce` retry.
6. WHEN two deliveries of the same `message_id` are processed concurrently, THE Idempotent_Inbox SHALL invoke the No_Op_Handler for at most one of them.
7. THE Inbox SHALL enforce uniqueness on `message_id` so that at most one Inbox row exists per `message_id`.

### Requirement 6: Generic Outbox Mechanics

**User Story:** As a Communications_Service developer, I want the generic outbox mechanics wired transactionally, so that an appended event is published exactly once to the events ingest API with retry and backoff, independent of any domain send behavior.

#### Acceptance Criteria

1. THE Outbox_Writer append operation SHALL use `MANDATORY` transaction propagation so that it joins the caller's active transaction.
2. WHEN the caller transaction that appends an Outbox row commits, THE Communications_Service SHALL persist exactly one Outbox row for that `event_id`.
3. IF the caller transaction that appends an Outbox row rolls back, THEN THE Communications_Service SHALL persist no Outbox row for that `event_id`.
4. THE Outbox SHALL enforce uniqueness on `event_id` so that at most one Outbox row exists per `event_id`.
5. WHEN an Outbox row is first appended, THE Outbox_Writer SHALL initialize the row as unpublished with `attempts` set to `0`.
6. WHEN the Outbox_Publisher runs on its configured interval, THE Outbox_Publisher SHALL select unpublished Outbox rows whose `next_attempt_at` is due, up to the configured batch size, and SHALL deliver each through the Outbox_Transport.
7. WHEN the Outbox_Transport returns a Success Transport_Result for an Outbox row, THE Outbox_Publisher SHALL mark that row published and SHALL NOT re-select that row on subsequent drains.
8. WHEN the Outbox_Transport returns a Retryable_Failure Transport_Result, THE Outbox_Publisher SHALL increment the Outbox row `attempts` and reschedule the row using the configured backoff.
9. WHEN an Outbox row reaches the configured maximum attempts, THE Outbox_Publisher SHALL stop re-selecting that row for delivery.

### Requirement 7: Outbox Transport Result Mapping

**User Story:** As an operator, I want the host Outbox_Transport to map events ingest API responses onto Transport_Result cases, so that transient failures recover and permanent failures are surfaced without losing events.

#### Acceptance Criteria

1. WHEN the Outbox_Transport sends an Outbox event, THE Outbox_Transport SHALL POST the canonical EventEnvelope JSON to the events ingest API at `POST /api/v1/events`.
2. WHEN the events ingest API returns a `2xx` response for either an accepted or a duplicate event, THE Outbox_Transport SHALL return a Success Transport_Result.
3. IF the events ingest API returns a `400` or `403` response, THEN THE Outbox_Transport SHALL return a Permanent_Failure Transport_Result.
4. IF the events ingest API returns a `5xx` response, a network error, or a timeout, THEN THE Outbox_Transport SHALL return a Retryable_Failure Transport_Result.
5. WHERE service-to-service authentication is enabled, THE Outbox_Transport SHALL attach an `events/ingest`-scoped service-identity bearer token to the request.
6. WHERE service-to-service authentication is disabled in the local Phase 0 profile, THE Outbox_Transport SHALL deliver without attaching a service-identity bearer token.

### Requirement 8: End-to-End Idempotency Invariant

**User Story:** As an operator, I want a redelivered message to be processed exactly once by the messaging base, so that redelivery is safe at both the inbox and the outbox.

#### Acceptance Criteria

1. WHEN the same EventEnvelope `eventId` is delivered to the Ingest_Endpoint more than once, THE Idempotent_Inbox SHALL invoke the No_Op_Handler for that `message_id` exactly once.
2. THE Communications_Service SHALL deduplicate inbound messages at the Inbox on `message_id` at receive time.
3. THE Communications_Service SHALL deduplicate outbound events at the Outbox on `event_id` at emission time.
4. THE Communications_Service SHALL preserve a single stable Idempotency_Key across the hop so that the key used as the Inbox `message_id` on receive is the same key used as the Outbox `event_id` on any corresponding emission.

### Requirement 9: Tenancy and Scope Isolation at the Envelope Level

**User Story:** As a platform operator, I want organization and facility scope carried consistently from the inbound envelope onto enriched rows, so that tenant isolation is preserved by the messaging mechanics.

#### Acceptance Criteria

1. WHEN the Communications_Service records an inbound EventEnvelope on the Inbox, THE Communications_Service SHALL set `scope_0` to the envelope `organizationId` resolved as the authoritative Organization_Scope on ingest.
2. WHERE the inbound EventEnvelope carries a `facilityId`, THE Communications_Service SHALL set `scope_1` to that Facility_Scope value on the enriched row.
3. IF the inbound EventEnvelope does not carry a `facilityId`, THEN THE Communications_Service SHALL set `scope_1` to null on the enriched row, because the `scope_1` column is nullable and no sentinel or default value is substituted.
4. WHEN the Communications_Service appends an Outbox row through the enriched Outbox_Writer, THE Communications_Service SHALL set `scope_0` to the Organization_Scope and `scope_1` to the Facility_Scope carried from the inbound envelope.
5. THE Communications_Service SHALL carry Organization_Scope and Facility_Scope through the messaging mechanics without coupling to any send record.

### Requirement 10: Secret-Free Enriched Payloads

**User Story:** As a data steward, I want enriched and emitted payloads to exclude secrets and unnecessary PII, so that sensitive data never travels on the event bus through the messaging base.

#### Acceptance Criteria

1. THE enriched and emitted EventEnvelope payloads SHALL contain only identifiers and approved operational fields.
2. THE enriched and emitted EventEnvelope payloads SHALL exclude payment secrets, authentication secrets, and personally identifiable information (PII).
3. THE messaging base SHALL NOT define or enumerate a concrete domain payload field set, because this spec defines only the generic messaging mechanics and carries no domain payload; the concrete per-event allowlist of approved operational fields SHALL be defined by each producing or consuming domain spec (for example, the follow-on communications send spec).

## External Assumptions (Out of Scope for storage-manager-communications)

The following are relied upon as cross-repository contracts but are NOT owned or changed by this spec.

- **A1 — Events-side LeadCreated workflow:** The workflow in `storage-manager-events` that reacts to `LeadCreated` and fans out downstream command events is owned by a separate future spec. This spec assumes only that generic commands may arrive at the Ingest_Endpoint as canonical EventEnvelopes.
- **A3 — Canonical EventEnvelope wire shape:** The 13-field EventEnvelope contract and the events ingest endpoint at `POST /api/v1/events` are owned by the events service and the messaging starter. This spec consumes and reproduces that shape.
- **A4 — Token production and S2S auth infrastructure:** Issuing the `events/ingest`-scoped service token (Cognito client-credentials in staging/production; the api local issuer in development) is owned by platform/producer configuration. This spec owns only the validation side on ingest and the attachment of a token on outbound delivery.
- **A5 — Domain send behavior deferred to the follow-on spec:** All actual send behavior is out of scope here and is deferred to the follow-on communications spec. This includes the `SendCommunication` eventType contract and payload interpretation, recipient resolution, the real send provider, send records, send-result-coupled outcome event semantics (`CommunicationSent` / `CommunicationFailed`), template rendering, and channel (`EMAIL` / `SMS`) semantics.

## Open Decisions / Recommended Defaults (user may override)

- **D1 — Ingest endpoint path:** Recommended default `POST /api/v1/communications` (over `POST /api/v1/send`), to align with the existing `/api/v1/*` convention. Reflected in Requirement 3.1.
