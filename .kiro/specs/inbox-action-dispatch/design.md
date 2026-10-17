# Design Document: Inbox Action-Dispatch

## Overview

This feature adds the **generic inbox action-dispatch mechanism** to `storage-manager-communications`:
the routing layer that connects an admitted inbound message to the handler that interprets it. It is
**step 5** of the lead event-driven communication flow — the wiring the `lead-communication-action`
spec deferred as its External Assumption A2.

Today the messaging base runs the inbox end-to-end with a **no-op** handler. In
`InboundRecordingService.record(...)` (a `messaging`-owned service), the inbox is driven by:

```java
inbox.processOnce(message, m -> { /* no-op */ });
```

This spec replaces that pass-through `Consumer<InboundMessage>` with a **Dispatching_Handler** that:

1. selects a registered **Inbox_Handler** by the message `eventType` only (never the payload),
2. reconstructs the 13-field `EventEnvelope` the handler expects from the `InboundMessage` read-view,
3. invokes the selected handler, and
4. maps the handler's outcome onto `processOnce` so that **success** and **terminal rejection** both
   stamp `processed_at` (consume the message) while a **transient failure** propagates out of
   `processOnce` (leaves `processed_at` unstamped, message redelivered).

The mechanism is **generic and reusable**. Handlers register by declaring the single `eventType`
value they handle (Decision D3: exactly one `eventType` per handler); future entity actions (tenant,
lease, unit) add an `Inbox_Handler` bean without touching the router. This spec ships exactly one
concrete binding — `eventType == "SendCommunication"` → the existing `ActionProcessor` — and
**references, does not reimplement**, that processor.

### What this spec owns vs reuses

| Concern | Owner | This spec's relationship |
|---|---|---|
| `EventEnvelope` 13-field wire record, `InboundMessage` read-view, `IdempotentInbox` / `processOnce`, `InboundRecordingService` | `messaging` | Reuses the types; supplies the real handler into the existing seam; reconstructs an `EventEnvelope` from the `InboundMessage`. The one cross-spec code touch is the handler wired into `InboundRecordingService` (see "The cross-spec touch"). |
| `ActionProcessor.process(EventEnvelope)`, `SendCommunicationAction.EVENT_TYPE` (`"SendCommunication"`), `ActionValidationException` (terminal) / `ActionRetryableException` (retryable) | `lead-communication-action` | Routes a matching message to `ActionProcessor` and maps its outcome; does not reimplement validation, recipient resolution, personalization, or the send. |
| Inbox uniqueness, dedupe, insert-on-receive, `processed_at`-stamp-on-success, at-most-once under concurrent redelivery | `messaging` starter (`IdempotentInbox`) | Preserved unchanged. This spec only decides whether the handler **completes normally** (stamp) or **throws** (retry). |

### Confirmed against real source

- `InboundMessage` (starter, `com.storagemanager.messaging.api.InboundMessage`) is a record with
  accessors `messageId()` (`UUID`), `eventType()` (`String`), `payload()` (`Map<String,Object>`),
  `scope()` (`LinkedHashMap<String,UUID>`), `correlationId()` (`UUID`), `headers()`
  (`LinkedHashMap<String,String>`).
- `IdempotentInbox.processOnce(InboundMessage, Consumer<InboundMessage>)` stamps `processed_at`
  **only** when the `Consumer` returns normally; if the `Consumer` throws, the exception propagates,
  `processed_at` stays null, and the row remains retry-eligible. This is the exact lever the outcome
  mapping pulls.
- `InboundRecordingService` (communications `service` package, owned by `messaging`) currently passes
  the no-op `m -> {}` consumer. This is the single integration point.
- `EventEnvelope` (communications `messaging` package) is the 13-field record
  `(eventId, eventType, schemaVersion, occurredAt, organizationId, facilityId, entityType, entityId,
  actorType, actorId, correlationId, causationId, payload)` with a nested `ActorType { SYSTEM, USER }`.
  `InboundRecordingService.toInboundMessage` writes the seven non-promoted fields as headers
  (`schema_version`, `occurred_at`, `entity_type`, `entity_id`, `actor_type`, and — only when present
  — `actor_id`, `causation_id`); reconstruction reads those same header keys.
- `ActionProcessor.process` returns `ActionResult` (a `record(boolean processed)`), throws
  `ActionValidationException` / `ActionRetryableException` (both extend `ActionProcessingException`).

---

## Architecture

### Position in the flow

```mermaid
flowchart LR
    subgraph EVT["storage-manager-events (A1, out of scope)"]
        W["LeadCreated workflow<br/>emits SendCommunication"]
    end
    subgraph COMM["storage-manager-communications"]
        direction TB
        IEC["InboundEventController<br/>(messaging)"]
        IRS["InboundRecordingService<br/>(messaging — the one touch)"]
        II["IdempotentInbox.processOnce<br/>(starter, unchanged)"]
        DH["Dispatching_Handler<br/>Consumer&lt;InboundMessage&gt;<br/>(THIS SPEC)"]
        REG["Handler_Registry<br/>eventType -&gt; Inbox_Handler<br/>(THIS SPEC)"]
        SCB["Send_Communication_Binding<br/>Inbox_Handler for SendCommunication<br/>(THIS SPEC)"]
        AP["ActionProcessor.process(EventEnvelope)<br/>(lead-communication-action)"]
    end
    W -->|EventEnvelope JSON| IEC
    IEC --> IRS
    IRS -->|processOnce(message, Dispatching_Handler)| II
    II -->|accept(InboundMessage)| DH
    DH -->|lookup by eventType| REG
    DH -->|reconstruct EventEnvelope, invoke| SCB
    SCB -->|process(envelope)| AP
```

