# Implementation Plan: Inbox Action-Dispatch

## Overview

This plan converts the `inbox-action-dispatch` design into incremental, test-driven coding tasks for
the `storage-manager-communications` Maven Spring Boot service. It delivers the generic inbox
routing seam — step 5 of the lead event-driven communication flow deferred by `lead-communication-action`
as its External Assumption A2.

All new types live under a new package `com.storagemanager.communications.inbox`:
`InboxHandler` (interface), `HandlerRegistry` + `DuplicateHandlerRegistrationException`,
`EnvelopeReconstructor` + `ReconstructionException`, `DispatchingHandler`
(`@Component implements Consumer<InboundMessage>`), and `SendCommunicationBinding`
(`@Component implements InboxHandler`). They reuse the `messaging`-owned `EventEnvelope`/`InboundMessage`/
`IdempotentInbox` types and the `lead-communication-action`-owned `ActionProcessor` /
`SendCommunicationAction.EVENT_TYPE` / `ActionValidationException` / `ActionRetryableException`.

This spec introduces **no new persistent entity, table, or Flyway migration** (Decision D2). "Recording"
an unmatched event type, a reconstruction failure, or a terminal rejection is a **log/diagnostic
obligation only** — the only durable effect is the existing `inbox.processed_at` stamp owned by the
`messaging` starter.

**The one cross-spec touch** is a single additive edit to the `messaging`-owned
`InboundRecordingService`: constructor-inject the `Consumer<InboundMessage>` seam bean and pass it to
`processOnce` instead of the inline `m -> { /* no-op */ }`; the messaging base no-op `@Bean` (if any)
becomes `@ConditionalOnMissingBean` so this spec's `DispatchingHandler` supersedes it. This task is
flagged explicitly below as the single cross-spec file edit.

Property-based tests use jqwik (`@Property(tries = 100)`), one property test per correctness property
(Properties 1–7), each tagged `Feature: inbox-action-dispatch, Property {n}: {property text}` with its
validated requirements. Pure properties (routing, reconstruction round-trip, correlation-id,
scope/key preservation, duplicate-registration) run as plain jqwik property tests. The outcome-mapping
(Property 5) and idempotency-preserved (Property 6) properties run against the module's real-inbox
`@DataJpaTest` H2-PostgreSQL slice, asserting `processed_at` via
`InboxRepository.findByMessageId(...).getProcessedAt()`, mirroring `InboxHandlerFailureRetryPropertyTest`
and `InboxExactlyOncePropertyTest`.

## Tasks

- [x] 1. Establish the handler-registration abstraction and registry
  - [x] 1.1 Create the `InboxHandler` interface and reconstruction/registration exception types
    - Create package `com.storagemanager.communications.inbox`
    - Add `InboxHandler` interface exposing `String eventType()` (exactly one Event_Type value) and
      `void handle(EventEnvelope envelope)` (the operation the Dispatching_Handler invokes)
    - Add `DuplicateHandlerRegistrationException extends RuntimeException` whose message names the
      conflicting `eventType` value and both colliding handler classes
    - Add `ReconstructionException extends RuntimeException` carrying the offending `field` name and a
      reason, with a `field()` accessor and both `(field, reason)` and `(field, reason, cause)` constructors
    - _Requirements: 1.1, 1.2, 1.3, 1.6, 3.6, 3.7_
    - _Design: Components and Interfaces (InboxHandler, DuplicateHandlerRegistrationException, ReconstructionException)_

  - [x] 1.2 Implement `HandlerRegistry`
    - Add `HandlerRegistry` `@Component` whose constructor injects `List<InboxHandler>` and builds an
      immutable `Map<String, InboxHandler>` keyed by `eventType()`
    - On a duplicate `eventType`, throw `DuplicateHandlerRegistrationException` from the constructor so
      context refresh aborts (application never boots with an ambiguous registry)
    - Expose `Optional<InboxHandler> lookup(String eventType)` returning empty for an unmatched type
    - _Requirements: 1.4, 1.5, 1.6_
    - _Design: HandlerRegistry (Why the constructor throws)_

  - [x] 1.3 Write property test for duplicate registration failing startup
    - **Property 7: Duplicate registration fails startup naming the conflicting eventType**
    - Generators: handler lists containing at least two handlers sharing an `eventType` (ctor throws
      `DuplicateHandlerRegistrationException` whose message contains the conflicting value); all-distinct
      lists (ctor succeeds and every handler is retrievable by its `eventType` via `lookup`)
    - **Validates: Requirements 1.4, 1.6**
    - _Design: Correctness Properties (Property 7); Testing Strategy (pure jqwik)_

  - [x] 1.4 Write context/smoke test for duplicate-eventType context-refresh failure
    - Spin up an application context with two `InboxHandler` beans declaring the same `eventType` and
      assert context refresh fails (complements the pure Property 7 unit logic with real Spring wiring)
    - _Requirements: 1.6_
    - _Design: Testing Strategy (1.6 context/smoke)_

