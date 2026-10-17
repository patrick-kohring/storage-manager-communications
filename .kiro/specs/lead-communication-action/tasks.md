# Implementation Plan: Lead Communication Action

## Overview

This plan converts the `lead-communication-action` design into incremental, test-driven coding tasks for the `storage-manager-communications` Maven Spring Boot service. Each task builds on the previous ones and ends with wiring the `ActionProcessor` orchestration together through the existing `communications-api` send seam.

The feature introduces **no new persistent entity and no new migration**: it reads the existing `communication_templates` and `entity_property_mappings` tables and writes through the existing `SendService` send seam (`sent_communications`). Lead data is read through a new `LeadReadPort` interface with an in-memory test/profile stub (the live cross-repository implementation is External Assumption A3 and is out of scope).

New types live under `com.storagemanager.communications.action` and `com.storagemanager.communications.action.lead`, plus `TemplatePersonalizer` under `service` and two read-only finders added to `CommunicationTemplateRepository`.

Property-based tests use jqwik (`@Property(tries = 100)`) and the existing Mockito-backed fake-repository pattern under `src/test/java/.../property`, with a `StubLeadReadPort` and a counting send-seam stub. There is one property test per correctness property (Properties 1–11), each tagged in the format `Feature: lead-communication-action, Property {n}: {property text}`.

## Tasks

- [x] 1. Establish the action contract and port/result value types
  - [x] 1.1 Create the `SendCommunicationAction` payload record and error types
    - Create package `com.storagemanager.communications.action`
    - Add `SendCommunicationAction` record with fields `entityKind: EntityType`, `leadId: String`, `templateName: String`, `recipientType: RecipientType` (nullable), `explicitRecipient: String`, annotated `@JsonIgnoreProperties(ignoreUnknown = true)`, with constant `EVENT_TYPE = "SendCommunication"`
    - Bind `leadId` as `String` (not `UUID`) so the processor can raise a field-named rejection on invalid UUID
    - Add exception hierarchy: `ActionProcessingException` (base), `ActionValidationException` (terminal reject), `ActionRetryableException` (transient/redeliver)
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 1.6, 2.1_
    - _Design: SendCommunicationAction contract; Error Handling_

  - [x] 1.2 Create the `ResolvedRecipient` and `ActionResult` value types
    - Add `ResolvedRecipient` record (`channel: MessageType`, `contactValue: String`, `auditRecipientType: RecipientType` nullable)
    - Add `ActionResult` representing a successfully processed action (carrying at minimum a processed indicator)
    - _Requirements: 7.4, 5.4_
    - _Design: Recipient + channel resolution (ResolvedRecipient); Components_

- [x] 2. Implement the Lead read seam
  - [x] 2.1 Create `LeadReadPort`, `LeadView`, and `LeadNotFoundException`
    - Create package `com.storagemanager.communications.action.lead`
    - Add `LeadView` record (`organizationId: UUID`, `facilityId: UUID` nullable, `email: String` nullable, `phone: String` nullable, `preferredMethod: MessageType`, `properties: Map<String,Object>`)
    - Add `LeadReadPort` interface with `Optional<LeadView> findLead(UUID leadId)` and a documented `LeadReadException` for transient read failures
    - _Requirements: 8.1_
    - _Design: LeadReadPort and its stub_

  - [x] 2.2 Implement `StubLeadReadPort` for test/local profile
    - In-memory map of configured `LeadView`s keyed by `leadId`, returns `Optional.empty()` for unknown ids, no calls to `storage-manager-api`
    - Register under the test/local profile so it is the only active implementation in this spec
    - _Requirements: 8.2_
    - _Design: LeadReadPort and its stub; A3_

  - [x]* 2.3 Write a smoke test confirming `StubLeadReadPort` wiring
    - Spring context test asserting the stub is wired and returns configured data under the test/local profile
    - _Requirements: 8.2_
    - _Design: Testing Strategy (Integration / smoke tests, SMOKE)_

