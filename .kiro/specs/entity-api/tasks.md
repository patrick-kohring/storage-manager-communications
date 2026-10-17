# Implementation Plan: Entity Property Mapping API (storage-manager-communications)

## Overview

This plan implements the Entity Property Mapping CRUD feature in the existing
`storage-manager-communications` Spring Boot service (Requirements 1–14, Correctness Properties
1–7). It mirrors the conventions already used by the **communication-api** feature in this same
service: an `EntityType` enum, a JPA entity with `@PrePersist`/`@PreUpdate` timestamps and a
`(entity_type, placeholder)` `@UniqueConstraint`, request/response DTO records with Jakarta Bean
Validation, a Spring Data repository, a `@Transactional` service holding uniqueness and
partial-update logic, a REST controller with a flat ADMIN-only `@PreAuthorize` gate, a `V3` Flyway
migration continuing this service's linear history, and the shared `GlobalExceptionHandler` /
`ErrorResponse` / `PagedResponse<T>` infrastructure.

**Scope boundary.** All work lands **only** in `storage-manager-communications`. No task touches
`storage-manager-api`, `storage-manager-events`, or any other repository. There is no template
rendering, no upstream read, no routing beyond exposing the five CRUD endpoints, and no
interpretation/validation of `jsonProperty` against any upstream shape (External Assumptions A1–A4
are context only, not work owned here).

Implementation language is **Java (Spring Boot 3.3.5, Java 17, Maven)** — the design specifies
concrete Java throughout; no language selection is required. The one deliberate difference versus
communication-api is a **stricter authorization posture**: every endpoint is **ADMIN-only** and
there is **no org/facility scoping** and **no data-dependent authorization** in the service.

Work is ordered from leaf, no-dependency units outward: the `EntityType` enum and the `V3`
migration are independent early leaves; the JPA entity depends on the enum; the DTO records depend
on the enum; the repository depends on the entity; the service depends on the repository + DTOs;
the controller depends on the service + DTOs; the `GlobalExceptionHandler` refinement is an
independent leaf; and the property/web/smoke tests build on the implemented components. Pure test
sub-tasks are marked optional with `*`. New code lives under the package root
`com.storagemanager.communications`.

> **No JSONB / partial-index shim required.** Unlike communication-api (partial unique index for
> NULL-distinctness) and messaging (H2 `JSONB` domain), the `entity_property_mappings` table uses
> only `UUID` / `VARCHAR` / `TIMESTAMP` with two NOT NULL unique-constrained columns, so the DDL
> runs identically under PostgreSQL (prod) and H2 PostgreSQL mode (tests) with no shim.

## Tasks

- [x] 1. Create the `EntityType` enum
  - Create `src/main/java/com/storagemanager/communications/model/EntityType.java`
  - Define exactly one value `LEAD` in this spec; written so additional values are a pure code
    change later (nothing is keyed off cardinality)
  - This type is referenced directly by the entity and the request/response DTOs so Jackson handles
    string↔enum binding and an unknown string fails as a body-readability `400`
  - _Requirements: 2.1, 2.2_