- [x] 2. Implement EventEnvelope reconstruction
  - [x] 2.1 Implement `EnvelopeReconstructor`
    - Add `EnvelopeReconstructor` `@Component` with `EventEnvelope reconstruct(InboundMessage m)`, the
      exact inverse of `InboundRecordingService.toInboundMessage`
    - Map `messageId -> eventId` (preserved key, never regenerated); `eventType`, `payload`,
      `correlationId` pass through; scope `organization_id -> organizationId` (required),
      `facility_id -> facilityId` (null preserved, no sentinel)
    - Rebuild header fields: `schema_version` (int), `occurred_at` (Instant), `entity_type` (non-blank),
      `entity_id` (UUID), `actor_type` (ActorType), and nullable `actor_id`, `causation_id`
    - A required field that is absent or unparseable throws `ReconstructionException(field, reason)`;
      an optional field that is present-but-unparseable also throws (naming the field)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 7.1, 7.2, 8.1, 8.2, 8.3_
    - _Design: EnvelopeReconstructor; Reconstruction field mapping table_

  - [x] 2.2 Write property test for reconstruction round-trip
    - **Property 2: Reconstruction round-trip preserves the envelope**
    - For any valid `EventEnvelope`, map via the same logic as `InboundRecordingService.toInboundMessage`
      then `reconstruct(...)`; assert field-by-field equality across all 13 fields; generate facility
      present/absent and actorId/causationId present/absent
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**
    - _Design: Correctness Properties (Property 2); Testing Strategy (pure jqwik)_

  - [x] 2.3 Write property test for correlation id derivation
    - **Property 3: Correlation id is carried through so the processor derives it consistently**
    - Generate `InboundMessage`s with non-null and null `correlationId`; assert
      `reconstructed.correlationId == message.correlationId` and `reconstructed.eventId == message.messageId`;
      derived Correlation_Id equals `correlationId` when non-null else `messageId`
    - **Validates: Requirements 7.1, 7.2**
    - _Design: Correctness Properties (Property 3); Testing Strategy (pure jqwik)_

  - [x] 2.4 Write property test for scope and idempotency-key preservation
    - **Property 4: Scope and the idempotency key are preserved without substitution**
    - Assert `organizationId == scope[organization_id]`, `facilityId == scope[facility_id]` (null
      preserved, no sentinel), and `eventId == messageId` with no new identifier generated; generate
      facility present/absent
    - **Validates: Requirements 8.1, 8.2, 8.3**
    - _Design: Correctness Properties (Property 4); Testing Strategy (pure jqwik)_

  - [x] 2.5 Write edge-case tests for reconstruction field corruption
    - For each required header/scope corruption — missing `organization_id`, non-integer
      `schema_version`, unparseable `occurred_at`, blank `entity_type`, non-UUID `entity_id`, bad
      `actor_type`, and a present-but-bad optional `actor_id`/`causation_id` — assert
      `EnvelopeReconstructor` raises `ReconstructionException` naming the offending field
    - _Requirements: 3.6, 3.7_
    - _Design: Testing Strategy (3.6 edge case); Error Handling_

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement the dispatching handler (routing + outcome mapping)
  - [x] 4.1 Implement `DispatchingHandler` routing and reconstruction wiring
    - Add `DispatchingHandler` `@Component implements Consumer<InboundMessage>`, constructor-injecting
      `HandlerRegistry` and `EnvelopeReconstructor`
    - In `accept(...)`, look up the handler by `message.eventType()` only (never inspect payload);
      unmatched → record (log) the unmatched eventType and return normally, invoking no handler
    - For a matched handler, reconstruct the `EventEnvelope`; catch `ReconstructionException`, record
      (log) the field + reason, and return normally without invoking the handler
    - Invoke the matched handler exactly once with the reconstructed envelope
    - _Requirements: 2.2, 2.3, 2.4, 3.1, 3.6, 3.7, 6.1, 6.2, 6.3_
    - _Design: DispatchingHandler; Sequence diagram_

  - [x] 4.2 Implement the outcome-mapping onto processOnce
    - On a matched handler: success (normal return) → return normally so `processed_at` is stamped
    - `ActionRetryableException` → re-throw out of `processOnce` so `processed_at` is NOT stamped and
      the message is redelivered (the ONLY exception that escapes)
    - `ActionValidationException` → record (log) the terminal rejection reason, then return normally so
      `processed_at` is stamped; wrap the recording in its own try/catch so a recording failure cannot
      propagate and cause redelivery (Req 5.5)
    - Preserve the Idempotent_Inbox uniqueness/dedupe/stamp rule unchanged — alter no `message_id`
      keying, insert-on-receive, or stamping behavior
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_
    - _Design: DispatchingHandler outcome mapping; Error Handling table_

  - [x] 4.3 Write example tests for diagnostic-recording obligations (log capture)
    - Assert a reconstruction failure records the offending field (3.7), a terminal
      `ActionValidationException` records the rejection reason (5.4), and an unmatched message records
      the eventType (6.2), via log capture
    - _Requirements: 3.7, 5.4, 6.2_
    - _Design: Testing Strategy (3.7 / 5.4 / 6.2 example, log capture)_

  - [x] 4.4 Write edge-case fault-injection test for Req 5.5
    - With a recorder/logger that throws during terminal-rejection recording, assert `DispatchingHandler`
      still returns normally (does not propagate) so `processed_at` is stamped
    - _Requirements: 5.5_
    - _Design: Testing Strategy (5.5 edge case, fault injection); Error Handling_