- [x] 3. Add read-only template scope finders
  - [x] 3.1 Add facility-level and org-level finders to `CommunicationTemplateRepository`
    - Add `Optional<CommunicationTemplate> findByOrganizationIdAndFacilityIdAndTemplateName(UUID organizationId, UUID facilityId, String templateName)`
    - Add `Optional<CommunicationTemplate> findByOrganizationIdAndFacilityIdIsNullAndTemplateName(UUID organizationId, String templateName)`
    - Read-only extension only; no changes to existing template write behavior
    - _Requirements: 3.2, 3.3_
    - _Design: TemplateResolver logic (facility-first then org)_

- [x] 4. Implement template personalization (pure function)
  - [x] 4.1 Implement `TemplatePersonalizer` and `UnresolvedPlaceholderException`
    - Create `TemplatePersonalizer` in the `service` package with `personalize(String content, Map<String,String> placeholderToJsonProperty, Map<String,Object> leadProperties)`
    - Match `{{inner-name}}` via regex `\{\{\s*([a-z0-9-]+)\s*\}\}`; for each occurrence look up the inner name in `placeholderToJsonProperty`
    - No mapping for an inner name → throw `UnresolvedPlaceholderException` naming the first unmapped placeholder
    - Mapping present → read `leadProperties.get(jsonProperty)`; absent/null → empty string; otherwise its string form
    - Content with no placeholders returned unchanged; repeated inner names resolve to the identical value
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_
    - _Design: TemplatePersonalizer (service package)_

  - [x]* 4.2 Write property test for personalization totality and consistency
    - **Property 2: Personalization substitutes mapped placeholders totally and consistently**
    - Generators: placeholder-free, mapped, unmapped, repeated, and null-valued placeholder content; random `LEAD` placeholder→jsonProperty sets; lead property maps including absent/null values
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 6.6**
    - _Design: Testing Strategy; Correctness Properties (Property 2)_