The thick new pieces are `Dispatching_Handler`, `Handler_Registry`, `Inbox_Handler`, and the
`Send_Communication_Binding`. `InboundEventController`, `IdempotentInbox`, and the inbox table are
unchanged. `InboundRecordingService` changes in exactly one line (which handler it passes).

### Layered responsibilities

| Layer | Component | Owner | Responsibility |
|---|---|---|---|
| Seam | `InboundRecordingService` | `messaging` (1-line change) | Pass the `Dispatching_Handler` to `processOnce` instead of the no-op |
| Consume | `IdempotentInbox` | `messaging` starter (unchanged) | Insert-on-receive, dedupe, run handler, stamp `processed_at` on normal return |
| Router | `Dispatching_Handler` | this spec (new) | `Consumer<InboundMessage>`: look up handler by `eventType`, reconstruct `EventEnvelope`, invoke, map outcome |
| Registry | `Handler_Registry` | this spec (new) | `Map<String, InboxHandler>` keyed by single `eventType`; fail startup on duplicate |
| Abstraction | `InboxHandler` | this spec (new) | Interface: declare one `eventType`, expose `handle(EventEnvelope)` |
| Binding | `SendCommunicationBinding` | this spec (new) | `InboxHandler` for `"SendCommunication"`; delegates to `ActionProcessor.process` |
| Reconstruct | `EnvelopeReconstructor` | this spec (new) | `InboundMessage` → `EventEnvelope`; terminal on header parse failure |
| Action | `ActionProcessor` | `lead-communication-action` (unchanged) | `ActionResult process(EventEnvelope)`; throws terminal/retryable |

### Sequence diagram — inbox → dispatch → handler → outcome

```mermaid
sequenceDiagram
    participant IRS as InboundRecordingService (messaging)
    participant II as IdempotentInbox.processOnce (starter)
    participant DH as Dispatching_Handler (this spec)
    participant REG as Handler_Registry
    participant ER as EnvelopeReconstructor
    participant H as Inbox_Handler (e.g. SendCommunicationBinding)
    participant AP as ActionProcessor

    IRS->>II: processOnce(message, Dispatching_Handler)
    note over II: insert-on-receive (processed_at null); dedupe-skip if already stamped
    II->>DH: accept(InboundMessage)
    DH->>REG: lookup(message.eventType())
    alt no handler for eventType (Unmatched_Message)
        REG-->>DH: empty
        DH->>DH: record unmatched eventType (log/diagnostic)
        DH-->>II: return normally  (processed_at STAMPED, no redelivery)
    else handler found
        REG-->>DH: Inbox_Handler
        DH->>ER: reconstruct(message)
        alt header absent / unparseable (reconstruction failure)
            ER-->>DH: ReconstructionException(field, reason)
            DH->>DH: record reason + offending field (log/diagnostic)
            DH-->>II: return normally  (processed_at STAMPED, no redelivery)
        else reconstructed
            ER-->>DH: EventEnvelope
            DH->>H: handle(envelope)
            H->>AP: process(envelope)
            alt success -> ActionResult
                AP-->>H: ActionResult(processed)
                H-->>DH: return
                DH-->>II: return normally  (processed_at STAMPED, processed once)
            else terminal -> ActionValidationException
                AP-->>H: throws ActionValidationException
                H-->>DH: propagates
                DH->>DH: record terminal rejection reason (log/diagnostic; best-effort)
                DH-->>II: return normally  (processed_at STAMPED, no redelivery)
            else retryable -> ActionRetryableException
                AP-->>H: throws ActionRetryableException
                H-->>DH: propagates
                DH-->>II: THROW out of processOnce  (processed_at NOT stamped, redelivered)
            end
        end
    end
```

The single rule the whole design turns on: **the `Dispatching_Handler` throws out of `processOnce`
only for a retryable outcome.** Every other outcome — success, terminal validation rejection,
reconstruction failure, unmatched event type — returns normally so `IdempotentInbox` stamps
`processed_at` and the message is consumed exactly once and never redelivered.

### The cross-spec touch (flag for review)

`InboundRecordingService` is owned by the `messaging` spec. This spec changes which
`Consumer<InboundMessage>` it passes to `processOnce` — from the inline no-op to the injected
`Dispatching_Handler` bean. This is the **one cross-spec file edit**, directly analogous to the
`SendService.sendInternal` note in `lead-communication-action` (a small, additive touch to a
collaborator owned by another spec), and is surfaced explicitly for review.

Two viable shapes, both keeping the dedupe mechanics unchanged:

- **Chosen — inject the seam handler.** Change `InboundRecordingService` to depend on a
  `Consumer<InboundMessage>` bean (the inbox handler seam) and pass it to `processOnce`. The
  messaging base provides a no-op `@Bean` under `@ConditionalOnMissingBean`; this spec provides the
  `Dispatching_Handler` as the primary `Consumer<InboundMessage>` bean, which supersedes the no-op.
  The edit to `InboundRecordingService` is minimal (constructor-inject the seam, pass it instead of
  `m -> {}`), and it keeps `InboundRecordingService` ignorant of dispatch — it still just maps and
  records. This is the recommended split: the `messaging`-owned file changes by one dependency, and
  all routing logic lives in this spec's beans.
- **Alternative — rejected.** Hard-wire the `Dispatching_Handler` directly into
  `InboundRecordingService`. Rejected because it couples a `messaging`-owned service to this spec's
  router type and defeats the "generic seam" intent.