- [x] 5. Implement the SendCommunication binding
  - [x] 5.1 Implement `SendCommunicationBinding`
    - Add `SendCommunicationBinding` `@Component implements InboxHandler`, constructor-injecting
      `ActionProcessor`
    - `eventType()` returns `SendCommunicationAction.EVENT_TYPE` (`"SendCommunication"`)
    - `handle(envelope)` delegates to `ActionProcessor.process(envelope)`; it does NOT catch or translate
      `ActionValidationException`/`ActionRetryableException` (the `DispatchingHandler` maps them centrally)
      and does NOT reimplement validation, recipient resolution, personalization, or the send
    - A normal return (an `ActionResult`) is the success signal; the returned value is intentionally unused
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
    - _Design: SendCommunicationBinding (Why the binding does not translate exceptions)_

  - [x] 5.2 Write example test for SendCommunicationBinding delegation
    - Assert `new SendCommunicationBinding(stubProcessor).eventType()` equals `"SendCommunication"`;
      with a counting stub `ActionProcessor`, assert `handle(envelope)` invokes `process(...)` exactly
      once with the passed envelope and that classified exceptions propagate uncaught
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
    - _Design: Testing Strategy (4.1 / 4.2 example)_

- [x] 6. Implement routing correctness property
  - [x] 6.1 Write property test for routing by eventType only
    - **Property 1: Routing is by eventType only, invokes exactly the matched handler exactly once**
    - Generate distinct-eventType counting stub handlers plus a message: matched stub invoked exactly
      once and all others zero; unmatched message → all zero; same eventType with varied
      payload/scope/correlationId/headers → identical selection (selection never inspects payload)
    - **Validates: Requirements 2.2, 2.3, 2.4, 6.3**
    - _Design: Correctness Properties (Property 1); Testing Strategy (pure jqwik)_

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Wire the dispatcher into the inbox seam (the one cross-spec touch)
  - [x] 8.1 Edit `InboundRecordingService` to inject and use the seam handler — CROSS-SPEC TOUCH
    - **FLAG: this is the single `messaging`-owned file edited by this spec**, directly analogous to the
      `SendService.sendInternal` touch in `lead-communication-action`; surface it for review
    - Change `InboundRecordingService` to constructor-inject a `Consumer<InboundMessage>` seam bean and
      pass it to `inbox.processOnce(message, inboxHandler)` instead of the inline `m -> { /* no-op */ }`;
      leave `toInboundMessage(...)` and the dedupe mechanics unchanged
    - If the messaging base provides a no-op `Consumer<InboundMessage>` `@Bean`, annotate it
      `@ConditionalOnMissingBean` so this spec's `DispatchingHandler` (the primary bean) supersedes it
    - _Requirements: 2.1, 5.6_
    - _Design: The cross-spec touch (flag for review); InboundRecordingService integration_

  - [x] 8.2 Write integration/context test for the seam wiring
    - Assert `InboundRecordingService` is wired with the `DispatchingHandler` as the
      `Consumer<InboundMessage>` seam (context test asserting the primary bean type / injected handler)
    - _Requirements: 2.1_
    - _Design: Testing Strategy (2.1 integration/context)_

