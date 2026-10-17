# Requirements Document

## Introduction

This feature adds the **inbox action-dispatch mechanism** to the `storage-manager-communications` service: the generic routing layer that connects an admitted inbound message to the handler that interprets it. It is **step 5** of the lead event-driven communication flow — the piece the `lead-communication-action` spec explicitly deferred as its External Assumption A2 ("the concrete wiring of the Action_Processor as the inbox handler for `eventType = SendCommunication`").

The deliberate design choice here is that the mechanism is **generic and reusable**, not lead-specific. Rather than hard-wiring the one existing `ActionProcessor` into the inbox, this spec defines a small registry-and-router seam: each inbound handler registers by the single EventEnvelope `eventType` value it handles, and a Dispatching_Handler selects the registered handler matching an admitted message's `eventType` and invokes it through the starter's `processOnce` (exactly-once / idempotent) semantics. Future entity actions (tenant, lease, unit, and so on) register additional handlers without modifying the router.

This spec owns exactly one concrete binding: `eventType == "SendCommunication"` routes to the existing `ActionProcessor`. That processor — its validation, recipient/channel resolution, personalization, and single audited send — is owned by the `lead-communication-action` spec and is **referenced and wired here, not reimplemented**.

This spec plugs a real dispatcher into the handler seam the `messaging` spec already defined. In the messaging base, `InboundRecordingService.record(...)` invokes `IdempotentInbox.processOnce(message, handler)` with a **no-op / pass-through** `Consumer<InboundMessage>`. This spec replaces that no-op handler with the Dispatching_Handler, while preserving every idempotency, dedupe, and exactly-once guarantee the `messaging` spec owns (uniqueness on `message_id`, insert-on-receive, `processed_at` stamped only on successful handler completion, at-most-once under concurrent redelivery).

This spec builds on and reuses existing capabilities rather than redefining them:

- The **EventEnvelope** wire shape, the **Idempotent_Inbox**, **processOnce**, the **InboundMessage** read-view, `InboundRecordingService`, and the **handler seam** (the `Consumer<InboundMessage>` passed to `processOnce`, currently a no-op) are owned by the `messaging` spec. This spec supplies the real dispatching handler that plugs into that seam and defines no new inbox mechanics, no new persistence, and no new migration.
- The **Action_Processor** (`com.storagemanager.communications.action.ActionProcessor` / `DefaultActionProcessor`), its `ActionResult process(EventEnvelope)` contract, the `SendCommunicationAction.EVENT_TYPE` constant (`"SendCommunication"`), and the `ActionValidationException` (terminal) vs `ActionRetryableException` (transient/redeliver) split are owned by the `lead-communication-action` spec. This spec routes a matching message to the Action_Processor and maps its outcome onto the inbox; it does not reimplement the send behavior.

Because the starter's handler seam supplies the handler an `InboundMessage` read-view (idempotency key, `eventType`, payload, scope, `correlationId`, headers) rather than the original 13-field EventEnvelope, this spec also owns the **reconstruction** of the EventEnvelope the Action_Processor requires from that read-view, so the routed message carries the correlation id, scope, and payload the processor expects.

## Glossary