> **Review callout.** The only `messaging`-owned file this spec edits is
> `InboundRecordingService.java`: replace the inline `m -> { /* no-op */ }` with an injected
> `Consumer<InboundMessage>` handler-seam bean. If the reviewer prefers the messaging base retain the
> no-op literal, the fallback is to override the inbox handler via a dedicated seam bean without
> editing `InboundRecordingService`; that requires a seam-extraction change to the messaging base and
> is noted as the less-preferred option.

---

## Components and Interfaces

All new types live under `com.storagemanager.communications.inbox` (new package), except the
reused `EventEnvelope` (`messaging` package) and the `ActionProcessor` / exceptions
(`action` package).

### `InboxHandler` (the registration abstraction) — Req 1

```java
package com.storagemanager.communications.inbox;

import com.storagemanager.communications.messaging.EventEnvelope;

/**
 * A handler that interprets an admitted inbound message of exactly one EventEnvelope eventType.
 * New entity actions register by implementing this interface as a Spring bean; the
 * Dispatching_Handler routes to them without modification (Req 1.1, 1.5, Decision D3).
 */
public interface InboxHandler {

    /**
     * The single eventType value this handler handles (Req 1.2, D3). Used as its Handler_Registry
     * key. Two handlers returning the same value fail application startup (Req 1.6).
     */
    String eventType();

    /**
     * Interpret a routed message (Req 1.3). Invoked by the Dispatching_Handler with the
     * EventEnvelope reconstructed from the InboundMessage. A normal return signals success; a
     * thrown exception is classified by the Dispatching_Handler's outcome mapping (Req 5).
     */
    void handle(EventEnvelope envelope);
}
```

### `HandlerRegistry` — Req 1.4, 1.6

Assembled from **all** registered `InboxHandler` beans, keyed by `eventType()`. A duplicate
`eventType` fails application startup with an error naming the conflicting value. The collision check
runs in the constructor (equivalently a `@PostConstruct`), so the failure happens during context
refresh — the application never boots with an ambiguous registry.

```java
package com.storagemanager.communications.inbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Immutable lookup of registered InboxHandlers keyed by their single declared eventType
 * (Req 1.4). Built once at startup from the InboxHandler beans Spring discovers. A duplicate
 * eventType is a configuration error that fails startup (Req 1.6).
 */
@Component
public class HandlerRegistry {

    private final Map<String, InboxHandler> byEventType;

    /**
     * @param handlers all InboxHandler beans in the context (Spring injects the full list)
     * @throws DuplicateHandlerRegistrationException if two handlers declare the same eventType,
     *         identifying the conflicting eventType value (Req 1.6)
     */
    public HandlerRegistry(List<InboxHandler> handlers) {
        Map<String, InboxHandler> map = new HashMap<>();
        for (InboxHandler handler : handlers) {
            String eventType = handler.eventType();
            InboxHandler existing = map.putIfAbsent(eventType, handler);
            if (existing != null) {
                throw new DuplicateHandlerRegistrationException(eventType, existing, handler);
            }
        }
        this.byEventType = Map.copyOf(map);
    }

    /** @return the handler for eventType, or empty for an Unmatched_Message (Req 6). */
    public Optional<InboxHandler> lookup(String eventType) {
        return Optional.ofNullable(byEventType.get(eventType));
    }
}
```

```java
package com.storagemanager.communications.inbox;

/**
 * Thrown during context refresh when two InboxHandler beans declare the same eventType, naming the
 * conflicting value so startup fails diagnosably (Req 1.6).
 */
public class DuplicateHandlerRegistrationException extends RuntimeException {

    public DuplicateHandlerRegistrationException(String eventType, InboxHandler a, InboxHandler b) {
        super("Duplicate Inbox_Handler registration for eventType '" + eventType + "': "
                + a.getClass().getName() + " and " + b.getClass().getName());
    }
}
```

> **Why the constructor throws.** A `@Component` whose constructor throws aborts the Spring context
> refresh, which is exactly "fail application startup with an error identifying the conflicting
> Event_Type value" (Req 1.6). Injecting `List<InboxHandler>` gives the registry every handler bean,
> so the collision is detected across the whole registered set, not pairwise at registration time.

### `EnvelopeReconstructor` — Req 3, 7, 8

Rebuilds the 13-field `EventEnvelope` from the `InboundMessage` read-view. The mapping is the exact
inverse of `InboundRecordingService.toInboundMessage` (confirmed against source): `messageId → eventId`,
`eventType`, `payload`, `correlationId` pass through; scope `organization_id → organizationId` and
`facility_id → facilityId`; the seven header keys rebuild `schemaVersion`, `occurredAt`, `entityType`,
`entityId`, `actorType`, `actorId`, `causationId`.