- [x] 2. Create the `V3` Flyway migration for the mapping table
  - [x] 2.1 Create `src/main/resources/db/migration/V3__create_entity_property_mappings.sql`
    - Continue this service's linear Flyway history after `V1` (communication_templates) and `V2`
      (messaging_inbox_outbox); the next free version is `V3`
    - `CREATE TABLE entity_property_mappings` with columns: `id UUID PRIMARY KEY`,
      `entity_type VARCHAR(64) NOT NULL`, `json_property VARCHAR(255) NOT NULL`,
      `placeholder VARCHAR(255) NOT NULL`, `created_at TIMESTAMP WITH TIME ZONE NOT NULL`,
      `updated_at TIMESTAMP WITH TIME ZONE NOT NULL`
    - Add the `uq_`-prefixed unique constraint `uq_entity_prop_mapping_type_placeholder`
      `UNIQUE (entity_type, placeholder)` (matching the entity `@UniqueConstraint` name exactly)
    - Add the `idx_`-prefixed index `idx_entity_prop_mapping_type_created_at`
      `ON entity_property_mappings (entity_type, created_at DESC)` supporting newest-first ordering
      for both the filtered and unfiltered list queries
    - Add **no** unique constraint on `json_property` (shared `jsonProperty` is permitted)
    - _Requirements: 1.1, 1.8, 1.9, 3.1, 3.2, 3.3, 3.4, 12.4_

  - [x] 2.2 Write the `V3` migration smoke test
    - Create `src/test/java/com/storagemanager/communications/entity/V3EntityMappingMigrationSmokeTest.java`
    - Apply `V3__create_entity_property_mappings.sql` to a fresh H2 database (PostgreSQL mode) — no
      `JSONB` domain or partial-index neutralization is needed since the DDL is portable as-is
    - Assert the `entity_property_mappings` table exists with the expected columns/types
    - Assert the `uq_entity_prop_mapping_type_placeholder` unique constraint on
      `(entity_type, placeholder)` exists and the `idx_entity_prop_mapping_type_created_at` index
      exists
    - Assert there is **no** unique constraint on `json_property`
    - _Requirements: 1.1, 3.1, 3.2, 3.3, 3.4, 12.4_

- [x] 3. Create the `EntityPropertyMapping` JPA entity
  - Create `src/main/java/com/storagemanager/communications/model/EntityPropertyMapping.java`
  - Map to table `entity_property_mappings` with a class-level `@UniqueConstraint` named
    `uq_entity_prop_mapping_type_placeholder` on columns `{entity_type, placeholder}`
  - `id` UUID primary key via `@GeneratedValue(strategy = GenerationType.UUID)`
  - `entityType` `@Enumerated(EnumType.STRING)` `@Column(name = "entity_type", nullable = false)`
  - `jsonProperty` String `@Column(name = "json_property", nullable = false)` stored as supplied
    (no interpretation/traversal/validation)
  - `placeholder` String `@Column(name = "placeholder", nullable = false)` holding the inner name
    only (no `{{`/`}}`)
  - `createdAt` Instant `@Column(name = "created_at", nullable = false, updatable = false)`;
    `updatedAt` Instant `@Column(name = "updated_at", nullable = false)`
  - Add `@PrePersist onCreate()` (sets both timestamps to `Instant.now()`) and
    `@PreUpdate onUpdate()` (refreshes `updatedAt`)
  - Add getters and setters for all fields
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.3, 11.2_

- [x] 4. Create the request and response DTO records
  - [x] 4.1 Create `CreateEntityPropertyMappingRequest` record
    - Create `src/main/java/com/storagemanager/communications/dto/CreateEntityPropertyMappingRequest.java`
    - Fields: `@NotNull EntityType entityType`, `@NotBlank String jsonProperty`,
      `@NotBlank @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "must be kebab-case ...") String placeholder`
    - `@NotNull` on `entityType` yields `400` naming `entityType` when absent/null; a non-enum string
      fails at Jackson binding (`HttpMessageNotReadableException` → `400`) before `@Valid` runs
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 11.1, 11.3_

  - [x] 4.2 Create `UpdateEntityPropertyMappingRequest` record
    - Create `src/main/java/com/storagemanager/communications/dto/UpdateEntityPropertyMappingRequest.java`
    - Fields (all optional/nullable for PATCH): `EntityType entityType`, `String jsonProperty`,
      `@Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "must be kebab-case ...") String placeholder`
    - `jsonProperty` carries no `@NotBlank` (null means "unchanged"); `@Pattern` passes on `null` but
      fails on a supplied non-kebab value → `400` naming `placeholder`
    - _Requirements: 8.2, 8.5, 11.3_

  - [x] 4.3 Create `EntityPropertyMappingResponse` record with `from` mapper
    - Create `src/main/java/com/storagemanager/communications/dto/EntityPropertyMappingResponse.java`
    - Fields: `UUID id`, `EntityType entityType`, `String jsonProperty`, `String placeholder`,
      `Instant createdAt`, `Instant updatedAt`
    - Add `static EntityPropertyMappingResponse from(EntityPropertyMapping m)` mapping each field
      one-for-one from the identically-named entity field
    - _Requirements: 10.1, 10.2_

