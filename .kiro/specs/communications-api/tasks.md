# Implementation Plan: Communications API (send-and-log)

## Overview

This plan builds the send-and-log feature in dependency order against the existing `com.storagemanager.communications` package layout, mirroring the `entity-api` / `communication-template-api` / `messaging` conventions already in the service. We start with the leaf-level enums and the `PublicIdGenerator` util, then the `SentCommunication` entity and the `V5` Flyway migration, then the repository, the delivery-provider seam and stubs, the DTOs, the `SendService`, and finally the `CommunicationsController` wiring. Property-based tests (jqwik, `@Property(tries = 100)`) are placed next to the component each property exercises, driven against a Mockito-fake `SentCommunicationRepository` and a counting stub `DeliveryProvider`, mirroring `EntityMappingRoundTripPropertyTest`. Integration/migration/security tests follow once the schema and controller exist.

The implementation language is Java (the design is fully specified in Java; no pseudocode). Error translation reuses the existing `GlobalExceptionHandler` with no new handler methods.

## Tasks

- [x] 1. Add model enums and the public-id generator (leaf dependencies)
  - [x] 1.1 Create the three `EnumType.STRING` enums in the `model` package
    - Add `MessageType { EMAIL, SMS }`, `SendStatus { DISPATCHED }`, and `RecipientType { LEAD, OWNER }` under `com.storagemanager.communications.model`, matching the `EntityType` style (persisted as literal name via `@Enumerated(EnumType.STRING)` on the entity)
    - _Requirements: 5.5, 5.9, 5.14_

  - [x] 1.2 Create `PublicIdGenerator` util mirroring storage-manager-api
    - Add `com.storagemanager.communications.util.PublicIdGenerator` that produces an 8-char `[A-Za-z0-9]` string from a `SecureRandom`, mirroring the storage-manager-api utility; expose a `generate()` method the service calls inside a bounded uniqueness-retry loop
    - _Requirements: 5.12_

  - [ ]* 1.3 Write unit tests for `PublicIdGenerator`
    - Assert generated ids are 8-char `[A-Za-z0-9]` and length ≤ 64 across many draws; smoke-test distribution/non-repetition over a batch
    - _Requirements: 5.12_

- [x] 2. Implement the `SentCommunication` entity
  - [x] 2.1 Create the `SentCommunication` JPA entity in the `model` package
    - `@Entity` mapped to `sent_communications`; `@Id @GeneratedValue(strategy = GenerationType.UUID)` id; every Requirement-5 audit field with explicit `@Column` name, nullability, and length exactly per the design's field↔column table; `messageType`/`status`/`recipientType` as `@Enumerated(EnumType.STRING)`; `status` field initializer defaulting to `SendStatus.DISPATCHED`; `publicId` `nullable=false, unique=true, length=64`
    - Add `@PrePersist`/`@PreUpdate` callbacks that set `createdAt`/`updatedAt` (`Instant`, UTC) independently of `dateSent` (`dateSent` is set by the service, not the callbacks); `createdAt` not updatable
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.9, 5.10, 5.11, 5.12, 5.13, 5.14, 5.15, 5.16, 5.17, 5.18, 5.19, 5.20, 5.21_

  - [ ]* 2.2 Write unit tests for the entity lifecycle callbacks
    - Verify `@PrePersist` sets `createdAt` and `updatedAt` and `@PreUpdate` refreshes `updatedAt` while leaving `createdAt` unchanged, independent of `dateSent`; verify `status` defaults to `DISPATCHED`
    - _Requirements: 5.9, 5.20, 5.21_

- [x] 3. Create the V5 Flyway migration and prove the mapping validates
  - [x] 3.1 Author `V5__create_sent_communications.sql`
    - Create `sent_communications` with one column per entity field, nullability matching Requirement 5 one-for-one, `id` as PRIMARY KEY, a UNIQUE constraint on `public_id`, and an index on `(facility_id, date_sent DESC)`; use `UUID`/`VARCHAR`/`TIMESTAMP WITH TIME ZONE` so it runs under PostgreSQL and H2 PostgreSQL mode, continuing the linear history after `V4`
    - _Requirements: 6.1, 6.2, 6.4, 5.8_

  - [ ]* 3.2 Write the V5 migration smoke test
    - Against H2 in PostgreSQL mode (as existing migration smoke tests do), assert the `sent_communications` table exists with the expected columns, per-column nullability, the `public_id` unique constraint, and the `(facility_id, date_sent)` index, and that `V5` is the latest migration
    - _Requirements: 6.1, 6.2, 6.4_

  - [ ]* 3.3 Write the context + `ddl-auto=validate` smoke test
    - Boot the Spring context with Flyway on H2 PostgreSQL mode and `hibernate ddl-auto=validate` to prove the `SentCommunication` mapping matches the `V5` schema and the application starts (a mapping mismatch must fail startup)
    - _Requirements: 6.3_