```java
package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * Reconstructs an EventEnvelope from an InboundMessage read-view (Req 3). The mapping is the exact
 * inverse of InboundRecordingService.toInboundMessage: scope organization_id/facility_id ->
 * organizationId/facilityId (Req 3.4, 8.1, 8.2); messageId -> eventId (Req 3.2, 8.3); correlationId
 * carried through (Req 3.3, 7); the seven metadata header keys rebuild the remaining fields
 * (Req 3.5). A required header that is absent or unparseable is a terminal reconstruction failure
 * (Req 3.6) raised as ReconstructionException naming the offending field (Req 3.7).
 */
@Component
public class EnvelopeReconstructor {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    public EventEnvelope reconstruct(InboundMessage m) {
        LinkedHashMap<String, UUID> scope =
                m.scope() != null ? m.scope() : new LinkedHashMap<>();
        Map<String, String> h = m.headers() != null ? m.headers() : Map.of();

        UUID organizationId = requireScope(scope, ORG);             // Req 3.4, 8.1
        UUID facilityId = scope.get(FAC);                            // nullable, no sentinel (Req 8.2)

        int schemaVersion = parseInt(h, "schema_version");          // Req 3.5
        Instant occurredAt = parseInstant(h, "occurred_at");
        String entityType = requireHeader(h, "entity_type");
        UUID entityId = parseUuid(h, "entity_id");
        ActorType actorType = parseActorType(h, "actor_type");
        UUID actorId = optionalUuid(h, "actor_id");                 // nullable by contract
        UUID causationId = optionalUuid(h, "causation_id");         // nullable by contract

        return new EventEnvelope(
                m.messageId(),          // eventId = messageId, preserved key (Req 3.2, 8.3)
                m.eventType(),          // Req 3.1
                schemaVersion,
                occurredAt,
                organizationId,
                facilityId,
                entityType,
                entityId,
                actorType,
                actorId,
                m.correlationId(),      // Req 3.3, 7.1 / 7.2
                causationId,
                m.payload());           // Req 3.1
    }

    // --- helpers: a missing/unparseable REQUIRED field throws ReconstructionException(field) ---

    private UUID requireScope(Map<String, UUID> scope, String field) {
        UUID v = scope.get(field);
        if (v == null) {
            throw new ReconstructionException(field, "required scope value absent");
        }
        return v;
    }

    private String requireHeader(Map<String, String> h, String field) {
        String v = h.get(field);
        if (v == null || v.isBlank()) {
            throw new ReconstructionException(field, "required header absent");
        }
        return v;
    }

    private int parseInt(Map<String, String> h, String field) {
        try {
            return Integer.parseInt(requireHeader(h, field));
        } catch (NumberFormatException ex) {
            throw new ReconstructionException(field, "not an integer", ex);
        }
    }

    private Instant parseInstant(Map<String, String> h, String field) {
        try {
            return Instant.parse(requireHeader(h, field));
        } catch (RuntimeException ex) {
            throw new ReconstructionException(field, "not an ISO-8601 instant", ex);
        }
    }

    private UUID parseUuid(Map<String, String> h, String field) {
        try {
            return UUID.fromString(requireHeader(h, field));
        } catch (IllegalArgumentException ex) {
            throw new ReconstructionException(field, "not a UUID", ex);
        }
    }

    private ActorType parseActorType(Map<String, String> h, String field) {
        try {
            return ActorType.valueOf(requireHeader(h, field));
        } catch (IllegalArgumentException ex) {
            throw new ReconstructionException(field, "not a valid ActorType", ex);
        }
    }

    private UUID optionalUuid(Map<String, String> h, String field) {
        String v = h.get(field);
        if (v == null || v.isBlank()) {
            return null;                                            // nullable by contract
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            throw new ReconstructionException(field, "present but not a UUID", ex);
        }
    }
}
```

```java
package com.storagemanager.communications.inbox;

/**
 * Terminal reconstruction failure: a required EventEnvelope field could not be populated from the
 * InboundMessage because a header/scope value was absent or unparseable (Req 3.6). Carries the
 * offending field name and reason so the consumed message is diagnosable (Req 3.7). Caught inside
 * the Dispatching_Handler, which records the reason and completes normally (processed_at stamped).
 */
public class ReconstructionException extends RuntimeException {

    private final String field;

    public ReconstructionException(String field, String reason) {
        this(field, reason, null);
    }

    public ReconstructionException(String field, String reason, Throwable cause) {
        super("reconstruction failed for field '" + field + "': " + reason, cause);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
```

### `DispatchingHandler` — Req 2, 3.6, 5, 6

The `Consumer<InboundMessage>` passed to `processOnce`. It is the single place the outcome mapping
lives. Note the field semantics: **only** `ActionRetryableException` escapes; everything else returns
normally.

