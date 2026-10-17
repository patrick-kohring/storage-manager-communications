# Design Document: Messaging (communications messaging base)

## Overview

This feature makes `storage-manager-communications` the **second host** to adopt the shared
`com.storagemanager:storage-manager-messaging` starter (`0.1.0`, with envelope enrichment),
reusing the reference pattern established by the `storage-manager-api` messaging adoption. Unlike
the api — which adopted the **outbox half only** as a `LeadCreated` producer — communications
enables **both** starter halves and wires them **generically**:

- **Inbox half** (`storagemanager.messaging.inbox.enabled=true`): a generic ingest endpoint
  accepts the events-canonical 13-field `EventEnvelope`, applies the service-to-service
  authorization posture, maps the envelope onto an `InboundMessage`, and records it idempotently
  through the starter's `IdempotentInbox.processOnce(InboundMessage, Consumer)`. The handler is a
  **no-op / pass-through** `Consumer<InboundMessage>` that performs no domain work, so the
  insert-on-receive → run-handler → stamp-`processed_at` dedupe path is exercised without
  interpreting the message.
- **Outbox half** (`storagemanager.messaging.outbox.enabled=true`): the starter `OutboxWriter`,
  `OutboxPublisher`, and a host-provided `OutboxTransport` (`EventsIngestTransport`) are wired so
  that an appended `outbox` row is published generically to the events ingest API
  (`POST /api/v1/events`) with retry and backoff. In this base scope there is **no domain trigger**
  that appends to the outbox; the outbox mechanics and transport are **wired and tested**, not
  driven by domain events.

The central deliverable is the **messaging base working end-to-end**: receive → dedupe → no-op on
the inbound side, and append → publish → transport on the outbound side, while the service still
boots and `ddl-auto=validate` passes. The events-boundary envelope mapping (scope → `scope_0`/
`scope_1`, the seven metadata fields → passthrough headers, `id` ↔ `eventId`) is reused **verbatim**
from the api reference pattern, applied here to a generic envelope rather than a concrete
`LeadCreated`.

**Explicitly OUT OF SCOPE (deferred to a follow-on communications send spec):** all actual
send behavior — `SendCommunication` payload interpretation, recipient resolution, the real send
provider and provider seam, send records, send-result-coupled outcome events (`CommunicationSent` /
`CommunicationFailed`), template rendering, and channel (`EMAIL` / `SMS`) semantics. In this base
scope the ingest endpoint accepts a **generic** `EventEnvelope` and does **not** branch on or
require any specific `eventType`, and the outbox is exercised **generically**, not from a send
result.

Grounding references:

- Requirements: `.kiro/specs/messaging/requirements.md` (this spec — the source of truth, 10
  requirements plus Decision D1).
- Reference pattern: `storage-manager-api/.kiro/specs/messaging/design.md` (the first host
  adoption — the events-boundary mapping, the `V18` physical-`scope_N` migration decision, the
  `EventsIngestTransport` HTTP-outcome → `TransportResult` mapping, the `InboxEntry`
  `@EntityScan` validate risk).
- Starter public API `com.storagemanager.messaging.api` (`0.1.0`): `OutboxWriter`,
  `IdempotentInbox`, `OutboxTransport`, `TransportResult`, `EnrichedMessage`, `InboundMessage`,
  `OutboxMessage`, `DerivedId`, `OutboxPublisher`, `MessagingAutoConfiguration` (starter beans
  gated by `@ConditionalOnProperty` + `@ConditionalOnMissingBean`; entities `@EntityScan`-ed
  unconditionally).
- Starter enriched reference DDL:
  `storage-manager-messaging/src/main/resources/db/reference/messaging_inbox_outbox_enriched.sql`.
- events S2S ingest posture: `storage-manager-events` `IngestController` (scope-based caller-org
  resolution; `events/ingest` trusted; 200 for ACCEPTED and DUPLICATE; 400 invalid; 403 cross-org).
- Communications current state: `StorageManagerCommunicationsApplication` (bare
  `@SpringBootApplication`), `SecurityConfig`, `CustomJwtAuthenticationConverter` (rejects missing
  role claim), `GlobalExceptionHandler`, `CommunicationController` (owns
  `/api/v1/communication-templates`), `V1__create_communication_templates.sql`.

---

## Architecture

High-Level Design (HLD): component and sequence diagrams below. Communications plays **two roles**
here — a generic inbox consumer (inbound) and a generic outbox producer (outbound) — so the
architecture is presented as two cooperating-but-independent pipelines sharing the starter beans
and the host database.

### Component diagram

```mermaid
flowchart LR
    subgraph PROD["Producer (e.g. events reaction, out of scope here)"]
        SRC["POST canonical EventEnvelope JSON"]
    end

    subgraph COMM["storage-manager-communications (messaging base)"]
        direction TB
        subgraph INBND["Inbound (inbox half)"]
            IEC["InboundEventController<br/>POST /api/v1/communications"]
            CJAC["CustomJwtAuthenticationConverter<br/>(+ scope-without-role support)"]
            IRS["InboundRecordingService<br/>(envelope -> InboundMessage)"]
            NOP["No_Op_Handler<br/>Consumer&lt;InboundMessage&gt;"]
            II["IdempotentInbox.processOnce(InboundMessage, handler)<br/>(starter, REQUIRED)"]
            INB[("inbox table<br/>message_id UNIQUE + scope_0/1 + correlation_id + headers")]
        end
        subgraph OUTBND["Outbound (outbox half)"]
            OW["OutboxWriter.append(EnrichedMessage)<br/>(starter, MANDATORY)"]
            OB[("outbox table<br/>event_id UNIQUE + scope_0/1 + correlation_id + headers")]
            OP["OutboxPublisher<br/>(starter, scheduled drain)"]
            EIT["EventsIngestTransport implements OutboxTransport<br/>(host, HTTP POST + TransportResult mapping)"]
        end
    end

    subgraph EVT["storage-manager-events"]
        ING["IngestController<br/>POST /api/v1/events"]
        EINBOX[("events inbox<br/>dedupe on eventId")]
    end

    SRC --> IEC
    IEC --> CJAC
    IEC --> IRS
    IRS --> II
    II --> NOP
    II --> INB
    OW --> OB
    OB --> OP
    OP -->|send(EnrichedMessage)| EIT
    EIT -->|HTTP POST EventEnvelope JSON| ING
    ING --> EINBOX
    EIT -. "2xx -> Success<br/>400/403 -> PermanentFailure<br/>5xx/network/timeout -> RetryableFailure" .-> OP
```

Communications owns everything inside the `COMM` box except the starter-owned beans
(`IdempotentInbox`, `OutboxWriter`, `OutboxPublisher`) and the `inbox`/`outbox` table shapes
(which communications applies as its own `V2` Flyway migration). The starter never knows about the
S2S posture, the HTTP ingest contract, or the events wire format — all of that lives in
`InboundEventController`, `InboundRecordingService`, and `EventsIngestTransport`, which are host
code.

### Layered responsibilities