- [x] 4. Implement the repository with deterministic finders
  - [x] 4.1 Create `SentCommunicationRepository`
    - `JpaRepository<SentCommunication, UUID>` with `existsByPublicId`, the admin finders (`findAllByOrderByDateSentDescIdDesc`, by `facilityId`, by `messageType`, by both), and the OWNER org-confined variants (`organizationId`-leading combinations), all suffixed `OrderByDateSentDescIdDesc` for deterministic newest-first ordering with id tiebreak
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 7.5, 7.6_

  - [ ]* 4.2 Write the `@DataJpaTest` persistence round-trip integration test
    - Mirror `EntityPropertyMappingPersistenceIT`: persist a `SentCommunication` through the real repository and confirm column lengths/nullability behave as mapped and the entity round-trips
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.12, 5.13, 5.20, 5.21_

- [x] 5. Implement the delivery-provider seam and stubs
  - [x] 5.1 Define the `DeliveryProvider` interface and the `DeliveryRequest` / `DeliveryResult` records
    - In the `service` package: `DeliveryProvider` with `MessageType channel()` and `DeliveryResult dispatch(DeliveryRequest)`; `DeliveryRequest(messageType, recipientContact, messageContent, emailSubject, correlationId)` and `DeliveryResult(success, providerId, providerMessageId, failureReason)` records
    - _Requirements: 1.2, 1.3_

  - [x] 5.2 Implement `StubEmailDeliveryProvider` and `StubSmsDeliveryProvider`
    - `StubEmailDeliveryProvider.channel()==EMAIL` and `StubSmsDeliveryProvider.channel()==SMS`; both perform no external I/O and return a success `DeliveryResult` (`"stub-email"`/`"stub-sms"` provider id and a generated provider message id)
    - _Requirements: 1.3_

  - [x] 5.3 Implement `DeliveryProviderRegistry`
    - `@Component` that receives all `DeliveryProvider` beans by constructor injection and indexes them by `channel()` into an `EnumMap<MessageType, DeliveryProvider>`, exposing `providerFor(MessageType)`
    - _Requirements: 1.2_

  - [ ]* 5.4 Write unit tests for the stubs and registry
    - Verify both stubs report success and perform no I/O (1.3) and that `providerFor(EMAIL)`/`providerFor(SMS)` resolve to the matching stub
    - _Requirements: 1.3_

- [x] 6. Implement the request/response DTOs
  - [x] 6.1 Create `SendCommunicationRequest` DTO record
    - Jakarta Bean Validation record with `@NotNull facilityId`, `@NotNull messageType`, `@NotBlank @Size(min=1,max=10000) messageContent`, `@Size`-bounded `phoneNumber`/`emailAddress`/`emailSubject`, and nullable `recipientType`/`tenantId`/`userId`/`correlationId`/`organizationId`; cross-field rules are deferred to the service
    - _Requirements: 2.7, 2.8, 5.4_

  - [x] 6.2 Create `SentCommunicationResponse` DTO record with `from(...)` mapper
    - Response record containing `id`, `publicId`, and every persisted audit field, with a static `from(SentCommunication)` field-for-field mapper (mirrors `EntityPropertyMappingResponse.from`)
    - _Requirements: 1.7, 3.2_