```java
package com.storagemanager.communications.inbox;

import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.storagemanager.communications.action.ActionRetryableException;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * The inbox handler seam (Req 2.1): a Consumer<InboundMessage> that routes an admitted message to
 * its registered InboxHandler by eventType only (Req 2.2, 2.4), reconstructs the EventEnvelope
 * (Req 3), invokes the handler exactly once (Req 2.3), and maps the outcome onto processOnce
 * (Req 5, 6).
 *
 * <p>Outcome mapping (total function over the five outcomes):
 * <ul>
 *   <li>success            -> return normally  -> processed_at stamped   (Req 5.1)</li>
 *   <li>ActionRetryable    -> re-throw         -> processed_at NOT stamped, redelivered (Req 5.2)</li>
 *   <li>ActionValidation   -> record + return  -> processed_at stamped, terminal (Req 5.3, 5.4)</li>
 *   <li>reconstruction fail -> record + return -> processed_at stamped, terminal (Req 3.6, 3.7)</li>
 *   <li>unmatched eventType -> record + return -> processed_at stamped, no handler (Req 6)</li>
 * </ul>
 */
@Component
public class DispatchingHandler implements Consumer<InboundMessage> {

    private static final Logger log = LoggerFactory.getLogger(DispatchingHandler.class);

    private final HandlerRegistry registry;
    private final EnvelopeReconstructor reconstructor;

    public DispatchingHandler(HandlerRegistry registry, EnvelopeReconstructor reconstructor) {
        this.registry = registry;
        this.reconstructor = reconstructor;
    }

    @Override
    public void accept(InboundMessage message) {
        // Route by eventType ONLY — never inspect the payload (Req 2.4).
        Optional<InboxHandler> handler = registry.lookup(message.eventType());

        if (handler.isEmpty()) {
            // Unmatched_Message: consume as a terminal processed no-op; record the eventType
            // (Req 6.1, 6.2) and invoke no handler (Req 6.3). Returning normally stamps processed_at.
            log.warn("Unmatched inbox eventType '{}' for messageId {}; no handler registered, "
                    + "consuming as terminal no-op", message.eventType(), message.messageId());
            return;
        }

        // Reconstruct the EventEnvelope the handler expects (Req 3). A terminal reconstruction
        // failure is consumed as a processed no-op with the reason + offending field recorded
        // (Req 3.6, 3.7); no handler is invoked.
        final EventEnvelope envelope;
        try {
            envelope = reconstructor.reconstruct(message);
        } catch (ReconstructionException ex) {
            log.warn("Reconstruction failed for messageId {} (field '{}'): {}; consuming as "
                    + "terminal no-op", message.messageId(), ex.field(), ex.getMessage());
            return; // processed_at stamped, not redelivered (Req 3.6)
        }

        // Invoke the selected handler exactly once (Req 2.3) and map its outcome (Req 5).
        try {
            handler.get().handle(envelope);
            // Success: return normally so processOnce stamps processed_at (Req 5.1).
        } catch (ActionRetryableException ex) {
            // Transient: propagate out of processOnce so processed_at is NOT stamped and the
            // message is redelivered (Req 5.2).
            throw ex;
        } catch (ActionValidationException ex) {
            // Terminal rejection (poison): record the reason, then return normally so processed_at
            // is stamped and the message is not redelivered (Req 5.3, 5.4). A failure WHILE recording
            // must not cause redelivery (Req 5.5), so recording is best-effort inside this catch.
            try {
                log.warn("Terminal rejection for messageId {} eventType '{}': {}; consuming "
                        + "(processed, not redelivered)", message.messageId(),
                        message.eventType(), ex.getMessage());
            } catch (RuntimeException recordingFailure) {
                // Swallow: diagnostics recording must not turn a terminal into a redelivery (Req 5.5).
            }
            // return normally
        }
    }
}
```

> **Diagnostics are a log obligation only (Decision D2).** "Record the reason" for an unmatched
> message, a reconstruction failure, and a terminal rejection is satisfied by structured logging.
> There is **no dead-letter entity, table, migration, or record** — this spec introduces no
> persistence. Req 5.5 is honored by wrapping the terminal-rejection recording in its own
> `try/catch` so a logging failure cannot propagate and cause redelivery.

### `SendCommunicationBinding` — Req 4

The single concrete handler this spec ships. It declares `"SendCommunication"` and delegates to
`ActionProcessor.process`, **without** catching or translating the processor's exceptions — the
`DispatchingHandler` maps `ActionValidationException`/`ActionRetryableException` centrally. This split
keeps the binding a thin adapter and the outcome policy in one place.

```java
package com.storagemanager.communications.inbox;

import org.springframework.stereotype.Component;

import com.storagemanager.communications.action.ActionProcessor;
import com.storagemanager.communications.action.SendCommunicationAction;
import com.storagemanager.communications.messaging.EventEnvelope;

/**
 * The one concrete binding this spec provides (Req 4): an InboxHandler declaring
 * SendCommunicationAction.EVENT_TYPE ("SendCommunication") that delegates to the existing
 * ActionProcessor (Req 4.1, 4.2). It does NOT reimplement validation, recipient resolution,
 * personalization, or the send (Req 4.3), and it does NOT catch the processor's exceptions — the
 * DispatchingHandler maps ActionValidationException/ActionRetryableException centrally. A normal
 * return (an ActionResult from the processor) means the handler completes normally so the
 * DispatchingHandler completes normally (Req 4.4).
 */
@Component
public class SendCommunicationBinding implements InboxHandler {

    private final ActionProcessor actionProcessor;

    public SendCommunicationBinding(ActionProcessor actionProcessor) {
        this.actionProcessor = actionProcessor;
    }

    @Override
    public String eventType() {
        return SendCommunicationAction.EVENT_TYPE; // "SendCommunication" (Req 4.1)
    }

    @Override
    public void handle(EventEnvelope envelope) {
        // Delegate; let ActionValidationException / ActionRetryableException propagate to the
        // DispatchingHandler's central outcome mapping (Req 4.2, 4.3). The returned ActionResult is
        // intentionally ignored here — a normal return is the success signal (Req 4.4).
        actionProcessor.process(envelope);
    }
}
```

> **Why the binding does not translate exceptions.** `ActionProcessor` already raises the two
> outcome-classed exceptions (`ActionValidationException` terminal, `ActionRetryableException`
> retryable). The cleanest split is: the binding carries no policy and just calls `process`; the
> `DispatchingHandler` owns the one outcome-mapping table for **all** handlers. If a future handler
> raises some other exception type, the `DispatchingHandler` can extend the mapping in one place
> rather than each binding reimplementing it. Confirmed as the cleanest split for this spec.

### `InboundRecordingService` integration (the one messaging-owned edit)

```java
// messaging-owned: com.storagemanager.communications.service.InboundRecordingService (EDITED)
@Service
public class InboundRecordingService {

    private final IdempotentInbox inbox;
    private final Consumer<InboundMessage> inboxHandler; // injected seam (was inline no-op)

    public InboundRecordingService(IdempotentInbox inbox, Consumer<InboundMessage> inboxHandler) {
        this.inbox = inbox;
        this.inboxHandler = inboxHandler;
    }

    @Transactional
    public void record(EventEnvelope envelope, UUID organizationScope) {
        InboundMessage message = toInboundMessage(envelope, organizationScope);
        inbox.processOnce(message, inboxHandler); // was: m -> { /* no-op */ }
    }
    // toInboundMessage(...) unchanged
}
```