- [x] 5. Create the `EntityPropertyMappingRepository`
  - Create `src/main/java/com/storagemanager/communications/repository/EntityPropertyMappingRepository.java`
  - Extend `JpaRepository<EntityPropertyMapping, UUID>`
  - Add `boolean existsByEntityTypeAndPlaceholder(EntityType entityType, String placeholder)` for
    the service-level uniqueness pre-check
  - Add `Page<EntityPropertyMapping> findAllByOrderByCreatedAtDesc(Pageable pageable)` for the
    unfiltered newest-first list
  - Add `Page<EntityPropertyMapping> findByEntityTypeOrderByCreatedAtDesc(EntityType entityType, Pageable pageable)`
    for the `entityType`-filtered newest-first list
  - _Requirements: 6.3, 6.4, 6.7, 12.1, 12.5_

- [x] 6. Implement the `EntityPropertyMappingService`
  - [x] 6.1 Create `EntityPropertyMappingService` skeleton and uniqueness pre-check
    - Create `src/main/java/com/storagemanager/communications/service/EntityPropertyMappingService.java`
    - Annotate `@Service @Transactional`; constructor-inject only
      `EntityPropertyMappingRepository` (no org/facility repositories, no `AuthorizationService`
      identity use — mappings are global and ADMIN-only)
    - Add private `requireUniquePair(EntityType entityType, String placeholder)` using
      `existsByEntityTypeAndPlaceholder(...)`, throwing `ConflictException` with a clean message on a
      duplicate
    - _Requirements: 12.1, 12.3, 12.5_

  - [x] 6.2 Implement `EntityPropertyMappingService.create`
    - Run `requireUniquePair(request.entityType(), request.placeholder())` → `ConflictException`
      (→ 409) on a duplicate
    - Otherwise build the entity (copy `entityType`, `jsonProperty` as-is, `placeholder`) and `save`;
      the `@PrePersist` callback sets `createdAt`/`updatedAt`; return the persisted entity
    - Store `jsonProperty` exactly as supplied with no interpretation/traversal/validation
    - _Requirements: 4.2, 4.4, 4.5, 4.6, 14.1, 14.2, 14.3_

  - [x] 6.3 Implement `EntityPropertyMappingService.list`
    - Annotate `@Transactional(readOnly = true)`
    - When `entityType` is non-null, return `findByEntityTypeOrderByCreatedAtDesc(entityType, pageable)`;
      when null, return `findAllByOrderByCreatedAtDesc(pageable)` (both newest-first)
    - An empty result yields an empty page (controller renders `200` with empty `content` and
      `totalElements = 0`)
    - _Requirements: 6.3, 6.4, 6.7, 6.9_

  - [x] 6.4 Implement `EntityPropertyMappingService.get`
    - Annotate `@Transactional(readOnly = true)`
    - `findById(id).orElseThrow(() -> new EntityNotFoundException(...))` → `404` when missing
    - _Requirements: 7.2, 7.3_

  - [x] 6.5 Implement `EntityPropertyMappingService.update` (partial)
    - Load via `findById` else `EntityNotFoundException` (→ 404)
    - Compute the prospective `(entityType, placeholder)` from the request's non-null fields merged
      over current values; if that pair differs from the current pair **and** exists on a different
      row, throw `ConflictException` (→ 409)
    - Apply each non-null request field; leave null fields unchanged; `save` (the `@PreUpdate`
      callback refreshes `updatedAt`, `createdAt` is unchanged); return the entity
    - _Requirements: 8.3, 8.4, 8.7, 8.9, 8.10, 12.3_

  - [x] 6.6 Implement `EntityPropertyMappingService.delete`
    - If `!repository.existsById(id)` throw `EntityNotFoundException` (→ 404); else
      `repository.deleteById(id)`
    - _Requirements: 9.2, 9.3_

  - [x] 6.7 Write jqwik property test — Property 1 (pair uniqueness invariant)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingUniquenessPropertyTest.java`
    - **Feature: entity-api, Property 1: `(entityType, placeholder)` uniqueness invariant** — over a
      sequence of create/update ops on an initially empty store, no two persisted mappings share a
      `(entityType, placeholder)` pair; a create/rename to an existing pair raises `ConflictException`
      and leaves the store unchanged; a fresh pair succeeds; two mappings with equal `jsonProperty`
      under distinct placeholders both persist. Repository backed by an in-memory fake or Mockito
      mock.
    - **Validates: Requirements 1.9, 4.5, 8.9, 12.1, 12.2, 12.3, 12.5**

  - [x] 6.8 Write jqwik property test — Property 3 (create-then-get round-trip)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingRoundTripPropertyTest.java`
    - **Feature: entity-api, Property 3: Create-then-get round-trip fidelity** — for any valid create
      request (`jsonProperty` across flat names, dot-paths, odd/unicode strings), `get(create(req).id)`
      equals the supplied `entityType`/`jsonProperty`/`placeholder` field-for-field with `jsonProperty`
      stored verbatim; `id`/`createdAt`/`updatedAt` non-null; `EntityPropertyMappingResponse.from`
      carries each field through unchanged.
    - **Validates: Requirements 1.2, 1.4, 4.2, 4.4, 4.6, 7.2, 10.1, 10.2, 14.2, 14.3**

  - [x] 6.9 Write jqwik property test — Property 4 (partial-update semantics and timestamps)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingUpdatePropertyTest.java`
    - **Feature: entity-api, Property 4: Partial-update semantics and timestamp invariants** — for an
      existing mapping and an update request where each field is independently null or a fresh valid
      value (chosen to avoid pair collisions), each non-null field replaces the stored field and each
      null field is left unchanged; `createdAt` is unchanged and `updatedAt >= original.updatedAt`.
    - **Validates: Requirements 1.6, 1.7, 8.3, 8.4, 8.10**

  - [x] 6.10 Write jqwik property test — Property 5 (list ordering, filtering, size cap)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingListPropertyTest.java`
    - **Feature: entity-api, Property 5: List ordering, filtering, and page-size cap** — for any
      backing set and any list request, content is monotone non-increasing by `createdAt`; a supplied
      `entityType` filter returns exactly the mappings of that type (none of any other) and no filter
      returns all types; for any requested `size > 100` the effective page size is capped at 100 and
      reflected as 100 in the pagination metadata (`size` spanning `{1, 20, 99, 100, 101, 500}`).
    - **Validates: Requirements 6.3, 6.4, 6.7, 6.8**