| Layer | Component | Owner | Responsibility |
|---|---|---|---|
| Controller | `InboundEventController` | communications (new) | `POST /api/v1/communications`; validate envelope; resolve caller org (S2S) |
| Security | `CustomJwtAuthenticationConverter` | communications (extended) | Grant `SCOPE_events/ingest` for scope-only tokens; keep `ROLE_*` role mapping |
| Service | `InboundRecordingService` | communications (new) | Map `EventEnvelope` → `InboundMessage`; call `processOnce` with the no-op handler |
| Handler | `No_Op_Handler` | communications (new) | Pass-through `Consumer<InboundMessage>`; replaced by real send in the follow-on spec |
| Consume | `IdempotentInbox` | starter | `processOnce(InboundMessage, handler)`; insert-on-receive, dedupe, stamp `processed_at` |
| Produce | `OutboxWriter` | starter | `append(EnrichedMessage)` with `MANDATORY` propagation |
| Store | `inbox` / `outbox` tables | communications (`V2` migration) | Durable enriched inbox + outbox logs |
| Drain | `OutboxPublisher` | starter | Scheduled poll, retry/backoff, parking |
| Transport | `EventsIngestTransport` | communications (new) | `EnrichedMessage` → canonical `EventEnvelope` JSON; HTTP POST; `TransportResult` mapping |
| Config | `MessagingTransportConfig` | communications (new) | `OutboxTransport` bean + `EventsIngestProperties` (satisfies starter fail-fast) |
| Destination | `IngestController` | events (unchanged) | `POST /api/v1/events`, dedupe on `eventId` |

### Sequence diagram — inbound (receive → dedupe → no-op)

```mermaid
sequenceDiagram
    participant Caller as Producer (JWT)
    participant SEC as Security filter chain
    participant IEC as InboundEventController
    participant IRS as InboundRecordingService (@Transactional)
    participant II as IdempotentInbox (starter, REQUIRED)
    participant NOP as No_Op_Handler
    participant DB as communications Postgres (inbox)

    Caller->>SEC: POST /api/v1/communications (EventEnvelope, Bearer JWT)
    alt no verified JWT
        SEC-->>Caller: 401 Unauthorized (no inbox row)
    else verified JWT
        SEC->>IEC: authenticated request (authorities incl. SCOPE_events/ingest or ROLE_*)
        IEC->>IEC: validate envelope (required fields); resolve caller org (S2S rule)
        alt End_User_Token and envelope.organizationId != token org
            IEC-->>Caller: 403 Forbidden (no inbox row)
        else authorized
            IEC->>IRS: record(envelope, resolvedOrganizationId)
            IRS->>IRS: map envelope -> InboundMessage (scope_0/1, correlationId, headers)
            IRS->>II: processOnce(message, No_Op_Handler)
            alt message_id not seen
                II->>DB: insert inbox row (received_at set, processed_at null)
                II->>NOP: accept(message)   [no-op, succeeds]
                II->>DB: stamp processed_at
            else message_id already processed
                II->>DB: dedupe-skip (no handler run)
            else concurrent insert race
                II->>DB: unique(message_id) loser returns WITHOUT running handler
            end
            IRS-->>IEC: done
            IEC-->>Caller: 200 OK (new OR duplicate)
        end
    end
```

### Sequence diagram — outbound (append → publish → transport)

```mermaid
sequenceDiagram
    participant Caller as Outbox-appending caller (@Transactional)
    participant OW as OutboxWriter (starter, MANDATORY)
    participant DB as communications Postgres (outbox)
    participant OP as OutboxPublisher (starter, scheduled)
    participant TR as EventsIngestTransport (host)
    participant EV as events POST /api/v1/events

    Note over Caller,DB: single @Transactional unit
    Caller->>OW: append(EnrichedMessage)  [MANDATORY -> joins THIS tx]
    OW->>DB: insert outbox row (dedupe on event_id, attempts=0)  [not committed]
    Caller-->>Caller: tx commits -> exactly one outbox row (or rollback -> none)

    Note over OP: later, on the scheduled poll
    OP->>DB: select due unpublished rows (next_attempt_at due, batch)
    OP->>TR: send(EnrichedMessage) rebuilt from the row
    TR->>TR: scope_0/1 -> organizationId/facilityId; headers -> 7 metadata fields; id -> eventId
    TR->>EV: POST /api/v1/events (canonical EventEnvelope JSON)
    alt 2xx (ACCEPTED or DUPLICATE)
        EV-->>TR: 200
        TR-->>OP: TransportResult.success()
        OP->>DB: stamp published_at (never re-selected)
    else 400 / 403
        EV-->>TR: 400 or 403
        TR-->>OP: TransportResult.permanent(...)
        OP->>DB: park row (dead-letter)
    else 5xx / network / timeout
        EV-->>TR: 5xx / IOException / timeout
        TR-->>OP: TransportResult.retryable(...)
        OP->>DB: attempts++, next_attempt_at = backoff (park at max-attempts)
    end
```

---

## Components and Interfaces

Low-Level Design (LLD) for the communications-owned components. The envelope mapping is reused
from the api reference pattern; the two new concerns unique to communications are the **inbound
ingest + S2S posture** and the **scope-without-role security extension**.

### Decision D1 reconciliation: ingest endpoint path

**Requirement 3.1 / Decision D1** names the default ingest path `POST /api/v1/communications`.
Verified against the existing `CommunicationController`: that controller is a bare `@RestController`
with **no class-level `@RequestMapping`**, and its method mappings are all under
`/api/v1/communication-templates` (`POST`/`GET`/`PATCH`). Therefore `/api/v1/communications` is
**not** taken — there is **no path collision** — and the design honors D1 as written. A new
`InboundEventController` exposes `POST /api/v1/communications`.

> Note on the near-miss: `/api/v1/communications` and `/api/v1/communication-templates` are
> distinct, non-overlapping paths (Spring routes on the full path, and neither is a prefix of a
> path variable of the other). No ambiguity results. Had the template controller owned
> `/api/v1/communications`, the design would have fallen back to `POST /api/v1/communications/ingest`
> and recorded the deviation; that fallback is **not** needed.

### `InboundEventController` (LLD) — ingest + S2S posture

The controller accepts a communications-side copy of the canonical envelope. Because the events
`EventEnvelope` record lives in another repository, communications defines its **own** request
record mirroring the 13 fields (Bean Validation annotations drive the `400` on missing required
fields via the existing `GlobalExceptionHandler`).

```java
package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Communications-side copy of the events-canonical 13-field EventEnvelope wire contract
 * (External Assumption A3; owned by events + the starter, reproduced here for ingest).
 * Required fields carry @NotNull so a missing field surfaces as 400 via GlobalExceptionHandler
 * (Req 3.4). actorId / correlationId / causationId are nullable by contract.
 */
public record EventEnvelope(
        @NotNull UUID eventId,
        @NotNull String eventType,
        int schemaVersion,
        @NotNull Instant occurredAt,
        @NotNull UUID organizationId,
        UUID facilityId,
        @NotNull String entityType,
        @NotNull UUID entityId,
        @NotNull ActorType actorType,   // SYSTEM | USER
        UUID actorId,
        UUID correlationId,
        UUID causationId,
        @NotNull Map<String, Object> payload) {

    public enum ActorType { SYSTEM, USER }
}
```

```java
package com.storagemanager.communications.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.service.InboundRecordingService;

import jakarta.validation.Valid;

/**
 * Generic ingest endpoint for the messaging base (Req 3). Accepts a canonical EventEnvelope,
 * applies the service-to-service authorization posture mirrored from events (Req 4), and records
 * the message idempotently via the starter IdempotentInbox (Req 5). Returns 200 for both a newly
 * accepted and a duplicate message (Req 3.6). Does NOT branch on eventType (Req 3.3).
 */
@RestController
public class InboundEventController {

    private final InboundRecordingService recordingService;
    private final IngestAuthorizer ingestAuthorizer;

    public InboundEventController(InboundRecordingService recordingService,
                                  IngestAuthorizer ingestAuthorizer) {
        this.recordingService = recordingService;
        this.ingestAuthorizer = ingestAuthorizer;
    }

    @PostMapping("/api/v1/communications")
    @ResponseStatus(HttpStatus.OK) // 200 for both new and duplicate (Req 3.6)
    public void ingest(@Valid @RequestBody EventEnvelope envelope, Authentication authentication) {
        // Resolve the authoritative organization scope per the S2S rule (Req 4.3-4.5).
        // Throws AccessDeniedException (-> 403) for a cross-org End_User_Token.
        UUID organizationScope = ingestAuthorizer.resolveOrganizationScope(envelope, authentication);
        recordingService.record(envelope, organizationScope);
    }
}
```