The `DispatchingHandler` is a `@Component implements Consumer<InboundMessage>`, so it is the primary
`Consumer<InboundMessage>` bean and satisfies the injected seam. The messaging base's no-op bean (if
retained) is declared `@ConditionalOnMissingBean`, so this spec's `DispatchingHandler` supersedes it.

---

## Data Models

**This spec introduces no new persistent entity, table, or Flyway migration (Decision D2).** It adds
no column to `inbox`. All new types are in-memory routing/reconstruction types; the only durable
effect is the existing `inbox.processed_at` stamp owned by the `messaging` starter. "Recording" an
unmatched event type, a reconstruction failure, or a terminal rejection is a **log/diagnostic
obligation only** — no dead-letter record is written.

In-memory types introduced by this spec:

| Type | Kind | Purpose |
|---|---|---|
| `InboxHandler` | interface | Registration abstraction: one `eventType()` + `handle(EventEnvelope)` (Req 1) |
| `HandlerRegistry` | `@Component` | `Map<String, InboxHandler>` keyed by single `eventType`; duplicate → startup failure (Req 1.4, 1.6) |
| `DispatchingHandler` | `@Component implements Consumer<InboundMessage>` | The seam handler; routing + outcome mapping (Req 2, 5, 6) |
| `EnvelopeReconstructor` | `@Component` | `InboundMessage` → `EventEnvelope`; terminal on parse failure (Req 3) |
| `SendCommunicationBinding` | `@Component implements InboxHandler` | The one concrete binding (Req 4) |
| `ReconstructionException` | `RuntimeException` | Terminal reconstruction failure carrying `field` + reason (Req 3.6, 3.7) |
| `DuplicateHandlerRegistrationException` | `RuntimeException` | Startup failure naming the conflicting `eventType` (Req 1.6) |

### Reconstruction field mapping (`InboundMessage` → `EventEnvelope`)

Exact inverse of `InboundRecordingService.toInboundMessage`, verified against source:

| `EventEnvelope` field | Source on `InboundMessage` | Required | Notes |
|---|---|---|---|
| `eventId` | `messageId()` | yes | preserved idempotency key; never regenerated (Req 3.2, 8.3) |
| `eventType` | `eventType()` | yes | the routing key (Req 3.1) |
| `schemaVersion` | `headers()["schema_version"]` | yes | `Integer.parseInt`; unparseable → terminal (Req 3.5, 3.6) |
| `occurredAt` | `headers()["occurred_at"]` | yes | `Instant.parse`; unparseable → terminal |
| `organizationId` | `scope()["organization_id"]` | yes | absent → terminal; no sentinel (Req 3.4, 8.1) |
| `facilityId` | `scope()["facility_id"]` | no | null preserved, no sentinel (Req 8.2) |
| `entityType` | `headers()["entity_type"]` | yes | blank/absent → terminal |
| `entityId` | `headers()["entity_id"]` | yes | `UUID.fromString`; unparseable → terminal |
| `actorType` | `headers()["actor_type"]` | yes | `ActorType.valueOf`; unknown → terminal |
| `actorId` | `headers()["actor_id"]` | no | nullable by contract; present-but-bad → terminal |
| `correlationId` | `correlationId()` | no | carried through; null → processor derives from `eventId` (Req 7) |
| `causationId` | `headers()["causation_id"]` | no | nullable by contract; present-but-bad → terminal |
| `payload` | `payload()` | yes | passed through opaque (Req 3.1) |

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a
system — essentially, a formal statement about what the system should do. Properties serve as the
bridge between human-readable specifications and machine-verifiable correctness guarantees.*

The prework classified the acceptance criteria and the properties were then reflected to remove
redundancy:

- The **reconstruction round-trip** (Property 2) subsumes the field-by-field reconstruction criteria
  (3.2–3.5). The **scope/idempotency-key** (Property 4) and **correlation-id** (Property 3)
  properties are retained separately from the round-trip because they assert behavior the
  `ActionProcessor` and tenant isolation depend on directly (Correlation_Id derivation; no sentinel
  substitution; preserved key).
- The five outcome criteria (4.4, 5.1, 5.2, 5.3, 6.1) are consolidated into one **outcome-mapping
  total-function** property (Property 5), because they are arms of the same function from
  `{success, retryable, terminal-validation, reconstruction-failure, unmatched}` to
  `{stamped, not-stamped}`.
- The routing criteria (2.2, 2.3, 2.4, 6.3) are consolidated into one **routing-correctness**
  property (Property 1), because exactly-one-invocation, select-by-eventType, payload-independence,
  and no-invocation-for-unmatched are facets of the same dispatch behavior.
- Diagnostic/logging obligations (3.7, 5.4, 6.2) and the fault-injection edge cases (5.5, and the
  reconstruction-failure arm detail) are covered by example/edge-case unit tests, not properties
  (see Testing Strategy).

### Property 1: Routing is by eventType only, invokes exactly the matched handler exactly once

*For any* set of registered `InboxHandler`s with distinct `eventType`s and *for any*
`InboundMessage`, the `DispatchingHandler` invokes the handler whose declared `eventType` equals the
message `eventType` exactly once and invokes no other registered handler; when no registered handler
matches the message `eventType`, it invokes no handler at all; and for two messages that share an
`eventType` but differ arbitrarily in payload, scope, correlation id, and headers, the selected
handler is identical (selection never inspects the payload).

**Validates: Requirements 2.2, 2.3, 2.4, 6.3**

### Property 2: Reconstruction round-trip preserves the envelope