- [x] 7. Implement `SendService.send` and its correctness properties
  - [x] 7.1 Implement `SendService` skeleton and `send(...)` write flow
    - `@Service @Transactional`; constructor-inject `SentCommunicationRepository`, `DeliveryProviderRegistry`, and `AuthorizationService authz`
    - `send(request)`: cross-field validation (normalize phone/email as supplied only when non-null and non-blank after `strip()`; exactly-one-of → 400; channel/contact consistency → 400; contact format for channel → 400 via `IllegalArgumentException`); OWNER org confinement (force persisted `organizationId` to token org); dispatch via `registry.providerFor(messageType)` exactly once; build + save exactly one entity with `status=DISPATCHED`, `dateSent=Instant.now()` captured once, the single channel-matching contact (other null), `publicId` via `PublicIdGenerator` with bounded `existsByPublicId` retry, and provider fields from the `DeliveryResult`; a persistence failure rolls back the single transaction (→ 500)
    - _Requirements: 1.2, 1.4, 1.5, 1.6, 1.8, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.9, 7.8_

  - [ ]* 7.2 Write property test — Property 1 (successful send persists one well-formed entry)
    - **Property 1: A successful send persists exactly one well-formed audit entry**
    - Comment tag: `Feature: communications-api, Property 1: A successful send persists exactly one well-formed audit entry`
    - Drive `SendService` against a Mockito-fake `SentCommunicationRepository` (assigns id/timestamps on `save`) and a counting stub `DeliveryProvider`; assert provider invoked exactly once, exactly one entity persisted with the specified field values, non-null id/publicId(≤64)/createdAt/updatedAt, `dateSent` non-null and ≤ an instant observed after return, and send→get round-trip equality
    - **Validates: Requirements 1.2, 1.4, 1.5, 1.6, 1.7, 2.3, 2.4, 3.2, 5.1, 5.2, 5.3, 5.4, 5.5, 5.9, 5.12, 5.20, 5.21**

  - [ ]* 7.3 Write property test — Property 2 (exactly-one-of phone/email invariant)
    - **Property 2: Exactly-one-of phone/email invariant**
    - Comment tag: `Feature: communications-api, Property 2: Exactly-one-of phone/email invariant`
    - For any accepted request, assert the persisted entity has exactly one of `phoneNumber`/`emailAddress` non-null and that it is the contact matching `messageType`
    - **Validates: Requirements 2.3, 2.4, 5.8, 1.5**

  - [ ]* 7.4 Write property test — Property 3 (generated publicId is unique)
    - **Property 3: Generated publicId is unique**
    - Comment tag: `Feature: communications-api, Property 3: Generated publicId is unique`
    - For any sequence of successful sends, assert every persisted `publicId` is non-null, length ≤ 64, and pairwise distinct (fake repo tracks `existsByPublicId`)
    - **Validates: Requirements 5.12**

  - [ ]* 7.5 Write property test — Property 4 (invalid recipient-contact requests rejected, persist nothing)
    - **Property 4: Invalid recipient-contact requests are rejected and persist nothing**
    - Comment tag: `Feature: communications-api, Property 4: Invalid recipient-contact requests are rejected and persist nothing`
    - Generate requests with neither/both contacts, channel-mismatched contact, or malformed channel contact; assert `send` throws the 400-mapped `IllegalArgumentException` and the fake repository `save` is never called
    - **Validates: Requirements 2.1, 2.2, 2.5, 2.6, 2.9**

  - [ ]* 7.6 Write property test — Property 9 (OWNER send confines organization to caller's org)
    - **Property 9: OWNER send confines organization to the caller's org**
    - Comment tag: `Feature: communications-api, Property 9: OWNER send confines organization to the caller's org`
    - For any OWNER caller and any supplied `organizationId`, assert the persisted entity's `organizationId` equals the caller's token org (authz stubbed to an OWNER identity)
    - **Validates: Requirements 7.8**

  - [ ]* 7.7 Write unit/example tests for deterministic send scenarios
    - Stub reports success and does no I/O (1.3); persistence failure after dispatch rolls back → surfaces 500 (1.8)
    - _Requirements: 1.3, 1.8_

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement `SendService` read operations and their properties
  - [x] 9.1 Implement `get(UUID id)` and `list(facilityId, messageType, pageable)`
    - `get`: `@Transactional(readOnly=true)` `findById` → `EntityNotFoundException` (404) first; then for an OWNER whose org ≠ row org throw `AccessDeniedException` (403); ADMIN reads any row — mirroring `CommunicationService.get`
    - `list`: `@Transactional(readOnly=true)` resolve role from `authz`; ADMIN uses the non-org-scoped finders, OWNER uses the `organizationId`-leading finders confined to the token org; dispatch to the correct finder by which of `facilityId`/`messageType` are non-null
    - _Requirements: 3.2, 3.3, 3.4, 4.3, 4.4, 4.5, 4.6, 7.5, 7.6, 7.7_

  - [ ]* 9.2 Write property test — Property 5 (list ordering is newest-first and deterministic)
    - **Property 5: List ordering is newest-first and deterministic**
    - Comment tag: `Feature: communications-api, Property 5: List ordering is newest-first and deterministic`
    - Against the fake repository returning visible entries (including equal-`dateSent` ties), assert results are `dateSent` descending with id tiebreak and identical across repeated identical calls
    - **Validates: Requirements 4.2**

  - [ ]* 9.3 Write property test — Property 6 (list is sound, complete, and org-scoped)
    - **Property 6: List is sound, complete, and org-scoped**
    - Comment tag: `Feature: communications-api, Property 6: List is sound, complete, and org-scoped`
    - Generate entries across multiple orgs/facilities, arbitrary `facilityId`/`messageType` filters, and ADMIN vs OWNER callers; assert the result contains exactly the in-scope entries matching every filter (no omissions, no out-of-scope/non-matching inclusions)
    - **Validates: Requirements 4.3, 4.4, 4.5, 4.6, 7.5, 7.6**

  - [ ]* 9.4 Write property test — Property 7 (by-id visibility respects organization scope)
    - **Property 7: By-id visibility respects organization scope**
    - Comment tag: `Feature: communications-api, Property 7: By-id visibility respects organization scope`
    - For any persisted entry and ADMIN/OWNER caller, assert `get` returns the entry for ADMIN or for an OWNER whose org matches, and throws `AccessDeniedException` (403) for an OWNER whose org differs
    - **Validates: Requirements 3.4, 7.7**

  - [ ]* 9.5 Write unit/example tests for deterministic read scenarios
    - Unknown id → 404 (3.3); ADMIN reads across orgs (3.2)
    - _Requirements: 3.2, 3.3_

- [x] 10. Implement the controller and wire the feature together
  - [x] 10.1 Create `CommunicationsController` at `/api/v1/sent-communications`
    - `@RestController` injecting `SendService`; `POST` → 201 returning `SentCommunicationResponse.from(service.send(...))` with `@Valid @RequestBody`; `GET /{id}` with `@PathVariable UUID id`; `GET` list with `@RequestParam(required=false) UUID facilityId`, `@RequestParam(required=false) MessageType messageType`, `page` default 0, `size` default 20, cap at 100, `page<0`/`size<1` → `IllegalArgumentException` (400), wrapping results in `PagedResponse`; `@PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")` on every endpoint so the role check runs before business logic
    - _Requirements: 1.1, 1.7, 3.1, 3.2, 3.5, 4.1, 4.2, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12, 4.13, 7.3, 7.4_

  - [ ]* 10.2 Write property test — Property 8 (applied page size is capped)
    - **Property 8: Applied page size is capped**
    - Comment tag: `Feature: communications-api, Property 8: Applied page size is capped`
    - For any requested size ≥ 1, assert the `Pageable` the controller builds has page size equal to `min(requested, 100)`
    - **Validates: Requirements 4.9**

  - [ ]* 10.3 Write controller example/edge unit tests
    - Default page 0 / size 20 (4.7/4.8); page<0 and size<1 → 400 (4.10); empty `PagedResponse` shape (content empty, totalElements 0, totalPages 0) (4.11); non-UUID path id → 400 (3.5); invalid `messageType` filter → 400 and invalid `facilityId` filter → 400 (4.12/4.13); unknown `messageType` enum in body → 400 (2.8); missing required field → 400 (2.7); ADMIN/OWNER-only reads and `@PreAuthorize`-before-logic 403 (3.1/4.1/7.3/7.4)
    - _Requirements: 2.7, 2.8, 3.1, 3.5, 4.1, 4.7, 4.8, 4.10, 4.11, 4.12, 4.13, 7.3, 7.4_

- [ ] 11. Error-shape and security integration tests
  - [ ]* 11.1 Write property test — Property 10 (error responses are well-formed)
    - **Property 10: Error responses are well-formed**
    - Comment tag: `Feature: communications-api, Property 10: Error responses are well-formed`
    - Across requests producing 400/401/403/404/500, assert the body is an `ErrorResponse` with all four fields (`timestamp`, `status`, `error`, `message`) non-null and `status` equal to the HTTP status code (driven via the existing `GlobalExceptionHandler`)
    - **Validates: Requirements 8.2**

  - [ ]* 11.2 Write MockMvc security integration tests
    - 401 for no/invalid token (7.1), 403 for a disallowed role (7.2), and the standard `ErrorResponse` shape for both (8.1, 8.3–8.6)
    - _Requirements: 7.1, 7.2, 8.1, 8.3, 8.4, 8.5, 8.6_

- [ ] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability; each property-based test task names its design property number and the property text to tag in the test comment.
- Property-based tests use jqwik `@Property(tries = 100)` driven against a Mockito-fake `SentCommunicationRepository` and a counting stub `DeliveryProvider`, placed under `src/test/java/com/storagemanager/communications/property`, mirroring `EntityMappingRoundTripPropertyTest`.
- Every correctness Property 1–10 is covered by exactly one property-based test (P1→7.2, P2→7.3, P3→7.4, P4→7.5, P9→7.6, P5→9.2, P6→9.3, P7→9.4, P8→10.2, P10→11.1).
- Example/edge-case unit tests and integration/migration/security tests cover the deterministic single-scenario and configuration/routing criteria.
- Error handling reuses the existing `GlobalExceptionHandler`; no new handler methods are added.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["2.1", "5.1"] },
    { "id": 2, "tasks": ["2.2", "3.1", "4.1", "5.2", "6.1", "6.2"] },
    { "id": 3, "tasks": ["3.2", "3.3", "4.2", "5.3", "5.4"] },
    { "id": 4, "tasks": ["7.1"] },
    { "id": 5, "tasks": ["7.2", "7.3", "7.4", "7.5", "7.6", "7.7"] },
    { "id": 6, "tasks": ["9.1"] },
    { "id": 7, "tasks": ["9.2", "9.3", "9.4", "9.5"] },
    { "id": 8, "tasks": ["10.1"] },
    { "id": 9, "tasks": ["10.2", "10.3", "11.1", "11.2"] }
  ]
}
```