### `IngestAuthorizer` (LLD) — the S2S posture mirrored from events

This encapsulates Requirement 4. It mirrors the events `IngestController.resolveCallerOrganizationId`
logic exactly: a `Trusted_Ingest_Caller` (JWT carrying `SCOPE_events/ingest`) may assert any
`organizationId`; an `End_User_Token` is confined to its own org.

```java
package com.storagemanager.communications.controller;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.UserIdentity;

@Component
public class IngestAuthorizer {

    private final String ingestScope; // "events/ingest" (Ingest_Scope)

    public IngestAuthorizer(@Value("${app.security.jwt.ingest-scope:events/ingest}") String ingestScope) {
        this.ingestScope = ingestScope;
    }

    /**
     * Trusted_Ingest_Caller (authority SCOPE_<ingestScope>): use envelope.organizationId
     * unchanged, with NO comparison to any organizationId claim (Req 4.3).
     * Otherwise End_User_Token: resolve the caller org from the token (Req 4.4) and require
     * envelope.organizationId == caller org (Req 4.5), else 403.
     * An authenticated token with no resolvable org is rejected (Req 4.4/4.5 -> 403).
     */
    public UUID resolveOrganizationScope(EventEnvelope envelope, Authentication authentication) {
        boolean trusted = authentication.getAuthorities().stream()
                .anyMatch(a -> ("SCOPE_" + ingestScope).equals(a.getAuthority()));
        if (trusted) {
            return envelope.organizationId(); // any org, authoritative (Req 4.3, 9.1)
        }

        UUID callerOrg = callerOrganization(authentication);
        if (callerOrg == null) {
            throw new AccessDeniedException("No organization scope for end-user token");
        }
        if (!callerOrg.equals(envelope.organizationId())) {
            throw new AccessDeniedException("Envelope organizationId does not match caller org");
        }
        return callerOrg; // confined to caller org (Req 4.4, 4.5)
    }

    private UUID callerOrganization(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Object details = jwtToken.getDetails();
            if (details instanceof UserIdentity identity && identity.getOrganizationId() != null) {
                return identity.getOrganizationId();
            }
            String claim = jwtToken.getToken().getClaimAsString("organizationId");
            return claim == null ? null : UUID.fromString(claim);
        }
        return null;
    }
}
```

Unauthenticated requests never reach the controller: `SecurityConfig`'s `.anyRequest().authenticated()`
plus the resource-server filter chain returns `401` via `CustomAuthenticationEntryPoint` (Req 4.2),
and `AccessDeniedException` surfaces as `403` via `CustomAccessDeniedHandler` /
`GlobalExceptionHandler` (Req 4.5).

### Security extension: scope-without-role (the critical converter gap)

**Finding (confirmed in source).** `CustomJwtAuthenticationConverter.convert` throws
`InvalidBearerTokenException("Missing role claim: ...")` whenever the configured role claim
(`custom:role`) is absent. A **pure service token** that carries only the `events/ingest` scope and
**no** `custom:role` claim — exactly the `Trusted_Ingest_Caller` identity events uses — would be
**rejected** today. This directly blocks Requirement 4.1/4.3 (a scope-only token must authenticate).

**Chosen resolution.** Extend the converter so a validated JWT that carries the ingest scope
authenticates **without** a role claim, while preserving the existing `ROLE_<role>` mapping when a
role is present. Concretely:

1. Read the OAuth2 `scope`/`scp` claim and grant Spring's standard `SCOPE_<value>` authorities for
   each scope value (so `SecurityConfig` can authorize the ingest path for `SCOPE_events/ingest`).
2. Read `custom:role`:
   - If present and recognized → add `ROLE_<role>` and attach a `UserIdentity` (unchanged behavior
     for interactive users; both authority sets apply when both a role and a scope are present).
   - If present and unrecognized → still reject as an invalid token (unchanged).
   - If **absent**, only reject when **no** ingest scope is present. If the ingest scope **is**
     present, authenticate as a scope-only service identity with authorities `{SCOPE_events/ingest}`
     and a `UserIdentity` whose `role` is null and whose `organizationId` is read from the claim if
     present (a trusted caller does not require one).

```java
// CustomJwtAuthenticationConverter.convert (extended) — illustrative
String roleClaimValue = jwt.getClaimAsString(roleClaim);
List<String> scopes = readScopes(jwt); // from "scope" (space-delimited) or "scp" (array)
boolean hasIngestScope = scopes.contains(ingestScope); // ingestScope = "events/ingest"

Collection<GrantedAuthority> authorities = new ArrayList<>();
for (String s : scopes) {
    authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)); // SCOPE_events/ingest etc.
}

UserRole role = null;
if (roleClaimValue != null) {
    try {
        role = UserRole.valueOf(roleClaimValue);
    } catch (IllegalArgumentException ex) {
        throw new InvalidBearerTokenException("Unrecognized role claim: " + roleClaimValue, ex);
    }
    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
} else if (!hasIngestScope) {
    // No role AND not a trusted ingest caller -> reject as before (interactive users need a role).
    throw new InvalidBearerTokenException("Missing role claim: " + roleClaim);
}

UUID organizationId = parseUuidClaim(jwt, "organizationId");
UserIdentity identity = new UserIdentity(jwt.getSubject(), role, organizationId); // role may be null
JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
token.setDetails(identity);
return token;
```

`UserIdentity` already stores `role` as a nullable reference; a null role is only produced for a
scope-only service identity and is never consulted by `IngestAuthorizer` on the trusted path (it
keys off the `SCOPE_events/ingest` authority, not the role).

**`SecurityConfig` update.** Authorize the ingest path for authenticated callers (the existing
`.anyRequest().authenticated()` already covers it; `401` for no token is preserved). Optionally make
the intent explicit:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/healthcheck", "/api/v1/docs", "/api/v1/docs/**",
                     "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/swagger-resources/**")
        .permitAll()
    // Ingest requires authentication; a Trusted_Ingest_Caller presents SCOPE_events/ingest,
    // an End_User_Token presents ROLE_* — both are authenticated identities (Req 4.1, 4.2).
    .requestMatchers(HttpMethod.POST, "/api/v1/communications").authenticated()
    .anyRequest().authenticated())
```

The entry point (`401`) and access-denied handler (`403`) are unchanged.

### `InboundRecordingService` (LLD) — envelope → `InboundMessage` → `processOnce`

```java
package com.storagemanager.communications.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * Maps a validated EventEnvelope onto the starter InboundMessage and records it idempotently
 * through processOnce with a no-op handler (Req 5). The idempotency key is the envelope eventId,
 * used as the inbox message_id (Req 5.1, 8.4).
 */
@Service
public class InboundRecordingService {

    private final IdempotentInbox inbox;

    public InboundRecordingService(IdempotentInbox inbox) {
        this.inbox = inbox;
    }