- **Communications_Service**: The `storage-manager-communications` Spring Boot application that hosts the generic inbox and, through this spec, routes admitted inbound messages to registered handlers.
- **EventEnvelope**: The canonical 13-field wire message owned by the `messaging` spec (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `organizationId`, `facilityId`, `entityType`, `entityId`, `actorType`, `actorId`, `correlationId`, `causationId`, `payload`). This spec reads its `eventType` for routing and reconstructs an EventEnvelope for the Action_Processor; it does not redefine the envelope.
- **Idempotent_Inbox**: The starter-provided bean exposing `processOnce(InboundMessage, Consumer<InboundMessage>)` semantics, owned by the `messaging` spec. It deduplicates on `message_id` and stamps `processed_at` only on successful handler completion.
- **processOnce**: The `Idempotent_Inbox` operation that inserts an inbox row on first receipt, invokes the supplied handler, and stamps `processed_at` when the handler completes successfully; a handler that raises leaves `processed_at` unstamped and the row eligible for redelivery. Owned by the `messaging` spec.
- **InboundMessage**: The starter read-view passed to the handler by `processOnce`, carrying the idempotency key (`messageId`), `eventType`, `payload`, scope, `correlationId`, and headers. Owned by the `messaging` spec / starter.
- **Handler_Seam**: The `Consumer<InboundMessage>` argument `InboundRecordingService.record(...)` passes to `processOnce`. In the messaging base this is a no-op; this spec supplies the Dispatching_Handler as the seam implementation.
- **InboundRecordingService**: The `messaging`-owned service that maps a validated EventEnvelope onto an `InboundMessage` and calls `processOnce` with the Handler_Seam. This spec changes which handler is passed; it does not change the mapping or the dedupe mechanics.
- **Dispatching_Handler**: The component this spec introduces that plugs into the Handler_Seam. For an admitted `InboundMessage`, it selects the registered Inbox_Handler matching the message `eventType` and invokes that handler, running inside `processOnce`.
- **Inbox_Handler**: The handler-registration abstraction this spec defines — an interface a handler implements, declaring the single `eventType` value it handles and exposing an operation the Dispatching_Handler invokes for a routed message. New entity actions register an Inbox_Handler without modifying the Dispatching_Handler.
- **Handler_Registry**: The collection of registered Inbox_Handlers, keyed by the single `eventType` value each declares, that the Dispatching_Handler consults to select a handler.
- **Event_Type**: The EventEnvelope `eventType` string value used as the routing key for handler selection.
- **Action_Processor**: The `lead-communication-action`-owned component exposing `ActionResult process(EventEnvelope)`, which interprets a `SendCommunication` action and performs exactly one audited send. Referenced and wired by this spec, not reimplemented.
- **Send_Communication_Binding**: The single concrete registration this spec provides: an Inbox_Handler declaring `eventType == "SendCommunication"` (the `SendCommunicationAction.EVENT_TYPE` constant) that delegates to the Action_Processor.
- **ActionResult**: The `lead-communication-action`-owned value returned by `Action_Processor.process(...)` indicating the action was successfully processed.
- **ActionValidationException**: The `lead-communication-action`-owned terminal exception signalling a deterministic defect in a message; a replay would fail identically, so the originating message must not be redelivered forever.
- **ActionRetryableException**: The `lead-communication-action`-owned transient exception signalling a recoverable failure; the originating message remains eligible for redelivery.
- **Unmatched_Message**: An admitted `InboundMessage` whose `eventType` has no registered Inbox_Handler in the Handler_Registry.
- **Correlation_Id**: The identifier tracing a routed message, taken from the `InboundMessage` `correlationId` when non-null and the `messageId` (the EventEnvelope `eventId`) when null, carried onto the reconstructed EventEnvelope.

## Requirements

### Requirement 1: Handler registration abstraction

**User Story:** As a Communications_Service developer, I want a handler-registration abstraction keyed on event type, so that new entity actions register an inbound handler without modifying the routing component.

#### Acceptance Criteria

1. THE Communications_Service SHALL define an Inbox_Handler interface that an inbound handler implements to be eligible for registration.
2. THE Inbox_Handler interface SHALL expose exactly one Event_Type value that the implementing handler handles.
3. THE Inbox_Handler interface SHALL expose an operation that the Dispatching_Handler invokes with the routed message for the implementing handler to process.
4. THE Communications_Service SHALL assemble a Handler_Registry from the set of registered Inbox_Handler implementations, keyed by the single Event_Type value each Inbox_Handler declares.
5. WHEN a new Inbox_Handler implementation is registered for an Event_Type that has no existing registration, THE Communications_Service SHALL add that Inbox_Handler to the Handler_Registry without a change to the Dispatching_Handler.
6. IF two registered Inbox_Handler implementations declare the same Event_Type value, THEN THE Communications_Service SHALL fail application startup with an error identifying the conflicting Event_Type value.

### Requirement 2: Dispatching handler plugged into the inbox seam

**User Story:** As a Communications_Service developer, I want a dispatching handler plugged into the existing inbox handler seam, so that an admitted message is routed to its registered handler through the starter's exactly-once semantics instead of the no-op pass-through.

#### Acceptance Criteria

1. THE Communications_Service SHALL supply the Dispatching_Handler as the `Consumer<InboundMessage>` passed to `processOnce` by `InboundRecordingService`, replacing the messaging base no-op pass-through handler.
2. WHEN `processOnce` invokes the Dispatching_Handler for an admitted InboundMessage, THE Dispatching_Handler SHALL select the registered Inbox_Handler whose declared Event_Type equals the InboundMessage `eventType`.
3. WHEN the Dispatching_Handler selects a registered Inbox_Handler for an InboundMessage, THE Dispatching_Handler SHALL invoke that Inbox_Handler exactly once for that invocation.
4. THE Dispatching_Handler SHALL select a handler using only the InboundMessage `eventType` value and SHALL NOT inspect the InboundMessage payload to choose a handler.

### Requirement 3: EventEnvelope reconstruction for the routed handler

**User Story:** As a Communications_Service developer, I want the routed handler to receive the EventEnvelope it expects, so that the Action_Processor can derive the correlation id and read the payload without depending on the inbox read-view shape.

#### Acceptance Criteria

