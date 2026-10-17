# Requirements Document

Live Lead Read Port (communications → storage-manager-api)

## Introduction

The `storage-manager-communications` service resolves a lead's organization,
facility, contact values, preferred channel, and personalization properties
through the `LeadReadPort` seam (`com.storagemanager.communications.action.lead`)
when the `ActionProcessor` handles a `SendCommunication` action. Today the only
implementation is the in-memory `StubLeadReadPort` (active under the `local`,
`test`, and `default` profiles); nothing reads real lead data from
`storage-manager-api`. Against real leads the processor therefore rejects every
action as "lead not found."

This feature adds a **live** `LeadReadPort` implementation —
`HttpLeadReadPort` — that reads a single lead from `storage-manager-api`'s
`GET /api/v1/leads/{id}` endpoint over HTTP and projects the response onto the
existing `LeadView`. It is registered under the non-local runtime profile so it
supersedes the stub in deployed/integration environments while the stub remains
the default for local development and tests. The HTTP client, timeouts, and
service-to-service (s2s) auth seam mirror the service's existing comms→events
outbound transport (`EventsIngestTransport` / `EventsIngestProperties` /
`EventsAuthTokenProvider`).

### External assumptions (owned by storage-manager-api, out of scope here)

These are tracked as a separate, subsequent api-side change. This spec is written
**assuming they exist** at runtime; no storage-manager-api code is modified by
this spec.

- **A1 — s2s read path.** `storage-manager-api` accepts a trusted service-identity
  (scope-based) token on its lead read endpoint, analogous to the `events/ingest`
  scope the events service accepts. The adapter attaches such a token; producing
  the real token is platform-owned (a disabled no-op seam is the default here).
- **A2 — `organizationId` on the lead response.** The lead read response exposes
  the lead's `organizationId` (required by `LeadView.organizationId`).
- **A3 — `properties` map on the lead response.** The lead read response exposes a
  `properties` map of personalization attribute values (consumed by
  `LeadView.properties`).

## Glossary

- **LeadView** — the existing read-seam projection
  (`organizationId`, `facilityId`, `email`, `phone`, `preferredMethod: MessageType`,
  `properties: Map<String,Object>`).
- **Lead-not-found** — the api reports no lead for the id (HTTP 404); the port
  returns `Optional.empty()` and the `ActionProcessor` treats it as a terminal
  validation rejection.
- **Transient read failure** — a network/timeout/5xx/auth failure; the port throws
  `LeadReadException` and the `ActionProcessor` surfaces a retryable condition so
  the originating message is redelivered.

## Requirements

### Requirement 1: Read a lead from storage-manager-api over HTTP

**User Story:** As the communications `ActionProcessor`, I want the live port to
fetch a lead's details from storage-manager-api, so that a `SendCommunication`
action can be processed against real lead data.

#### Acceptance Criteria

1. WHEN `findLead(leadId)` is called THEN the system SHALL issue an HTTP
   `GET /api/v1/leads/{leadId}` to the configured storage-manager-api base URL,
   with `leadId` as the path variable.
2. THE request SHALL carry an `Authorization: Bearer <token>` header when s2s auth
   is enabled (see Requirement 4), and no `Authorization` header when disabled.
3. WHEN the api returns 200 THEN the system SHALL parse the lead response body and
   return `Optional.of(view)` with a fully mapped `LeadView` (see Requirement 2).
4. THE adapter SHALL depend only on the response for the given `leadId`; it SHALL
   NOT read any local lead store.

### Requirement 2: Map the lead response onto LeadView

**User Story:** As the `ActionProcessor`, I want the api response mapped exactly
onto `LeadView`, so that template scoping, recipient/channel resolution, and
personalization behave as they do with the stub.

#### Acceptance Criteria

1. THE adapter SHALL map `LeadView.organizationId` from the response
   `organizationId` (External Assumption A2); a missing/null `organizationId` on a
   200 response SHALL be treated as a transient read failure (Requirement 3.4)
   rather than a null `LeadView` field, because `LeadView.organizationId` is
   required.