    @Transactional
    public void record(EventEnvelope envelope, UUID organizationScope) {
        InboundMessage message = toInboundMessage(envelope, organizationScope);
        // No_Op_Handler: pass-through, no domain work, completes successfully so processed_at is
        // stamped (Req 5.2, 5.4). The follow-on send spec replaces this consumer with real logic.
        inbox.processOnce(message, m -> { /* no-op */ });
    }

    private InboundMessage toInboundMessage(EventEnvelope e, UUID organizationScope) {
        // scope_0 = organization (authoritative on ingest), scope_1 = facility (nullable).
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", organizationScope);
        scope.put("facility_id", e.facilityId()); // null -> scope_1 null, no sentinel (Req 9.3)

        // The seven non-promoted envelope fields ride as passthrough headers; actorType/actorId
        // are preserved here, NOT overwritten by the transport identity (Req 4.6).
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", Integer.toString(e.schemaVersion()));
        headers.put("occurred_at", e.occurredAt().toString());
        headers.put("entity_type", e.entityType());
        headers.put("entity_id", e.entityId().toString());
        headers.put("actor_type", e.actorType().name());
        if (e.actorId() != null) {
            headers.put("actor_id", e.actorId().toString());
        }
        if (e.causationId() != null) {
            headers.put("causation_id", e.causationId().toString());
        }

        return new InboundMessage(
                e.eventId(),        // messageId == eventId (the Idempotency_Key, Req 5.1, 8.4)
                e.eventType(),
                (Map<String, Object>) e.payload(),
                scope,
                e.correlationId(),
                headers);
    }
}
```

The starter's enriched `processOnce(InboundMessage, Consumer<InboundMessage>)` overload rejects a
null `messageId()` with `MissingMessageIdException` before any row/handler; the envelope's `eventId`
is `@NotNull`, so a valid request always carries a non-null key.

### The events-boundary envelope mapping (reused reference pattern)

Reused verbatim from the api reference pattern (api design: *The events-boundary envelope mapping*).
In the base scope communications produces a **generic** `EnrichedMessage` rather than a concrete
`LeadCreated`, so there is no domain factory; the mapping table below defines the wire-side
reconstruction (`EnrichedMessage` → canonical `EventEnvelope` JSON) in `EventsIngestTransport`, and
the symmetric inbound mapping (`EventEnvelope` → `InboundMessage`) done in `InboundRecordingService`
above.

| `EventEnvelope` field | `EnrichedMessage` / `InboundMessage` member | Wire-side reconstruction (`EventsIngestTransport`) |
|---|---|---|
| `eventId` | `id` / `messageId` | `message.id()` |
| `eventType` | `eventType` | `message.eventType()` |
| `organizationId` | `scope["organization_id"]` → `scope_0` | `message.scope().get("organization_id")` |
| `facilityId` | `scope["facility_id"]` → `scope_1` | `message.scope().get("facility_id")` (null allowed) |
| `correlationId` | `correlationId` | `message.correlationId()` |
| `schemaVersion` | `headers["schema_version"]` | `Integer.parseInt(headers.get("schema_version"))` |
| `occurredAt` | `headers["occurred_at"]` | `Instant.parse(headers.get("occurred_at"))` |
| `entityType` | `headers["entity_type"]` | `headers.get("entity_type")` |
| `entityId` | `headers["entity_id"]` | `UUID.fromString(headers.get("entity_id"))` |
| `actorType` | `headers["actor_type"]` | `ActorType.valueOf(headers.get("actor_type"))` |
| `actorId` | `headers["actor_id"]` | `null` when absent |
| `causationId` | `headers["causation_id"]` | `null` when absent |
| `payload` | `payload` | `message.payload()` passed through |

Convention (reused): **scope + idempotency key + `eventType` + opaque payload are first-class
`EnrichedMessage` members**; the **seven remaining envelope metadata fields ride as passthrough
headers** and are lifted back onto the canonical JSON by the transport. The starter round-trips
headers byte-for-byte and never interprets them.

### Wire-side host transport: `EventsIngestTransport` (LLD)

Mirrors the api `EventsIngestTransport` exactly — same wire mapping, same HTTP-outcome →
`TransportResult` mapping. The `OutboxPublisher` calls `send(EnrichedMessage)`, so this
enrichment-aware override is the primary path; `send(OutboxMessage)` is kept source-complete.

```java
package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.OutboxMessage;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.TransportResult;

/**
 * Host OutboxTransport that reconstructs the events-canonical 13-field EventEnvelope JSON from an
 * EnrichedMessage and POSTs it to events /api/v1/events (Req 7.1). Maps 2xx -> Success,
 * 400/403 -> PermanentFailure, 5xx/network/timeout -> RetryableFailure (Req 7.2-7.4).
 */
public class EventsIngestTransport implements OutboxTransport {

    private final RestClient restClient; // configured with events base URL + optional s2s auth

    public EventsIngestTransport(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public TransportResult send(EnrichedMessage message) {
        Map<String, Object> envelope = toCanonicalEnvelope(message);
        try {
            restClient.post()
                    .uri("/api/v1/events")
                    .body(envelope)
                    .retrieve()
                    .toBodilessEntity();                 // events returns 200 for ACCEPTED and DUPLICATE
            return TransportResult.success();             // redelivery is safe (dedupe on eventId)
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400) {
                return TransportResult.permanent("events rejected envelope (400): " + e.getResponseBodyAsString());
            }
            if (status == 403) {
                return TransportResult.permanent("events cross-org rejection (403)");
            }
            if (status >= 500) {
                return TransportResult.retryable("events server error (" + status + ")");
            }
            return TransportResult.permanent("events rejected (" + status + ")"); // other 4xx -> permanent
        } catch (RestClientException e) {
            // Connection refused, timeout, DNS, etc. -> transient, back off and retry.
            return TransportResult.retryable("transport error: " + e.getMessage());
        }
    }

    /** Baseline path kept source-complete; not the primary path (publisher calls the enriched overload). */
    @Override
    public TransportResult send(OutboxMessage message) {
        return send(EnrichedMessage.of(message));
    }

    /** Rebuild the 13-field canonical EventEnvelope JSON from the enriched members. */
    private Map<String, Object> toCanonicalEnvelope(EnrichedMessage m) {
        Map<String, UUID> scope = m.scope();
        Map<String, String> h = m.headers();

        LinkedHashMap<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", m.id());
        e.put("eventType", m.eventType());
        e.put("schemaVersion", intHeader(h, "schema_version"));
        e.put("occurredAt", h.get("occurred_at"));
        e.put("organizationId", scope.get("organization_id"));   // scope_0 -> organizationId
        e.put("facilityId", scope.get("facility_id"));           // scope_1 -> facilityId (nullable)
        e.put("entityType", h.get("entity_type"));
        e.put("entityId", uuidHeader(h, "entity_id"));
        e.put("actorType", h.get("actor_type"));
        e.put("actorId", uuidHeader(h, "actor_id"));             // null when absent
        e.put("correlationId", m.correlationId());
        e.put("causationId", uuidHeader(h, "causation_id"));     // null when absent
        e.put("payload", m.payload());                           // opaque passthrough
        return e;
    }

    private static Integer intHeader(Map<String, String> h, String k) {
        String v = h.get(k);
        return v == null ? null : Integer.valueOf(v);
    }