1. WHEN the Dispatching_Handler routes an InboundMessage to an Inbox_Handler, THE Dispatching_Handler SHALL reconstruct an EventEnvelope from the InboundMessage carrying the InboundMessage `eventType`, `payload`, scope, `correlationId`, and headers.
2. WHEN the Dispatching_Handler reconstructs an EventEnvelope, THE Dispatching_Handler SHALL set the EventEnvelope `eventId` to the InboundMessage `messageId` so that the preserved idempotency key is available to the handler.
3. WHEN the Dispatching_Handler reconstructs an EventEnvelope, THE Dispatching_Handler SHALL set the EventEnvelope `correlationId` to the InboundMessage `correlationId`.
4. WHEN the Dispatching_Handler reconstructs an EventEnvelope, THE Dispatching_Handler SHALL set the EventEnvelope `organizationId` to the scope value bound to `organization_id` and SHALL set the EventEnvelope `facilityId` to the scope value bound to `facility_id`.
5. WHEN the Dispatching_Handler reconstructs an EventEnvelope, THE Dispatching_Handler SHALL populate the EventEnvelope fields carried as headers by the InboundMessage from those header values.
6. IF the Dispatching_Handler cannot populate a required EventEnvelope field from the InboundMessage headers because a header value is absent or cannot be parsed into the field type, THEN THE Dispatching_Handler SHALL treat the InboundMessage as a terminal reconstruction failure, SHALL NOT route the message to any Inbox_Handler, and SHALL complete the `processOnce` invocation without propagating a failure so that `processed_at` is stamped and the message is not redelivered.
7. WHEN the Dispatching_Handler treats an InboundMessage as a terminal reconstruction failure, THE Dispatching_Handler SHALL record the reconstruction failure reason and the offending field so that the consumed message is diagnosable.

### Requirement 4: SendCommunication binding to the Action_Processor

**User Story:** As an operator, I want a SendCommunication message routed to the existing action processor, so that the deferred step-5 wiring performs the single audited send the lead-communication-action spec defined.

#### Acceptance Criteria

1. THE Communications_Service SHALL register a Send_Communication_Binding as an Inbox_Handler declaring the Event_Type equal to `SendCommunicationAction.EVENT_TYPE` (the value `"SendCommunication"`).
2. WHEN the Dispatching_Handler routes an InboundMessage whose `eventType` equals `"SendCommunication"`, THE Send_Communication_Binding SHALL invoke `Action_Processor.process` with the reconstructed EventEnvelope.
3. THE Send_Communication_Binding SHALL delegate send interpretation to the Action_Processor and SHALL NOT reimplement validation, recipient resolution, template personalization, or the send.
4. WHEN `Action_Processor.process` returns an ActionResult for a routed SendCommunication message, THE Send_Communication_Binding SHALL complete normally so that the Dispatching_Handler completes normally.

### Requirement 5: Handler outcome mapped onto processOnce

**User Story:** As an operator, I want each routed handler's outcome mapped onto the inbox exactly-once semantics, so that a success is processed once, a transient failure is redelivered, and a terminal rejection does not loop forever.

#### Acceptance Criteria

1. WHEN a routed Inbox_Handler completes successfully for an InboundMessage, THE Dispatching_Handler SHALL complete normally so that `processOnce` stamps `processed_at` and the message is processed exactly once.
2. IF a routed Inbox_Handler raises an ActionRetryableException for an InboundMessage, THEN THE Dispatching_Handler SHALL propagate a handler failure out of `processOnce` so that `processed_at` is not stamped and the message remains eligible for redelivery.
3. IF a routed Inbox_Handler raises an ActionValidationException for an InboundMessage, THEN THE Dispatching_Handler SHALL complete the `processOnce` invocation without propagating a failure so that `processed_at` is stamped and the terminally rejected message is not redelivered.
4. WHEN the Dispatching_Handler completes a `processOnce` invocation for a terminal ActionValidationException, THE Dispatching_Handler SHALL record the terminal rejection reason named by the ActionValidationException so that the consumed poison message is diagnosable.
5. IF recording the terminal rejection reason fails, THEN THE Dispatching_Handler SHALL still complete the `processOnce` invocation without propagating a failure so that `processed_at` is stamped and a diagnostics-recording failure does not cause the terminally rejected message to be redelivered.
6. THE Dispatching_Handler SHALL preserve the Idempotent_Inbox uniqueness, dedupe, and at-most-once guarantees owned by the `messaging` spec and SHALL NOT alter the `message_id` keying, the insert-on-receive behavior, or the `processed_at` stamping rule.

### Requirement 6: Behavior for an unmatched event type

**User Story:** As an operator, I want a predictable outcome when no handler is registered for a message's event type, so that an unknown message neither loops forever nor silently disappears without a trace.

#### Acceptance Criteria