- [x] 7. Checkpoint — compile and verify service-path tests pass
  - Ensure the module compiles and the implemented tests pass (`mvn test`).
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Refine `GlobalExceptionHandler` to name the `entityType` field on an unknown-enum body
  - Update `src/main/java/com/storagemanager/communications/exception/GlobalExceptionHandler.java`
  - In `handleHttpMessageNotReadable`, inspect `ex.getCause()`: when it is a Jackson
    `InvalidFormatException` whose `getTargetType().isEnum()` is true, derive the field name from the
    last `getPath()` reference and return a message naming the field (e.g.
    `"entityType: unknown value '...'"`); otherwise keep the existing
    `"Malformed JSON request body"` → `400`
    behavior (backward-compatible; still `400` in all enum-binding cases)
  - Leave the existing `ConflictException` → `409`, `EntityNotFoundException` → `404`,
    `MethodArgumentNotValidException`/`MethodArgumentTypeMismatchException`/`IllegalArgumentException`
    → `400`, `AccessDenied` → `403`, and `Authentication` → `401` mappings unchanged
  - _Requirements: 2.4, 5.5, 5.7, 6.5, 8.6_

- [x] 9. Implement and wire the `EntityPropertyMappingController`
  - [x] 9.1 Create `EntityPropertyMappingController` with all five endpoints
    - Create `src/main/java/com/storagemanager/communications/controller/EntityPropertyMappingController.java`
    - Annotate `@RestController`; constructor-inject `EntityPropertyMappingService`
    - `POST /api/v1/entity-property-mappings` → `@ResponseStatus(HttpStatus.CREATED)`,
      `@Valid @RequestBody CreateEntityPropertyMappingRequest`, returns
      `EntityPropertyMappingResponse` (`201`)
    - `GET /api/v1/entity-property-mappings` → optional `@RequestParam EntityType entityType`,
      `page` (default 0), `size` (default 20); validate `page >= 0` and `size >= 1` (→ 400);
      cap size via `Math.min(size, 100)`; return `PagedResponse.from(...)` (`200`)
    - `GET /api/v1/entity-property-mappings/{id}` → `@PathVariable UUID id`, returns
      `EntityPropertyMappingResponse` (`200`)
    - `PATCH /api/v1/entity-property-mappings/{id}` →
      `@Valid @RequestBody UpdateEntityPropertyMappingRequest`, returns
      `EntityPropertyMappingResponse` (`200`)
    - `DELETE /api/v1/entity-property-mappings/{id}` → `@ResponseStatus(HttpStatus.NO_CONTENT)`
      (`204`)
    - Annotate **every** method with the flat `@PreAuthorize("@authz.isAdmin()")` gate so the role
      check runs before any business logic
    - Confirm `SecurityConfig` requires authentication for these paths under
      `anyRequest().authenticated()` (no `permitAll` added); no `@EntityScan`/
      `@EnableJpaRepositories` change is needed — fold this verification in here
    - _Requirements: 4.1, 4.3, 6.1, 6.2, 6.6, 6.8, 7.1, 8.1, 9.1, 9.2, 13.1, 13.5_

  - [x] 9.2 Write jqwik property test — Property 7 (ADMIN-only authorization)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingAuthorizationPropertyTest.java`
    - **Feature: entity-api, Property 7: Authorization — ADMIN-only across every endpoint** —
      MockMvc-driven jqwik property over caller role `{OWNER, STAFF, TENANT}` (plus anonymous),
      endpoint `{create, list, get, update, delete}`, and `id` `{existing, non-existent, malformed}`:
      a non-ADMIN authenticated caller gets `403` before any service/repository call (verified via
      mock interactions), an unauthenticated caller gets `401`, and an ADMIN is never rejected by the
      gate. Uses `spring-security-test`; service mocked to assert it is never invoked for non-ADMIN.
    - **Validates: Requirements 13.1, 13.3, 13.4, 13.5**

  - [x] 9.3 Write jqwik property test — Property 2 (kebab-case enforcement)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingKebabPropertyTest.java`
    - **Feature: entity-api, Property 2: Kebab-case placeholder enforcement** — MockMvc/validation
      property: a candidate `placeholder` is accepted (format-wise) iff it matches
      `^[a-z0-9]+(-[a-z0-9]+)*$`; a non-matching value (uppercase, underscore,
      leading/trailing/double hyphen, braces, spaces, empty) is rejected `400` naming `placeholder`
      (`MethodArgumentNotValidException`); every persisted placeholder matches the pattern and never
      contains `{{`/`}}`.
    - **Validates: Requirements 5.4, 8.5, 11.1, 11.2, 11.3**

  - [x] 9.4 Write jqwik property test — Property 6 (required-field validation)
    - Create `src/test/java/com/storagemanager/communications/property/EntityMappingRequiredFieldPropertyTest.java`
    - **Feature: entity-api, Property 6: Required-field (non-blank) validation** — a create request
      with `entityType` null, or `jsonProperty` null/whitespace-only, or `placeholder`
      null/whitespace-only is rejected `400` naming every failing field; a request with all three
      present, non-blank, and placeholder kebab-valid passes validation.
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.6**

  - [x] 9.5 Write example-based web tests (MockMvc)
    - Create `src/test/java/com/storagemanager/communications/controller/EntityPropertyMappingControllerTest.java`
    - Endpoint wiring/status: `POST` → `201` with body; `GET` list → `200`; `GET /{id}` → `200`;
      `PATCH` → `200`; `DELETE` → `204`
    - Enum facts: `EntityType.valueOf("LEAD")` and `EntityType.values().length == 1`; persist/read
      back the `LEAD` string
    - Framework behavior: malformed JSON body → `400`; unknown `entityType` in body → `400` naming
      `entityType`; unknown `entityType` query param → `400`; non-UUID `{id}` on get/update/delete →
      `400`; multiple validation violations aggregated into one `400` listing fields; missing/invalid
      token → `401`
    - Pagination: default `page=0`/`size=20`; `page < 0` and `size < 1` → `400`; `size > 100` capped
      to 100 and reported as 100 in metadata; empty result → `200` with empty `content` and
      `totalElements = 0`
    - Not-found edges: non-existent id on get/update/delete → `404`
    - Delete behavior: create → delete (`204`) → get → `404`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 4.1, 4.3, 5.5, 5.6, 5.7, 6.1, 6.5, 6.6, 6.8, 6.9, 7.1, 7.3, 7.4, 8.1, 8.6, 8.7, 8.8, 9.1, 9.2, 9.3, 9.4, 13.2_

  - [x] 9.6 Write persistence integration test for the uniqueness constraint and shared property
    - Create `src/test/java/com/storagemanager/communications/entity/EntityPropertyMappingPersistenceIT.java`
    - Persist two mappings with the same `jsonProperty` but different placeholders to confirm the
      schema permits shared properties
    - Attempt a second insert with the same `(entity_type, placeholder)` pair and confirm the
      database unique constraint rejects it — the database-level counterpart to Property 1
    - _Requirements: 1.9, 12.2, 12.4_