    private static UUID uuidHeader(Map<String, String> h, String k) {
        String v = h.get(k);
        return v == null ? null : UUID.fromString(v);
    }
}
```

### TransportResult mapping (HTTP outcome → publisher behavior)

| events HTTP outcome | `TransportResult` | `OutboxPublisher` behavior | Why |
|---|---|---|---|
| 2xx (`ACCEPTED` or `DUPLICATE`, both 200) | `Success()` | stamp `published_at`, never re-select | delivery done; `DUPLICATE` means events already has it — redelivery is safe |
| 400 invalid/unsupported envelope | `PermanentFailure` | park the row (dead-letter) | a malformed event never succeeds on retry |
| 403 cross-org | `PermanentFailure` | park the row | same envelope keeps being rejected; needs operator attention |
| 5xx / network / timeout | `RetryableFailure` | `attempts++`, backoff reschedule | transient; events may recover |
| transport throws | (treated as retryable by publisher) | backoff reschedule | starter contract: a throwing transport never loses the row |

### How the outbox is exercised generically (no domain trigger yet)

In the base scope there is **no domain event** that appends to the outbox. The outbox mechanics +
transport are nonetheless **wired and tested**:

- `OutboxWriter` is an active starter `@Bean` (outbox enabled), and `MessagingTransportConfig`
  supplies the `OutboxTransport` so the context starts (fail-fast satisfied).
- Coverage appends a **generic** row via `EnrichedMessage.minimal(id, eventType, payload)` (empty
  scope, null correlation, empty headers) or a fully-enriched generic `EnrichedMessage` inside a
  test/`@DataJpaTest` transaction, then drives the publisher against a stubbed events endpoint.

Outbox emission is **deliberately not coupled** to the no-op inbound handler or to any send result:
the inbound and outbound pipelines are independent in this base. The follow-on send spec introduces
the real domain trigger that calls `OutboxWriter.append(...)`.

### Host transport wiring and config: `MessagingTransportConfig`

```java
package com.storagemanager.communications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.storagemanager.communications.messaging.EventsIngestTransport;
import com.storagemanager.messaging.api.OutboxTransport;

/**
 * Supplies the host OutboxTransport bean (satisfying the starter fail-fast "outbox enabled requires
 * an OutboxTransport bean" rule, Req 1.6/1.7) and the events ingest connection properties.
 */
@Configuration
public class MessagingTransportConfig {