1. WHEN `processOnce` invokes the Dispatching_Handler for an Unmatched_Message, THE Dispatching_Handler SHALL complete the `processOnce` invocation without propagating a failure so that `processed_at` is stamped and the Unmatched_Message is not redelivered.
2. WHEN the Dispatching_Handler processes an Unmatched_Message, THE Dispatching_Handler SHALL record the unmatched Event_Type value so that an unhandled message is diagnosable.
3. WHEN the Dispatching_Handler processes an Unmatched_Message, THE Dispatching_Handler SHALL invoke no registered Inbox_Handler for that message.

### Requirement 7: Correlation id derivation on reconstruction

**User Story:** As an operator, I want a routed message's correlation id derived consistently, so that a dispatched action traces to the originating event even when the inbound message carried no correlation id.

#### Acceptance Criteria

1. WHEN the InboundMessage `correlationId` is non-null, THE Dispatching_Handler SHALL set the reconstructed EventEnvelope `correlationId` to that value so that the Action_Processor derives the same Correlation_Id.
2. WHEN the InboundMessage `correlationId` is null, THE Dispatching_Handler SHALL set the reconstructed EventEnvelope `correlationId` to null and the reconstructed EventEnvelope `eventId` to the InboundMessage `messageId` so that the Action_Processor derives the Correlation_Id from the `eventId`.

### Requirement 8: Scope and idempotency preservation

**User Story:** As a platform operator, I want organization and facility scope and the single idempotency key preserved through dispatch, so that routing introduces no tenant leakage and no duplicate processing.

#### Acceptance Criteria

1. THE Dispatching_Handler SHALL carry the InboundMessage organization scope and facility scope onto the reconstructed EventEnvelope without substituting a sentinel or default value.
2. WHEN the InboundMessage carries no facility scope, THE Dispatching_Handler SHALL set the reconstructed EventEnvelope `facilityId` to null.
3. THE Dispatching_Handler SHALL use the InboundMessage `messageId` as the single preserved idempotency key on the reconstructed EventEnvelope `eventId` and SHALL NOT generate a new identifier for the routed message.
4. WHEN the same `message_id` is delivered more than once, THE Dispatching_Handler SHALL be invoked by `processOnce` for that `message_id` at most once across concurrent and sequential redeliveries, because the dedupe guarantee is owned by the Idempotent_Inbox and unaltered by this spec.

## External Assumptions (Out of Scope for storage-manager-communications)

The following are relied upon as cross-repository context but are NOT owned or changed by this spec.

- **A1 — Events-side action production:** The workflow in `storage-manager-events` that reacts to `LeadCreated` and emits the `SendCommunication` action is owned by the events service (`lead-communication-action` A1). This spec routes an admitted message and does not own the workflow that produces it.
- **A2 — Action_Processor send behavior:** The validation, recipient/channel resolution, template personalization, and single audited send performed by the Action_Processor are owned by the `lead-communication-action` spec. This spec routes a `SendCommunication` message to `Action_Processor.process` and maps its outcome; it does not reimplement any send behavior.
- **A3 — Live Lead_Read_Port implementation:** The live service-to-service read from `storage-manager-communications` to `storage-manager-api` for lead data remains owned by the `lead-communication-action` spec and a future spec. This spec does not read lead data.
- **A4 — Inbox admission, ingest posture, and dedupe mechanics:** The ingest endpoint, the service-to-service authorization posture, the EventEnvelope-to-InboundMessage mapping, the Idempotent_Inbox, `processOnce`, and the inbox/outbox migration are owned by the `messaging` spec. This spec plugs a handler into the existing seam and preserves those mechanics unchanged.
- **A5 — Real delivery provider:** Actual third-party email/SMS transmission remains stubbed, consistent with the `communications-api` and `lead-communication-action` specs. This spec does not touch delivery.

## Resolved Decisions

All previously open decisions have been resolved and folded into the settled requirements above:

- **D1 — Unmatched event-type outcome (resolved: terminal processed no-op):** An Unmatched_Message is consumed as a terminal processed no-op — `processOnce` completes without propagating a failure so `processed_at` is stamped and the message is not redelivered, and the unmatched Event_Type is recorded for diagnosis (Requirement 6).
- **D2 — Terminal-rejection consumption at the inbox (resolved: no dead-letter record):** A terminal `ActionValidationException` maps onto a stamped-`processed_at` consumption; recording the rejection reason on consumption (Requirement 5.4) is the only diagnostic obligation. No dead-letter entity, table, or record is introduced.
- **D3 — Handler-declared multiplicity (resolved: exactly one event type per handler):** Each Inbox_Handler declares exactly one Event_Type, and the Handler_Registry is keyed by that single Event_Type per handler (Requirement 1). Two handlers declaring the same single Event_Type is a duplicate registration that fails startup (Requirement 1.6).