- [x] 5. Add the system-actor send entry on the reused send seam
  - [x] 5.1 Add `SendService.sendInternal(SendCommunicationRequest request)`
    - Add a package-visible `sendInternal(...)` that performs the identical dispatch/persist logic as the public `send(...)` but treats the supplied `organizationId` as authoritative (system actor), skipping the interactive-user `authz` org-confinement branch
    - Share a single private core between `send(...)` and `sendInternal(...)`; keep the public HTTP `send(...)` contract unchanged (additive change only)
    - _Requirements: 7.2, 7.3_
    - _Design: Dispatch via the reused send seam (caller-identity caveat)_

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement the ActionProcessor orchestration
  - [x] 7.1 Create the `ActionProcessor` interface and skeleton with envelope binding, eventType guard, correlation-id derivation, and payload parse
    - Add `ActionProcessor` interface with `ActionResult process(EventEnvelope envelope)`
    - Implement envelope `eventType == SendCommunicationAction.EVENT_TYPE` guard
    - Derive correlation id: `envelope.correlationId()` when non-null, else `envelope.eventId()`
    - Parse payload via `objectMapper.convertValue(envelope.payload(), SendCommunicationAction.class)`; wrap binding failures as a malformed-payload `ActionValidationException`
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
    - _Design: ActionProcessor responsibilities 1–3_

  - [x]* 7.2 Write property test for correlation id derivation
    - **Property 11: Correlation id derivation**
    - Generators: envelopes with present vs null `correlationId`
    - **Validates: Requirements 2.2**
    - _Design: Correctness Properties (Property 11)_

  - [x]* 7.3 Write property test for structurally invalid action rejection
    - **Property 6: Structurally invalid actions are rejected**
    - Generators: missing `entityKind`/`leadId`/`templateName`; non-`LEAD` entityKind; non-UUID `leadId`; unparseable payload maps
    - **Validates: Requirements 1.2, 1.7, 1.8, 2.3**
    - _Design: Correctness Properties (Property 6)_

  - [x] 7.4 Implement action validation and single-recipient selection
    - Required fields present (`entityKind`, `leadId` valid UUID, non-blank `templateName`); `entityKind == LEAD` else "unsupported entity kind"
    - Single-recipient xor: exactly one of (`recipientType == LEAD`) or (`explicitRecipient` present); both → reject "single recipient selection required"; neither → reject "explicit recipient required"
    - _Requirements: 1.2, 1.7, 1.8, 1.9, 5.3, 9.3_
    - _Design: ActionProcessor responsibility 4; Recipient-type representation (D2) in code_

  - [x]* 7.5 Write property test for single-recipient selection
    - **Property 5: An action selects exactly one recipient**
    - Generators: both recipient selections, neither, and junk `explicitRecipient` literals
    - **Validates: Requirements 1.9, 5.3, 5.5, 9.3**
    - _Design: Correctness Properties (Property 5)_

  - [x] 7.6 Implement lead read and template resolution
    - Call `leadReadPort.findLead(leadId)`; empty → "lead not found" `ActionValidationException` (8.3); thrown → `ActionRetryableException` (8.4)
    - Obtain lead org id and facility id from the `LeadView`
    - Resolve template facility-first then org via the new finders; no match → "template not found" rejection
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 8.3, 8.4_
    - _Design: ActionProcessor responsibilities 5–6; TemplateResolver logic_

  - [x]* 7.7 Write property test for template resolution scope preference
    - **Property 4: Template resolution prefers facility scope and rejects when neither scope matches**
    - Generators: leads with/without facility; random org/facility/name templates exercising facility-first-then-org and no-match
    - **Validates: Requirements 3.2, 3.3, 3.4, 3.5**
    - _Design: Correctness Properties (Property 4)_

  - [x]* 7.8 Write property test for the missing-lead rejection
    - **Property 8: A missing lead is rejected**
    - Generators: actions whose port lookup reports no lead for the id
    - **Validates: Requirements 8.3**
    - _Design: Correctness Properties (Property 8)_

  - [x] 7.9 Implement recipient + channel resolution and channel content selection
    - LEAD path: `channel = leadView.preferredMethod()`; contact = email for `EMAIL`, phone for `SMS`; absent/blank contact → reject "lead lacks a contact for the preferred channel" (4.7); `auditRecipientType = LEAD`
    - Explicit path: classify literal (email → `EMAIL`+email; phone → `SMS`+phone; neither → reject naming `explicitRecipient`, 5.5); `auditRecipientType = null`
    - Use a shared `ContactFormat` helper whose email/phone predicates match `SendService`'s existing patterns so the processor's classification never conflicts with `send` validation
    - Channel content selection: `EMAIL` → `emailContent`, `SMS` → `smsContent`; missing content → reject "template lacks content for the channel" (4.5/4.6)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 5.1, 5.2, 5.5_
    - _Design: ActionProcessor responsibilities 7–8; Recipient + channel resolution_

  - [x]* 7.10 Write property test for recipient and channel resolution
    - **Property 3: Recipient and channel resolution yields exactly one target**
    - Generators: LEAD path (both preferred methods, present/absent contacts); explicit path (email-shaped and phone-shaped literals)
    - **Validates: Requirements 4.1, 4.2, 5.1, 5.2**
    - _Design: Correctness Properties (Property 3)_

  - [x]* 7.11 Write property test for missing channel content / preferred-channel contact
    - **Property 7: Missing channel content or preferred-channel contact is rejected**
    - Generators: templates missing `emailContent`/`smsContent`; LEAD-path leads missing the preferred-channel contact
    - **Validates: Requirements 4.5, 4.6, 4.7**
    - _Design: Correctness Properties (Property 7)_

  - [x] 7.12 Wire personalization and the single send-seam dispatch
    - Build `placeholderToJsonProperty` from `EntityPropertyMappingRepository` rows where `entityType == LEAD`; call `TemplatePersonalizer.personalize(...)`; unmapped placeholder → validation rejection naming the placeholder (6.3)
    - Build exactly one `SendCommunicationRequest` and call `SendService.sendInternal(...)` exactly once with: `messageType = channel`, single matching contact (other null), `messageContent = personalized`, `facilityId`/`organizationId` from the lead, `tenantId = leadId`, `recipientType = auditRecipientType`, `correlationId = derived`
    - Send raised → `ActionRetryableException` (7.5); success → return `ActionResult.processed` (7.4)
    - Ensure every validation branch returns/throws before the single send call site (zero-send invariant)
    - _Requirements: 6.3, 7.1, 7.2, 7.4, 7.5_
    - _Design: ActionProcessor responsibilities 9–10; Dispatch via the reused send seam_

  - [x]* 7.13 Write property test for the successful send
    - **Property 1: A successful action produces exactly one correctly-mapped send**
    - Drive against fake template/mapping repos, `StubLeadReadPort`, and a counting send-seam stub; assert exactly-one send and full field mapping (channel, contact, content, facility/org, tenant=leadId, recipientType, correlation id) and processed outcome
    - **Validates: Requirements 2.4, 4.3, 4.4, 5.1, 5.2, 5.4, 7.1, 7.2, 7.4**
    - _Design: Correctness Properties (Property 1)_

  - [x]* 7.14 Write property test for retryable transient failures
    - **Property 9: Transient failures are surfaced as retryable**
    - Generators: throwing `SendService`/provider and throwing `LeadReadPort`
    - **Validates: Requirements 7.5, 8.4, 9.2**
    - _Design: Correctness Properties (Property 9)_

  - [x]* 7.15 Write property test for the zero-send-on-rejection invariant
    - **Property 10: Any validation rejection invokes the send seam zero times**
    - Use the counting send-seam stub; assert zero invocations across all validation-rejection generators (Req 1,2,3,4,5,6,8)
    - **Validates: Requirements 9.1**
    - _Design: Correctness Properties (Property 10); Error Handling (zero-send invariant)_

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Example/edge and integration tests
  - [x]* 9.1 Write example/edge-case unit tests for structural binding and routing facts
    - Field binding for `entityKind`/`leadId`/`templateName` (1.1, 1.3, 1.4); `recipientType` LEAD-or-absent binding (1.5, 1.6); `eventType == SendCommunication` guard (2.1); lead org/facility obtained via the port (3.1); `LeadReadPort` interface shape (8.1)
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 1.6, 2.1, 3.1, 8.1_
    - _Design: Testing Strategy (Example / edge-case unit tests)_

  - [x]* 9.2 Write integration test for reused provider success through the processor
    - Confirm the reused `communications-api` stub provider reports success and transmits nothing when driven through the processor (thin wiring check)
    - _Requirements: 7.3_
    - _Design: Testing Strategy (Integration / smoke tests, INTEGRATION)_

  - [x]* 9.3 Write end-to-end envelope→send integration test
    - Feed a well-formed `SendCommunication` `EventEnvelope` through `ActionProcessor.process(...)` against the real `SendService` + fake repositories; assert exactly one `SentCommunication` persisted with the expected fields
    - _Requirements: 7.1, 7.2, 7.4_
    - _Design: Testing Strategy (Integration / smoke tests, end-to-end example)_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements and, where applicable, the design correctness property it implements, for traceability.
- This spec introduces no new persistent entity and no new migration: it reads existing tables and writes via the existing `SendService` send seam.
- Property tests use jqwik at `@Property(tries = 100)` with the existing Mockito-backed fake-repository pattern, a `StubLeadReadPort`, and a counting send-seam stub, placed under `src/test/java/.../property`.
- Each property test carries a comment in the format `Feature: lead-communication-action, Property {n}: {property text}` and its validated requirements.
- The `sendInternal(...)` addition to `SendService` is the single additive change to a `communications-api`-owned type; it preserves the existing public `send(...)` contract.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1", "5.1"] },
    { "id": 1, "tasks": ["1.2", "2.2", "4.1"] },
    { "id": 2, "tasks": ["2.3", "4.2", "7.1"] },
    { "id": 3, "tasks": ["7.2", "7.3", "7.4"] },
    { "id": 4, "tasks": ["7.5", "7.6"] },
    { "id": 5, "tasks": ["7.7", "7.8", "7.9"] },
    { "id": 6, "tasks": ["7.10", "7.11", "7.12"] },
    { "id": 7, "tasks": ["7.13", "7.14", "7.15", "9.1"] },
    { "id": 8, "tasks": ["9.2", "9.3"] }
  ]
}
```