2. THE adapter SHALL map `LeadView.facilityId` from the response `facilityId`
   (may be null-tolerant on the `LeadView` side).
3. THE adapter SHALL map `LeadView.email` from the response `email`.
4. THE adapter SHALL map `LeadView.phone` from the response `mobilePhone`.
5. THE adapter SHALL map `LeadView.preferredMethod` from the response
   `preferredContactMethod`, translating the api `ContactMethod` enum
   (`EMAIL`/`SMS`) to the communications `MessageType` enum by name.
6. THE adapter SHALL map `LeadView.properties` from the response `properties` map
   (External Assumption A3); WHEN the response omits `properties` THEN the adapter
   SHALL use an empty map (never null).
7. THE adapter SHALL pass the `properties` values through opaquely (keys/values
   unchanged), since personalization looks them up by `jsonProperty` key.

### Requirement 3: Outcome mapping (not-found vs transient)

**User Story:** As the `ActionProcessor`, I want HTTP outcomes mapped onto the
`LeadReadPort` contract, so that a missing lead is terminal and a transient
failure is retryable.

#### Acceptance Criteria

1. WHEN the api returns 404 THEN the system SHALL return `Optional.empty()`
   (terminal lead-not-found); it SHALL NOT throw.
2. WHEN the api returns 200 with a parseable body THEN the system SHALL return a
   populated `Optional<LeadView>`.
3. WHEN the api returns a 5xx status THEN the system SHALL throw
   `LeadReadException` (retryable).
4. WHEN the call fails with a transport error (connection refused, timeout, DNS),
   or a 200 body is unparseable or missing a required field, THEN the system SHALL
   throw `LeadReadException` (retryable).
5. WHEN the api returns 401 or 403 THEN the system SHALL throw `LeadReadException`
   (retryable), with a detail that records the status so an auth misconfiguration
   surfaces clearly rather than being silently treated as not-found.
6. THE adapter SHALL NOT translate outcomes into `ActionValidationException` or
   `ActionRetryableException` itself — it only returns `Optional`/throws
   `LeadReadException`; the `ActionProcessor` maps those onto its own outcomes
   (the port contract is unchanged).

### Requirement 4: Configurable api connection and s2s auth seam

**User Story:** As an operator, I want the api base URL, timeouts, and auth toggle
externally configured, so that the same build runs locally and in deployed
environments.

#### Acceptance Criteria

1. THE system SHALL read the storage-manager-api base URL from configuration (no
   hardcoded host); a missing base URL SHALL fail fast at startup with a clear
   message.
2. THE system SHALL apply configurable connect and read timeouts to the outbound
   HTTP client so a hung api never blocks the processing thread indefinitely.
3. WHEN s2s auth is enabled (`auth.enabled=true`) THEN the system SHALL attach a
   bearer token from an injectable token-provider seam; WHEN disabled (the
   local/Phase-0 default) THEN it SHALL attach no `Authorization` header and SHALL
   NOT consult the provider.
4. THE token provider SHALL have a no-op default bean (returning no token) so the
   seam is injectable without a real token source in local development; a real
   client-credentials implementation can replace it later (token production is
   platform-owned and out of scope — see External Assumption A1).
5. THE configuration key shape SHALL follow the existing
   `storagemanager.messaging.events.*` convention, under a new
   `storagemanager.api.*` group.

### Requirement 5: Profile activation and coexistence with the stub

**User Story:** As a developer, I want the live port active only in
deployed/integration runs, so that local development and tests keep using the
deterministic stub.

#### Acceptance Criteria

1. THE live `HttpLeadReadPort` SHALL be registered so it is the active
   `LeadReadPort` in the non-local runtime profile, and SHALL NOT be active under
   the `local`, `test`, or `default` profiles where `StubLeadReadPort` remains the
   implementation.
2. THERE SHALL be exactly one active `LeadReadPort` bean in any given profile (no
   ambiguous-bean startup failure).
3. THE `LeadReadPort` interface, `LeadView`, and `LeadReadException` contracts
   SHALL be unchanged by this feature.