*For any* valid `EventEnvelope`, mapping it to an `InboundMessage` exactly as
`InboundRecordingService.toInboundMessage` does and then reconstructing it with
`EnvelopeReconstructor` produces an `EventEnvelope` equal to the original field-by-field (`eventId`,
`eventType`, `schemaVersion`, `occurredAt`, `organizationId`, `facilityId`, `entityType`, `entityId`,
`actorType`, `actorId`, `correlationId`, `causationId`, `payload`).

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

### Property 3: Correlation id is carried through so the processor derives it consistently

*For any* `InboundMessage`, the reconstructed `EventEnvelope` has `correlationId` equal to the
message `correlationId` and `eventId` equal to the message `messageId`; consequently the
`ActionProcessor`'s derived Correlation_Id equals the message `correlationId` when it is non-null and
equals the message `messageId` (the `eventId`) when the `correlationId` is null.

**Validates: Requirements 7.1, 7.2**

### Property 4: Scope and the idempotency key are preserved without substitution

*For any* `InboundMessage`, the reconstructed `EventEnvelope` sets `organizationId` to the scope value
bound to `organization_id`, sets `facilityId` to the scope value bound to `facility_id` (null when
absent, with no sentinel or default substituted), and sets `eventId` to the message `messageId`
without generating any new identifier.

**Validates: Requirements 8.1, 8.2, 8.3**

### Property 5: Outcome mapping is a total function onto the processed_at stamp

*For any* admitted `InboundMessage`, the `DispatchingHandler`'s outcome maps onto the `processOnce`
`processed_at` stamp as a total function over the five outcomes: a routed handler that **completes
successfully** and a routed handler that raises **`ActionValidationException`** (terminal) and a
**reconstruction failure** and an **unmatched `eventType`** all cause the `DispatchingHandler` to
return normally so `processed_at` is stamped and the message is not redelivered, while a routed
handler that raises **`ActionRetryableException`** (transient) causes the `DispatchingHandler` to
propagate out of `processOnce` so `processed_at` is not stamped and the message remains eligible for
redelivery.

**Validates: Requirements 4.4, 5.1, 5.2, 5.3, 6.1**

### Property 6: Idempotency and dedupe are preserved through dispatch

*For any* routable `InboundMessage` delivered `N >= 1` times with the same `messageId` to the real
`IdempotentInbox` through the `DispatchingHandler` with a matched, succeeding handler, the routed
handler is invoked exactly once across all `N` deliveries and exactly one inbox row exists for that
`messageId` with a non-null `processed_at` — the dispatcher introduces no new identifier and does not
alter the inbox's insert-on-receive, `message_id` keying, or stamp-on-success behavior.

**Validates: Requirements 5.6, 8.4**

### Property 7: Duplicate registration fails startup naming the conflicting eventType

*For any* list of `InboxHandler`s that contains at least two handlers declaring the same `eventType`,
constructing the `HandlerRegistry` fails with a `DuplicateHandlerRegistrationException` whose message
contains the conflicting `eventType` value; and *for any* list of handlers whose `eventType`s are all
distinct, construction succeeds and every handler is retrievable by its `eventType`.

**Validates: Requirements 1.4, 1.6**

---

## Error Handling

The design distinguishes two failure dispositions, driven entirely by whether the
`DispatchingHandler` returns normally or throws out of `processOnce`.

| Condition | Source | Disposition | `processed_at` | Diagnostic |
|---|---|---|---|---|
| Handler success (`ActionResult`) | `ActionProcessor` | normal return | stamped | none (happy path) |
| Transient failure | `ActionRetryableException` | **re-thrown** out of `processOnce` | **not stamped** | exception propagates to starter; message redelivered |
| Terminal rejection (poison) | `ActionValidationException` | caught, recorded, normal return | stamped | `log.warn` reason (Req 5.4); recording is best-effort (Req 5.5) |
| Reconstruction failure | `ReconstructionException` | caught, recorded, normal return | stamped | `log.warn` field + reason (Req 3.6, 3.7) |
| Unmatched `eventType` | registry miss | recorded, normal return | stamped | `log.warn` eventType (Req 6.1, 6.2) |
| Duplicate handler registration | startup | `DuplicateHandlerRegistrationException` | n/a (never boots) | error names the conflicting `eventType` (Req 1.6) |

Key error-handling rules:

- **Only `ActionRetryableException` escapes `processOnce`.** Every terminal condition (validation
  rejection, reconstruction failure, unmatched type) is consumed as a processed no-op so a poison or
  unroutable message cannot loop forever (Decision D1 for unmatched; Decision D2 for terminal
  rejection).
- **Diagnostics never cause redelivery (Req 5.5).** The terminal-rejection recording is wrapped in
  its own `try/catch`; a logging failure is swallowed so it cannot turn a terminal outcome into a
  redelivery.
- **No dead-letter persistence (Decision D2).** "Recording" is a `log.warn` obligation only — no
  entity, table, migration, or row is introduced.
- **An unexpected exception type** (a handler throwing something that is neither
  `ActionRetryableException` nor `ActionValidationException`) is **not** swallowed: it propagates out
  of `processOnce` (so `processed_at` is not stamped) and the message is redelivered. Treating an
  unknown fault as retryable is the safe default — the alternative (silently consuming an
  unclassified error) would risk data loss. The one shipped binding (`SendCommunicationBinding`) only
  ever surfaces the two classified exceptions, so this is a defensive backstop for future handlers.
- **Startup fail-fast.** A duplicate `eventType` aborts context refresh, so the service never runs
  with an ambiguous registry (Req 1.6).

---

## Testing Strategy

### Dual approach