- [x] 10. Final checkpoint — build the module and run the full test suite
  - Run `mvn test` for the communications module and ensure it is green end-to-end.
  - No `JSONB` domain or partial-index neutralization is required for the H2 test datasource for this
    feature's DDL — the `V3` migration is portable as-is.
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (pure test sub-tasks) and can be skipped for a faster MVP; the
  core feature (enum, migration, entity, DTOs, repository, service, handler refinement, controller)
  is required.
- Each task references specific requirements for traceability; property-test sub-tasks name the
  design Correctness Property and the requirements clauses they validate.
- Property tests use jqwik 1.8.5 + jqwik-spring 0.12.0 (minimum 100 iterations, `@Property`),
  matching the communications test stack; Properties 1 and 3–5 exercise
  `EntityPropertyMappingService` with the repository backed by an in-memory fake or Mockito mock,
  while Properties 2, 6, and 7 are driven through MockMvc with `spring-security-test`. The jqwik
  PBT library is reused, never reimplemented.
- Each property test is tagged **Feature: entity-api, Property {number}: {property_text}**.
- No new exception types are needed; the feature reuses `ConflictException`,
  `EntityNotFoundException`, and framework exceptions mapped by `GlobalExceptionHandler`. The only
  handler change is a backward-compatible refinement to name the `entityType` field on an
  unknown-enum body (task 8).
- This feature has **no org/facility scoping** and **no data-dependent authorization**: the sole
  authorization decision is the controller's flat `@PreAuthorize("@authz.isAdmin()")`.
- Requirements 2.1/2.2/2.3 (enum facts/binding), 3.x (migration DDL), 5.5/5.7/6.5/7.4/8.6/8.8/9.4
  (framework binding), 6.6/6.9 (pagination defaults/empty), 7.3/8.7/9.3 (not-found), 13.2 (401) are
  not property-based; they are covered by the migration smoke test, persistence IT, and MockMvc
  example tests.
- All tasks are strictly coding activities (writing/modifying/testing code and SQL) and all changes
  land only in `storage-manager-communications`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2.1", "8"] },
    { "id": 1, "tasks": ["2.2", "3", "4.1", "4.2", "4.3"] },
    { "id": 2, "tasks": ["5"] },
    { "id": 3, "tasks": ["6.1"] },
    { "id": 4, "tasks": ["6.2", "6.3", "6.4", "6.5", "6.6"] },
    { "id": 5, "tasks": ["6.7", "6.8", "6.9", "6.10", "9.1"] },
    { "id": 6, "tasks": ["9.2", "9.3", "9.4", "9.5", "9.6"] }
  ]
}
```