    @Bean
    public OutboxTransport eventsIngestTransport(EventsIngestProperties props,
                                                 EventsAuthTokenProvider tokenProvider) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(props.clientRequestFactoryWithTimeouts()); // connect/read timeouts
        if (props.getAuth().isEnabled()) {
            // WHERE s2s auth enabled: attach an events/ingest-scoped service bearer token (Req 7.5).
            builder = builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBearerAuth(tokenProvider.currentToken());
                return execution.execute(request, body);
            });
        }
        // WHERE disabled (local Phase 0): deliver without a token (Req 7.6).
        return new EventsIngestTransport(builder.build());
    }

    @Bean
    @ConfigurationProperties(prefix = "storagemanager.messaging.events")
    public EventsIngestProperties eventsIngestProperties() {
        return new EventsIngestProperties();
    }
}
```

`EventsIngestProperties` holds `baseUrl`, `connectTimeout`, `readTimeout`, and nested `auth`
(`enabled` flag). `EventsAuthTokenProvider` is the token seam: a no-op in local Phase 0
(`auth.enabled=false`), a real client-credentials provider when enabled (External Assumption A4 —
token production is platform-owned; communications owns only attachment on outbound and validation
on inbound).

### Config properties communications sets

```yaml
storagemanager:
  messaging:
    inbox:
      enabled: true                 # consumer: IdempotentInbox bean active (Req 1.2)
    outbox:
      enabled: true                 # producer: OutboxWriter + OutboxPublisher active (Req 1.3)
      poll-interval-ms: 1000        # starter default
      batch-size: 100               # starter default
    enrichment:
      enabled: true                 # persist scope_0/scope_1/correlation_id/headers (Req 1.4)
    scope-fields:                   # declared order binds positionally (Req 1.5)
      - organization_id             # -> scope_0
      - facility_id                 # -> scope_1
    max-attempts: 10                # parked (dead-lettered) after 10 attempts
    backoff:
      base: 1s
      max: 5m
      multiplier: 2
    events:
      base-url: ${EVENTS_BASE_URL:http://localhost:8081}
      connect-timeout: 2s
      read-timeout: 5s
      auth:
        enabled: false              # local Phase 0: no s2s token attached (Req 7.6)

app:
  security:
    jwt:
      ingest-scope: events/ingest   # Ingest_Scope -> SCOPE_events/ingest (Req 4.3)
```

Notes:

- `outbox.enabled=true` + no `OutboxTransport` bean = fail-fast `NoSuchBeanDefinitionException`
  naming `OutboxTransport`; `MessagingTransportConfig` supplies the bean (Req 1.6, 1.7).
- Properties not supplied fall back to the starter's documented defaults (Req 1.8).
- Both halves enabled here, so both the `IdempotentInbox` bean and the `OutboxWriter`/
  `OutboxPublisher` beans are created; the migration must create **both** tables regardless (see
  Data Models).

---

## Data Models

The persistence shape communications owns for this feature: the enriched `inbox`/`outbox` tables
(applied as a host Flyway migration `V2`) and how they reconcile with the starter's JPA entities.
The enriched carrier types (`EnrichedMessage`, `InboundMessage`, the canonical `EventEnvelope`
fields) are detailed in the mapping table under Components and Interfaces.

### Host Flyway migration — `V2__messaging_inbox_outbox.sql`

**File:** `src/main/resources/db/migration/V2__messaging_inbox_outbox.sql` (`V1` is
`create_communication_templates`; `V2` is the next free version, Req 2.1).

The migration applies the starter's **enriched reference schema** (Section 1, greenfield shape)
from `storage-manager-messaging/.../db/reference/messaging_inbox_outbox_enriched.sql`, creating
**both** the `inbox` and `outbox` tables (Req 2.2, 2.3). Both are needed anyway (both halves are
enabled), and both are required for `ddl-auto=validate` regardless (see the integration risk).

#### Column-name decision: physical `scope_0`/`scope_1` (reconciliation with D1-style nuance)

The starter enriched reference DDL names the scope columns `organization_id`/`facility_id` as
**host-declared aliases**, but the starter **entity** maps the **physical** names
`scope_0 .. scope_15`. Under `hibernate.ddl-auto=validate` Hibernate validates every mapped column
by its **physical** name. The api resolved this in its `V18` migration by using the physical
`scope_0`/`scope_1` names (not `organization_id`/`facility_id`), so `validate` passes without
per-column `@AttributeOverride`. **Communications does the same**: `V2` uses the physical column
names `scope_0`/`scope_1`, with a comment that `scope-fields=[organization_id, facility_id]` binds
positionally to `scope_0`/`scope_1` (Req 2.4). Indexes are placed on the physical `scope_0`/`scope_1`
columns (matching what the api `V18` did).

```sql
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
```

The migration satisfies Req 2.5 (`payload` and `headers` are `JSONB` on both tables), Req 2.6/2.7
(`uq_`-prefixed UNIQUE on `inbox.message_id` and `outbox.event_id`), Req 2.8 (outbox mechanics
`created_at`/`published_at`/`attempts`/`next_attempt_at`; inbox mechanics `received_at`/
`processed_at`), and Req 2.9 (`idx_`-prefixed indexes).

#### `ddl-auto=validate` reconciliation (both tables, both environments)

The starter entities (`OutboxEntry`, `InboxEntry`) map all 16 physical `scope_N` columns plus
`correlation_id`, `headers`, and the mechanics columns. Under `hibernate.ddl-auto=validate`
(the communications production posture, `application.properties`), Hibernate validates that **every
mapped column exists by its physical name** for **both** entities. The migration therefore creates
all 16 `scope_N` slots on both tables and both tables unconditionally, so `validate` is green in
Postgres (prod) and H2 (the migration smoke test).

#### H2 test note (JSONB domain)

The starter entities map `payload` and `headers` as `JSONB`. Under H2 in `MODE=PostgreSQL` (the
communications test datasource: `jdbc:h2:mem:comm_test;MODE=PostgreSQL;...`) the test datasource must
register a `JSONB` domain aliased to H2's native JSON type so those columns validate (Req 2.10):

```sql
CREATE DOMAIN IF NOT EXISTS JSONB AS JSON;
```

This is applied via the JDBC URL `INIT` clause (or an H2-profile pre-migration) for tests that run
the real `V2` DDL against H2. `gen_random_uuid()` and the partial (`WHERE ...`) indexes are accepted
in H2 PostgreSQL mode; because the entities allow app-assigned UUIDs, `DEFAULT gen_random_uuid()` is
not load-bearing in tests. (The existing test `application.properties` disables Flyway for context
slices and uses `create-drop`; the `V2` DDL is validated by a dedicated H2 migration smoke test, as
`V1` already is.)

### JPA integration on the application class

The starter's `MessagingAutoConfiguration` carries its own `@EntityScan`/`@EnableJpaRepositories`
for `InboxEntry`/`OutboxEntry` and their repositories at the outer level, so the host does **not**
need to add `@EntityScan`/`@EnableJpaRepositories` to `StorageManagerCommunicationsApplication`.
This mirrors the api adoption, which added nothing to its application class and relied on the
starter autoconfig. The bare `@SpringBootApplication` on `StorageManagerCommunicationsApplication`
is therefore unchanged; adding the starter dependency plus the config above is sufficient to wire
both halves.

### Starter carrier types (reference)

- `EnrichedMessage(UUID id, String eventType, LinkedHashMap<String,Object> payload,
  LinkedHashMap<String,UUID> scope, UUID correlationId, LinkedHashMap<String,String> headers)` —
  bounds: scope 0..16 unique entries; headers 0..100 entries, key 1..256 chars, value 0..4096
  chars, no duplicate keys. `EnrichedMessage.minimal(id, eventType, payload)` yields empty scope,
  null correlation, empty headers.
- `InboundMessage(UUID messageId, String eventType, Map<String,Object> payload,
  LinkedHashMap<String,UUID> scope, UUID correlationId, LinkedHashMap<String,String> headers)` — the
  consume-side read-back view handed to the enriched handler.
- `OutboxMessage(UUID id, String eventType, Map<String,Object> payload)` — baseline triple.
- `TransportResult` sealed interface: `Success`, `RetryableFailure(String detail)`,
  `PermanentFailure(String detail)`; factories `success()` / `retryable(detail)` / `permanent(detail)`;
  `isSuccess()`.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a
system — essentially, a formal statement about what the system should do. Properties serve as the
bridge between human-readable specifications and machine-verifiable correctness guarantees.*

The properties below are universally quantified and map directly to the testing strategy. Each is
tagged with the requirements it validates and marked **end-to-end** (exercised across the
ingest/inbox or outbox boundary) or **unit-level** (exercised on a single component). Property-based
tests use **jqwik 1.8.5** + **jqwik-spring 0.12.0**, minimum 100 iterations each.

Requirements 2.x (migration DDL), 1.1/1.2/1.3/1.6/1.7/1.8 (wiring/config), 4.1/4.2/4.7 (security
wiring), 6.6/6.7/6.8/6.9 (starter publisher scheduling), 7.5/7.6 (auth-seam config), 9.5, 10.1,
10.3 are **not** property-based (schema/config/infrastructure or architectural scoping statements);
they are covered by smoke, integration, and example tests in the Testing Strategy.

### Property 1: Inbox exactly-once no-op lifecycle under redelivery

*For any* EventEnvelope and *for any* sequence of redeliveries of the same `eventId`, the
`No_Op_Handler` is invoked **exactly once**, exactly one `inbox` row exists for that
`message_id` (= `eventId`), its `processed_at` is non-null after the first successful handler run,
and the Ingest_Endpoint returns `200` for both the first delivery and every duplicate.

**Validates: Requirements 3.6, 5.1, 5.2, 5.3, 5.4, 8.1, 8.2**

### Property 2: Concurrent redelivery is at-most-once

*For any* EventEnvelope delivered by N concurrent `processOnce` invocations of the same
`message_id`, the `No_Op_Handler` is invoked for **at most one** of them and exactly one `inbox`
row exists afterward (the unique constraint on `message_id` makes the losers of the insert race
return without running the handler).

**Validates: Requirements 5.6, 5.7**

### Property 3: Handler failure leaves the row retry-eligible

*For any* EventEnvelope, if the handler raises during `processOnce` then `processed_at` is **not**
stamped and a subsequent `processOnce` with a succeeding handler stamps `processed_at` and runs the
handler (the first failed attempt did not consume the idempotency slot).

**Validates: Requirements 5.5**

### Property 4: Atomic outbox write with per-`event_id` uniqueness

*For any* `EnrichedMessage` appended inside a caller transaction: if the transaction commits then
**exactly one** `outbox` row exists for that `event_id`, initialized unpublished with `attempts = 0`;
if it rolls back then **no** `outbox` row exists; and appending the same `event_id` more than once
yields **exactly one** `outbox` row (`MANDATORY` propagation joins the caller tx; the unique
constraint on `event_id` dedupes).

**Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 8.3**

### Property 5: Transport-result mapping over arbitrary HTTP outcomes

*For any* events HTTP outcome, `EventsIngestTransport.send` maps it deterministically: any `2xx`
(ACCEPTED or DUPLICATE) → `Success`; `400` or `403` → `PermanentFailure`; any `5xx`, a network
error, or a timeout → `RetryableFailure`.

**Validates: Requirements 7.2, 7.3, 7.4**

### Property 6: Lossless, invertible envelope round-trip

*For any* valid envelope inputs `(eventId, eventType, schemaVersion, occurredAt, organizationId,
facilityId, entityType, entityId, actorType, actorId, correlationId, causationId, payload)`, the
`EventEnvelope` → `EnrichedMessage`/`InboundMessage` → canonical `EventEnvelope` JSON round-trip
reconstructs the same 13 fields (scope lifted to `organizationId`/`facilityId`, the seven metadata
headers lifted back, `id` ↔ `eventId`, byte-for-byte `payload`).

**Validates: Requirements 7.1, 8.4**

### Property 7: Scope positional binding (organization → `scope_0`, facility → `scope_1`)

*For any* resolved Organization_Scope and *for any* optional Facility_Scope, the enriched `inbox`
and `outbox` rows persist `organization_id` to `scope_0` and `facility_id` to `scope_1` matching the
declared `scope-fields` order; when `facilityId` is absent, `scope_1` is **null** with no sentinel
substituted; and the transport reads them back into the canonical `organizationId`/`facilityId`.

**Validates: Requirements 1.5, 9.1, 9.2, 9.3, 9.4**

### Property 8: Service-to-service organization resolution

*For any* envelope `organizationId`, a `Trusted_Ingest_Caller` (JWT carrying `SCOPE_events/ingest`)
has its resolved Organization_Scope equal to the envelope `organizationId` with no comparison to any
claim; and *for any* `(envelopeOrg, tokenOrg)` pair, an `End_User_Token` is accepted with
Organization_Scope = `tokenOrg` iff `envelopeOrg == tokenOrg`, otherwise the request is rejected
with `403` and no `inbox` row is written.

**Validates: Requirements 4.3, 4.4, 4.5, 9.1**

### Property 9: Generic acceptance independent of `eventType`

*For any* non-empty `eventType` string on an otherwise-valid envelope, the Ingest_Endpoint accepts
and records the message identically — no branch on, or requirement of, any specific `eventType`
value.

**Validates: Requirements 3.2, 3.3**

### Property 10: Missing required field is rejected without a row

*For any* EventEnvelope with exactly one required field omitted (`eventId`, `eventType`,
`occurredAt`, `organizationId`, `entityType`, `entityId`, `actorType`, or `payload`), the
Ingest_Endpoint responds `400` and writes no `inbox` row.

**Validates: Requirements 3.4**

### Property 11: Actor preservation on persistence

*For any* envelope `actorType` and optional `actorId`, the recorded inbox headers preserve
`actor_type` and `actor_id` exactly as carried on the envelope and never substitute the transport
identity of a `Trusted_Ingest_Caller`.

**Validates: Requirements 4.6**

### Property 12: Secret-free enriched payloads

*For any* payload presented to the messaging base, the mechanics never add payment secrets,
authentication secrets, or PII to the enriched/emitted payload, and the secret-free guard flags any
denylisted secret/PII key so it cannot travel on the event bus.

**Validates: Requirements 10.2**

### End-to-end idempotency / exactly-once chain

```
delivery of EventEnvelope.eventId = K  (possibly redelivered N times)
   └─ message_id = K  (Idempotency_Key preserved on receive, Req 5.1 / 8.4)
        └─ IdempotentInbox.processOnce dedupes on unique(message_id) → ONE inbox row
             └─ No_Op_Handler runs exactly once (first delivery) → processed_at stamped
                  └─ every redelivery is a dedupe-skip (processed_at non-null) → 200, no side effect
...
append of EnrichedMessage.id = K       (if/when a corresponding emission occurs)
   └─ event_id = K  (same stable key, Req 8.4)
        └─ OutboxWriter.append dedupes on unique(event_id) → ONE outbox row
             └─ OutboxPublisher delivers K as EventEnvelope.eventId
                  └─ events dedupes ingest on eventId = K → ONE events-side row
                       └─ 200 (ACCEPTED first, DUPLICATE after) → Success → published_at stamped → no loop
```

The single stable Idempotency_Key `K` (`eventId`) is used as the inbox `message_id` on receive and
as the outbox `event_id` on any corresponding emission, so redelivery is safe at **both** the inbox
and the outbox (Req 8.4).

---

## Error Handling

Delivery error handling on the outbound side is inherited from the starter's `OutboxPublisher`; the
host transport maps events HTTP outcomes onto the three `TransportResult` cases (see the
TransportResult mapping table under Components and Interfaces). Inbound errors surface through the
existing `GlobalExceptionHandler`.

### Inbound ingest errors (via `GlobalExceptionHandler`)

| Condition | Exception | HTTP | Inbox row? |
|---|---|---|---|
| Missing required envelope field | `MethodArgumentNotValidException` (`@Valid` on the record) | `400` | none (Req 3.4) |
| Malformed / unparseable JSON body | `HttpMessageNotReadableException` | `400` | none (Req 3.5) |
| No verified JWT | `AuthenticationException` → `CustomAuthenticationEntryPoint` | `401` | none (Req 4.2) |
| End_User_Token org mismatch / no resolvable org | `AccessDeniedException` → `CustomAccessDeniedHandler` | `403` | none (Req 4.5) |
| Unrecognized role claim on an interactive token | `InvalidBearerTokenException` | `401` | none |

All of these short-circuit **before** `InboundRecordingService.record`, so no `inbox` row is written
on any rejection. The scope-without-role converter extension (above) ensures a valid
`SCOPE_events/ingest` service token is **not** rejected for lacking a role claim.

### Handler failure and inbox retry (inherited from the starter)

`processOnce` runs under `REQUIRED` propagation: on a handler throw, `processed_at` stays null and the
exception propagates, leaving the row eligible for a later `processOnce` retry (Req 5.5). The base
`No_Op_Handler` never throws, so in the base scope every accepted message is stamped on first
delivery; the retry path is exercised in tests with an injected throwing handler.

### Outbound failure / retry / backoff / dead-letter (inherited from the starter)

- `RetryableFailure` (5xx/network/timeout): `attempts++`, `next_attempt_at = base * multiplier^(n-1)`
  capped at `backoff.max`; re-selected when due (Req 6.8).
- After `max-attempts` (10) the row is **parked** (unpublished, never auto-selected) — the de facto
  dead-letter set (Req 6.9). `PermanentFailure` (400/403) parks immediately.
- A transport that **throws** is treated by the publisher as retryable (the row is never lost).
- Dead-letter inspection uses the starter query against the communications database:

  ```sql
  SELECT id, event_id, event_type, payload, created_at, attempts, next_attempt_at
  FROM outbox
  WHERE published_at IS NULL AND attempts >= 10;   -- max-attempts default
  ```

### Startup / configuration errors (fail-fast)

- `outbox.enabled=true` with no `OutboxTransport` bean → `NoSuchBeanDefinitionException` naming
  `OutboxTransport` at startup (Req 1.7); `MessagingTransportConfig` supplies the bean.
- `ddl-auto=validate` with a missing `inbox` **or** `outbox` table → Hibernate validation failure at
  startup; `V2` creates both tables (Req 2.2, 2.3).

### Security seam (how communications authenticates to events)

Mirrors the api Phase-0 seam. In local Phase 0 (`storagemanager.messaging.events.auth.enabled=false`)
communications POSTs to events without a service token (Req 7.6); events scopes by the correctly-set
envelope `organizationId`. When s2s auth is enabled, `MessagingTransportConfig` attaches an
`events/ingest`-scoped bearer token via `EventsAuthTokenProvider` (Req 7.5) — no change to the
envelope mapping. Token production is platform-owned (External Assumption A4).

### Observability

Each event carries a `correlationId` that downstream services echo across the chain, so an inbound
message and any corresponding emission share one correlation id for tracing. The parked-row
dead-letter query surfaces stuck outbound deliveries; operators inspect `attempts`,
`next_attempt_at`, and the failure cause, then may reset `attempts`/`next_attempt_at` to re-drive a
row.

---

## Testing Strategy

Consistent with the communications module (JUnit 5 + Spring Boot Test, `@DataJpaTest`, MockMvc,
`spring-security-test`, H2 in `MODE=PostgreSQL`) and **jqwik 1.8.5 + jqwik-spring 0.12.0** for
property tests. The module already declares all of these dependencies; the only new runtime
dependency is the starter (see Dependencies). Property tests run a **minimum of 100 iterations** and
each is tagged **Feature: messaging, Property {N}: {property text}** plus the requirements it
validates.

### Dual testing approach

- **Property tests** verify the universal properties above (idempotency, dedupe, transactional
  atomicity, round-trip mapping, scope binding, S2S resolution, validation, actor preservation,
  secret-free guard).
- **Unit / example tests** verify specific routes, security wiring, and config-gated behavior.
- **Integration tests** (1–3 examples) verify the starter publisher scheduling, the actual HTTP POST
  to a stubbed events endpoint, and the Flyway migration under H2.
- **Smoke tests** verify one-time wiring and configuration (beans present, fail-fast, defaults,
  issuer config).

### Property-based tests (jqwik) — minimum 100 iterations each

| Property | Level | Sketch |
|---|---|---|
| P1 exactly-once lifecycle | end-to-end | generate envelope + redelivery count; POST (or `processOnce`) that many times; assert handler-invocation counter == 1, one inbox row, `processed_at` non-null, every call 200 |
| P2 concurrent at-most-once | end-to-end | fire N concurrent `processOnce` for one `message_id`; assert handler counter ≤ 1 and exactly one row |
| P3 handler-failure retry | end-to-end | throwing handler → `processed_at` null; re-run with no-op → stamped and handler ran |
| P4 atomic outbox write | `@DataJpaTest` | append in a committing tx → one row (`attempts=0`, unpublished); force rollback → no row; append same `event_id` twice → one row |
| P5 transport-result mapping | unit | generate statuses `{2xx, 400, 403, 5xx}` + thrown `RestClientException` via a stubbed `RestClient`/`MockRestServiceServer`; assert `Success`/`Permanent`/`Retryable` |
| P6 envelope round-trip | unit | arbitrary 13-field inputs → map → canonical JSON → parse back; assert field-wise equality incl. byte-for-byte payload and null `facilityId`/`actorId`/`causationId` |
| P7 scope positional binding | `@DataJpaTest` / end-to-end | arbitrary org + optional facility; assert `scope_0`/`scope_1` on persisted inbox/outbox rows; null facility → null `scope_1` |
| P8 S2S org resolution | end-to-end | arbitrary `(envelopeOrg, tokenOrg)`; trusted token → scope == envelopeOrg; end-user → accept iff equal else 403 + no row |
| P9 generic acceptance | end-to-end | arbitrary `eventType` string; assert accepted + recorded identically |
| P10 required-field validation | end-to-end | drop one required field at a time; assert 400 + no inbox row |
| P11 actor preservation | unit / end-to-end | arbitrary `actorType`/`actorId`; assert recorded headers `actor_type`/`actor_id` match the envelope |
| P12 secret-free guard | unit | arbitrary payloads incl. denylisted secret/PII keys; assert the guard flags them and the mechanics add none |

### Unit / example tests

- `InboundEventController` route: `POST /api/v1/communications` reaches the controller (Req 3.1); a
  valid body returns `200` and leaves one inbox row (Req 3.2).
- `CustomJwtAuthenticationConverter`: a scope-only `events/ingest` token (no `custom:role`)
  authenticates with authority `SCOPE_events/ingest` and a null-role `UserIdentity`; a role-only
  token still grants `ROLE_<role>`; both present → both authorities; unrecognized role → `401`;
  no role and no ingest scope → `401`.
- `IngestAuthorizer`: trusted-caller and end-user branches (example mirrors of Property 8).
- No token → `401` with no inbox row (Req 4.2); malformed JSON → `400` with no row (Req 3.5).
- `MessagingTransportConfig`: `auth.enabled=true` → request carries `Authorization: Bearer`;
  `auth.enabled=false` → no `Authorization` header (Req 7.5, 7.6).

### Integration tests (1–3 examples)

- **Stubbed events endpoint** (WireMock or `MockRestServiceServer` as `/api/v1/events`): drive the
  publisher (or call the transport directly) and assert the posted JSON is the canonical 13-field
  envelope; stub `200` → `published_at` stamped and row not re-selected (Req 6.7); `5xx` →
  `attempts++` with advanced `next_attempt_at` (Req 6.8); drive to `max-attempts` → parked (Req 6.9);
  `400`/`403` → parked immediately.
- **Publisher selection** (Req 6.6): seed several due/undue unpublished rows; assert only due rows up
  to `batch-size` are delivered.

### Smoke tests

- Context loads with both halves enabled: `IdempotentInbox`, `OutboxWriter`, `OutboxPublisher`, and
  the host `OutboxTransport` beans are present (Req 1.2, 1.3, 1.6).
- Fail-fast: an `ApplicationContextRunner` with `outbox.enabled=true` and **no** `OutboxTransport`
  bean fails startup with a `NoSuchBeanDefinitionException` naming `OutboxTransport` (Req 1.7).
- Defaults: unsupplied starter properties resolve to documented defaults — `poll-interval-ms=1000`,
  `batch-size=100`, `max-attempts=10`, backoff `base=1s`/`max=5m`/`multiplier=2` (Req 1.8).
- Local profile: `issuer-uri`/`jwk-set-uri` point at `http://localhost:8080/api/v1/local` (Req 4.7).

### Migration / schema tests (Req 2.x — not PBT)

- **H2 migration smoke test** (mirrors the existing `V1` smoke test): run `V2` against
  `jdbc:h2:mem:...;MODE=PostgreSQL` with `CREATE DOMAIN IF NOT EXISTS JSONB AS JSON` applied first
  (Req 2.10); assert both `inbox` and `outbox` tables exist with physical `scope_0..scope_15` columns
  (Req 2.4), `JSONB` `payload`/`headers` (Req 2.5), `uq_`-prefixed unique constraints on
  `inbox.message_id` and `outbox.event_id` (Req 2.6, 2.7), the mechanics columns (Req 2.8), and
  `idx_`-prefixed indexes (Req 2.9).
- **`validate` posture**: a Postgres-backed (or Testcontainers) context starts with
  `ddl-auto=validate` after `V2`, proving the starter entities validate against both tables (Req 2.2,
  2.3) with no `@AttributeOverride` (physical-name decision).

### Verification

Run `./mvnw test` (communications module). H2 tests that execute the real `V2` DDL require the
`CREATE DOMAIN IF NOT EXISTS JSONB AS JSON` `INIT` clause on the test datasource so the enriched
`inbox`/`outbox` DDL and the entities' `JSONB` columns validate. Context slices that do not run
Flyway continue to use `create-drop` as the existing test `application.properties` configures.

---

## Dependencies

- **New Maven dependency** in `storage-manager-communications/pom.xml`:

  ```xml
  <dependency>
      <groupId>com.storagemanager</groupId>
      <artifactId>storage-manager-messaging</artifactId>
      <version>0.1.0</version>
  </dependency>
  ```

  The communications module already brings `spring-boot-starter-web`, `data-jpa`, `validation`,
  `postgresql`, `flyway-core` + `flyway-database-postgresql`, `springdoc`,
  `oauth2-resource-server`, and the test stack (`spring-boot-starter-test`, `spring-security-test`,
  jqwik 1.8.5 + jqwik-spring 0.12.0, H2) — so no additional runtime deps are required. If WireMock
  is used for the stubbed-events integration test, add it as a `test`-scoped dependency.
- **No change** to the starter itself (communications is a pure consumer of the published `0.1.0`
  artifact).

## Summary of reconciled integration decisions

1. **Physical `scope_N` migration (Req 2.4).** `V2` uses physical `scope_0`/`scope_1` names (not
   `organization_id`/`facility_id`), indexing the physical columns, so `ddl-auto=validate` passes
   without per-column `@AttributeOverride` — identical to the api `V18` decision.
2. **Ingest path vs D1 (Req 3.1).** Verified no collision with `CommunicationController`
   (`/api/v1/communication-templates`); the design honors D1's `POST /api/v1/communications`
   unchanged. No deviation recorded.
3. **JWT converter scope-without-role gap (Req 4.1/4.3).** `CustomJwtAuthenticationConverter` is
   extended so a validated `events/ingest` scope-only token authenticates (granted
   `SCOPE_events/ingest`) with no role claim, while role mapping (`ROLE_<role>`) is preserved when a
   role is present.
4. **Both tables under `ddl-auto=validate` (Req 2.2/2.3).** The starter `@EntityScans` both
   `InboxEntry` and `OutboxEntry` unconditionally, so `V2` creates **both** `inbox` and `outbox`
   tables (both halves are enabled here regardless).