- **Property tests (jqwik):** verify the universal properties above across generated inputs.
- **Unit/example tests:** verify the concrete binding (`SendCommunicationBinding.eventType()`), the
  diagnostic-recording obligations (log capture), and the fault-injection edge cases.
- **Integration slice:** verify the wiring — that `InboundRecordingService` drives the real
  `IdempotentInbox` with the `DispatchingHandler`.

PBT **is** appropriate here: the registry, router, and reconstructor are pure / near-pure logic whose
behavior varies meaningfully with input (handler sets, eventTypes, envelope fields, malformed
headers, outcome classes), and the module already establishes the pattern with jqwik `@DataJpaTest`
inbox slices (`InboxExactlyOncePropertyTest`, `InboxHandlerFailureRetryPropertyTest`).

### Property-based testing library and conventions

- **Library:** `net.jqwik` with `net.jqwik.spring.JqwikSpringSupport`, consistent with the module's
  existing property tests. Do **not** hand-roll randomization.
- **Iterations:** every property test runs a minimum of `@Property(tries = 100)`.
- **Tag format:** each property test references its design property in a class/method Javadoc tag:
  `Feature: inbox-action-dispatch, Property {number}: {property text}`.
- **One property → one property test.**

### Test doubles (fakes/stubs), matching the module pattern

- **Fake `InboundMessage`:** built directly via the record constructor (as the existing inbox tests
  do), with jqwik-generated `messageId`, `eventType`, `payload`, `scope` (`organization_id` always
  present, `facility_id` optionally null), `correlationId` (optionally null), and `headers`.
- **Stub `InboxHandler`s:** small counting stubs that record whether/how often `handle(...)` was
  called and the envelope they received, each declaring a generated distinct `eventType`. Variants
  that throw `ActionValidationException` / `ActionRetryableException` / succeed drive the
  outcome-mapping property.
- **Counting / stub `ActionProcessor`:** a fake implementing `ActionProcessor` that counts
  `process(...)` calls, captures the envelope, and is configurable to return `ActionResult` or throw
  either exception — used for the `SendCommunicationBinding` delegation example and reusable in the
  outcome property.
- **Asserting stamp vs redelivery:** reuse the module's real-inbox slice pattern
  (`@DataJpaTest` + `@ImportAutoConfiguration(MessagingAutoConfiguration.class)` + H2-PostgreSQL with
  the `JSONB` domain alias + `@Transactional(NOT_SUPPORTED)` so each `processOnce` is its own
  committed unit). Assert `processed_at` via `InboxRepository.findByMessageId(id).getProcessedAt()`
  (non-null = stamped/consumed, null = redelivery-eligible), exactly as
  `InboxHandlerFailureRetryPropertyTest` does. The pure properties (routing, reconstruction, registry)
  need no database and run as plain jqwik `@Property` tests.

### Property → test mapping

| Property | Test kind | Key assertions |
|---|---|---|
| 1 — Routing by eventType only | Pure jqwik | Generate distinct-eventType counting stubs + a message; matched stub count == 1, all others == 0; unmatched message → all == 0; same eventType + varied payloads → identical selection |
| 2 — Reconstruction round-trip | Pure jqwik | `reconstruct(toInboundMessage(env)) == env` field-by-field, over generated envelopes (facility present/absent, actorId/causationId present/absent) |
| 3 — Correlation id derivation | Pure jqwik | `reconstructed.correlationId == message.correlationId`; derived id == correlationId (non-null) else messageId |
| 4 — Scope + key preservation | Pure jqwik | `organizationId == scope[organization_id]`; `facilityId == scope[facility_id]` (null preserved); `eventId == messageId`; no sentinel |
| 5 — Outcome mapping total function | Real-inbox `@DataJpaTest` slice | For each of the five outcomes: success/terminal/reconstruction-fail/unmatched → `processed_at` non-null and `ActionProcessor` call count matches expectation; retryable → dispatcher throws and `processed_at` null |
| 6 — Idempotency preserved | Real-inbox `@DataJpaTest` slice | Deliver same `messageId` N≥1 times with a matched succeeding handler → handler count == 1, exactly one stamped row |
| 7 — Duplicate registration fails startup | Pure jqwik | Lists with an injected duplicate → `HandlerRegistry` ctor throws `DuplicateHandlerRegistrationException` containing the eventType; all-distinct lists → construct and all retrievable |

### Example and edge-case unit tests (not properties)

- **4.1 / 4.2 (example):** `new SendCommunicationBinding(stubProcessor).eventType()` equals
  `"SendCommunication"`; dispatching a `SendCommunication` message invokes the stub `ActionProcessor`
  exactly once with the reconstructed envelope.
- **3.7 / 5.4 / 6.2 (example, log capture):** a reconstruction failure records the offending field;
  a terminal rejection records the rejection reason; an unmatched message records the eventType.
- **5.5 (edge case, fault injection):** with a recorder/logger that throws during terminal-rejection
  recording, the `DispatchingHandler` still returns normally (does not propagate) and `processed_at`
  is stamped.
- **3.6 (edge case):** `EnvelopeReconstructor` raises `ReconstructionException` naming the field for
  each required header/scope corruption (missing `organization_id`, non-integer `schema_version`,
  non-UUID `entity_id`, bad `actor_type`, unparseable `occurred_at`), and the `DispatchingHandler`
  consumes it as a stamped no-op invoking no handler.
- **2.1 (integration/context):** `InboundRecordingService` is wired with the `DispatchingHandler` as
  the `Consumer<InboundMessage>` seam (context test asserting the primary bean type).
- **1.6 (context/smoke):** an application context with two handlers declaring the same `eventType`
  fails to refresh (complements the pure Property 7 logic test).