- [x] 9. Verify outcome mapping and idempotency against the real inbox slice
  - [x] 9.1 Write property test for the outcome-mapping total function
    - **Property 5: Outcome mapping is a total function onto the processed_at stamp**
    - Use the module's real-inbox `@DataJpaTest` slice (H2-PostgreSQL with the `JSONB` domain alias,
      `@ImportAutoConfiguration(MessagingAutoConfiguration.class)`, `@Transactional(NOT_SUPPORTED)`),
      mirroring `InboxHandlerFailureRetryPropertyTest`; drive `InboundRecordingService` →
      `IdempotentInbox.processOnce` → `DispatchingHandler`
    - For success, terminal `ActionValidationException`, reconstruction failure, and unmatched eventType:
      assert `InboxRepository.findByMessageId(id).getProcessedAt()` is non-null (stamped) and the
      `ActionProcessor` call count matches expectation; for `ActionRetryableException`: assert the
      dispatcher throws and `getProcessedAt()` is null
    - **Validates: Requirements 4.4, 5.1, 5.2, 5.3, 6.1**
    - _Design: Correctness Properties (Property 5); Testing Strategy (real-inbox slice)_

  - [x] 9.2 Write property test for idempotency preserved through dispatch
    - **Property 6: Idempotency and dedupe are preserved through dispatch**
    - Real-inbox `@DataJpaTest` slice mirroring `InboxExactlyOncePropertyTest`: deliver the same
      `messageId` N≥1 times with a matched succeeding handler; assert the routed handler is invoked
      exactly once and exactly one inbox row exists with non-null `processed_at`
    - **Validates: Requirements 5.6, 8.4**
    - _Design: Correctness Properties (Property 6); Testing Strategy (real-inbox slice)_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation
  tasks are never optional.
- Each task references specific requirements and, where applicable, the design correctness property it
  implements, for traceability.
- This spec introduces **no new persistent entity, table, or Flyway migration** (Decision D2);
  "recording" is a `log.warn` obligation only — no dead-letter entity, table, or row.
- The single rule the design turns on: **only `ActionRetryableException` escapes `processOnce`.** Every
  other outcome (success, terminal validation rejection, reconstruction failure, unmatched eventType)
  returns normally so `processed_at` is stamped and the message is consumed.
- Task 8.1 is the **single cross-spec file edit** (the `messaging`-owned `InboundRecordingService`),
  flagged explicitly for review.
- Property tests use jqwik at `@Property(tries = 100)`. Pure properties (1–4, 7) run without a database;
  Properties 5 and 6 run against the real-inbox `@DataJpaTest` H2-PostgreSQL slice mirroring
  `InboxHandlerFailureRetryPropertyTest` / `InboxExactlyOncePropertyTest`, asserting `processed_at` via
  `InboxRepository.findByMessageId(...).getProcessedAt()`.
- Each property test carries a tag in the format `Feature: inbox-action-dispatch, Property {n}: {property text}`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "1.4", "2.2", "2.3", "2.4", "2.5"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2"] },
    { "id": 5, "tasks": ["4.3", "4.4", "5.1", "6.1"] },
    { "id": 6, "tasks": ["5.2", "8.1"] },
    { "id": 7, "tasks": ["8.2", "9.1", "9.2"] }
  ]
}
```
