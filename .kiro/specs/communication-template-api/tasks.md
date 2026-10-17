# Implementation Plan: Communication Template API (storage-manager-communications)

## Overview

This plan implements the Communication Template CRUD feature in the new `storage-manager-communications` Spring Boot service (Requirements 1–11, Properties 1–18). It was relocated from `storage-manager-api` and follows the same conventions: a JPA entity, request/response DTO records, a Spring Data repository, a `@Transactional` service holding org/facility scoping, owner facility-access validation, and uniqueness enforcement, a REST controller, Flyway migrations, and a shared `GlobalExceptionHandler`.

Two relocation adaptations apply because this service does not own the Organization, Facility, or user-identity tables (see requirements/design): (a) `UserIdentity` is a claims-derived value object populated by `CustomJwtAuthenticationConverter` from the JWT, not a persisted entity; (b) `CommunicationService` injects only `CommunicationTemplateRepository`, `AuthorizationService`, and (for placeholder validation) the read-only `EntityPropertyMappingRepository` — the Organization-existence and Facility-in-Organization DB checks (and their `404`s) are not performed here.

The core CRUD feature (entity, DTOs, repository, service, controller, V1 migration, security/exception infrastructure) and the migration smoke test and application context test are already implemented and verified (`mvn test` green). This update adds the **entity-type association** (a nullable `entityType` on the template) and **write-time placeholder validation** (Requirement 11, Properties 16–18). Several previously-completed CRUD tasks are **reset from `[x]` to `[ ]`** because their underlying code must change to carry the new `entityType` field and run placeholder validation (the entity, the create/update DTOs, the response mapper, and the service skeleton/create/update). Tasks whose code is genuinely unaffected (e.g. the controller wiring, which binds the new DTO field automatically) are left exactly as-is. New work is isolated to the `V4` migration, the `findPlaceholdersByEntityType` repository projection, the `validatePlaceholders` helper (folded into the existing service tasks to minimize task count), and three new property tests. Remaining optional `*` sub-tasks are the per-property jqwik tests and the MockMvc web tests.

## Tasks

- [x] 1. Create the Communication Template entity and database migration
  - [x] 1.1 Create the `CommunicationTemplate` JPA entity
    - Create `src/main/java/com/storagemanager/communications/model/CommunicationTemplate.java`
    - Map to table `communication_templates` with a class-level `@UniqueConstraint` named `uq_comm_tmpl_org_facility_name` on columns `{organization_id, facility_id, template_name}`
    - `id` UUID primary key via `@GeneratedValue(strategy = GenerationType.UUID)`
    - `organizationId` UUID `@Column(name = "organization_id", nullable = false)`
    - `facilityId` UUID nullable `@Column(name = "facility_id")`
    - `templateName` String `@Column(name = "template_name", nullable = false)`
    - `emailContent` String `@Column(name = "email_content", columnDefinition = "TEXT")`
    - `smsContent` String `@Column(name = "sms_content", length = 500)`
    - `entityType` `EntityType` nullable `@Enumerated(EnumType.STRING) @Column(name = "entity_type")`, reusing `com.storagemanager.communications.model.EntityType` (owned by entity-api); a null value denotes no entity-type association
    - `createdAt` Instant `@Column(nullable = false, updatable = false)`; `updatedAt` Instant `@Column(nullable = false)`
    - Add `@PrePersist onCreate()` (sets both timestamps) and `@PreUpdate onUpdate()` (refreshes `updatedAt`)
    - Add getters and setters for all fields (including `entityType`)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.12_

  - [x] 1.2 Create the V1 Flyway migration
    - Create `src/main/resources/db/migration/V1__create_communication_templates.sql`
    - `CREATE TABLE communication_templates` with columns: `id UUID PRIMARY KEY`, `organization_id UUID NOT NULL`, `facility_id UUID`, `template_name VARCHAR(255) NOT NULL`, `email_content TEXT`, `sms_content VARCHAR(500)`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`
    - Add composite unique constraint `uq_comm_tmpl_org_facility_name UNIQUE (organization_id, facility_id, template_name)`
    - Add partial unique index `uq_comm_tmpl_org_name_facility_null ON communication_templates (organization_id, template_name) WHERE facility_id IS NULL`
    - Add index `idx_comm_tmpl_org_created_at ON communication_templates (organization_id, created_at DESC)`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.10, 1.11, 9.7, 9.8_

  - [x]* 1.3 Write migration smoke test
    - Create `src/test/java/com/storagemanager/communications/communication/V1CommunicationMigrationSmokeTest.java`
    - Apply `V1__create_communication_templates.sql` to a fresh H2 database (PostgreSQL mode), neutralizing the Postgres-only partial-index predicate for H2
    - Assert the `communication_templates` table exists with the expected columns
    - Assert the composite unique constraint `uq_comm_tmpl_org_facility_name` exists
    - Assert the org-level unique index `uq_comm_tmpl_org_name_facility_null` exists
    - Also apply `V4__add_entity_type_to_communication_templates.sql` and assert the nullable `entity_type` column now exists on `communication_templates`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.10, 1.11, 1.12, 1.13, 9.7, 9.8_

  - [x] 1.4 Create the V4 Flyway migration adding `entity_type`
    - Create `src/main/resources/db/migration/V4__add_entity_type_to_communication_templates.sql`
    - `ALTER TABLE communication_templates ADD COLUMN entity_type VARCHAR(64)` (nullable, no `DEFAULT`, no `NOT NULL`, no backfill — existing rows keep a null `entity_type`)
    - This is V4 in the service's linear history (after V1 communication_templates, V2 messaging_inbox_outbox, V3 entity_property_mappings); the plain `ADD COLUMN` is portable to H2 PostgreSQL mode with no shim
    - _Requirements: 1.13_

- [x] 2. Create request and response DTO records
  - [x] 2.1 Create `CreateCommunicationTemplateRequest` record
    - Create `src/main/java/com/storagemanager/communications/model/CreateCommunicationTemplateRequest.java`
    - Fields: `UUID organizationId`, `UUID facilityId`, `@NotBlank String templateName`, `String emailContent`, `@Size(max = 500) String smsContent`, `EntityType entityType`
    - `organizationId` intentionally carries no `@NotNull` (its requiredness for ADMIN is a role-dependent service rule)
    - `entityType` is an optional (nullable) `EntityType`; it binds directly to the enum so an unknown string value fails Jackson deserialization → `400` naming `entityType` before `@Valid` runs (framework-driven, Req 3.10)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.9_

  - [x] 2.2 Create `UpdateCommunicationTemplateRequest` record
    - Create `src/main/java/com/storagemanager/communications/model/UpdateCommunicationTemplateRequest.java`
    - Fields (all optional/nullable for PATCH): `@Pattern(regexp = "\\S.*", message = "must not be blank") String templateName`, `String emailContent`, `@Size(max = 500) String smsContent`, `EntityType entityType`
    - `entityType` is an optional (nullable) `EntityType`; a non-null value updates the association and a null value leaves it unchanged (an unknown enum string again fails Jackson binding → `400` naming `entityType`, framework-driven)
    - _Requirements: 6.2, 6.5, 6.6, 6.13_

  - [x] 2.3 Create `CommunicationTemplateResponse` record with `from` mapper
    - Create `src/main/java/com/storagemanager/communications/model/CommunicationTemplateResponse.java`
    - Fields: `UUID id`, `UUID organizationId`, `UUID facilityId`, `String templateName`, `String emailContent`, `String smsContent`, `EntityType entityType`, `Instant createdAt`, `Instant updatedAt`
    - Add `static CommunicationTemplateResponse from(CommunicationTemplate template)` mapping each field one-for-one (including `entityType`), preserving null values
    - _Requirements: 7.1, 7.2, 7.3_

  - [ ]* 2.4 Write property test: Response mapping preserves all fields (Property 14)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationResponseMappingPropertyTest.java`
    - **Property 14: Response mapping preserves all fields**
    - Generate arbitrary `CommunicationTemplate` entities (nullable `facilityId`/`emailContent`/`smsContent`/`entityType`); assert `CommunicationTemplateResponse.from` copies every identically-named field — including `entityType` — and preserves nulls
    - **Validates: Requirements 7.1, 7.2, 7.3**

- [x] 3. Create the Communication Template repository
  - [x] 3.1 Create `CommunicationTemplateRepository`
    - Create `src/main/java/com/storagemanager/communications/repository/CommunicationTemplateRepository.java`
    - Extend `JpaRepository<CommunicationTemplate, UUID>`
    - Add `Page<CommunicationTemplate> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable)`
    - Add `boolean existsByOrganizationIdAndFacilityIdAndTemplateName(UUID organizationId, UUID facilityId, String templateName)`
    - Add `boolean existsByOrganizationIdAndFacilityIdIsNullAndTemplateName(UUID organizationId, String templateName)`
    - _Requirements: 4.2, 4.3, 4.6, 9.1, 9.4, 9.5, 9.8, 1.11_

  - [x] 3.2 Add placeholder lookup to `EntityPropertyMappingRepository`
    - Edit the existing `src/main/java/com/storagemanager/communications/repository/EntityPropertyMappingRepository.java` (owned by entity-api; extended here read-only — this feature does not create, modify, or delete mappings)
    - Add the derived projection `List<String> findPlaceholdersByEntityType(EntityType entityType)` returning the global set of valid placeholder inner names for an `entityType` in one query, backing service-level placeholder validation
    - _Requirements: 11.2, 11.7_

- [x] 4. Implement the Communication Service
  - [x] 4.1 Create `CommunicationService` skeleton, uniqueness pre-check, and placeholder-validation helper
    - Create `src/main/java/com/storagemanager/communications/service/CommunicationService.java`
    - Annotate `@Service @Transactional`; constructor-inject `CommunicationTemplateRepository`, `EntityPropertyMappingRepository` (read-only, for placeholder validation), and `AuthorizationService authz` (no Organization/Facility repositories — relocation adaptation)
    - Add private `requireUniqueName(UUID organizationId, UUID facilityId, String templateName)` using the `...FacilityIdIsNull...` query when `facilityId` is null and the three-column query otherwise, throwing `ConflictException` on a duplicate
    - Add private `validatePlaceholders(String emailContent, String smsContent, EntityType entityType)`: extract every `{{...}}` occurrence from both fields (null field contributes none) trimming inner whitespace; when `entityType` is null throw `IllegalArgumentException` (→ 400) if any occurrence is present; otherwise reject inner names that are empty-after-trim or do not match `^[a-z0-9]+(-[a-z0-9]+)*$`, then fetch the global valid set once via `mappingRepository.findPlaceholdersByEntityType(entityType)` and throw `IllegalArgumentException` (→ 400) naming each distinct unknown offender; zero occurrences pass
    - _Requirements: 1.11, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.8, 11.1, 11.5, 11.6, 11.7, 11.9_

  - [x] 4.2 Implement `CommunicationService.create`
    - Resolve identity via `authz.getCurrentUserIdentity()`
    - OWNER: effective org = JWT org; if `request.organizationId()` is non-null and differs, throw `IllegalArgumentException` identifying `organizationId` (→ 400)
    - ADMIN: effective org = `request.organizationId()`; if null, throw `IllegalArgumentException` identifying `organizationId` (→ 400)
    - When `facilityId` non-null and caller is OWNER, require `authz.canAccessFacility(facilityId)` else `AccessDeniedException` (→ 403)
    - When `facilityId` null, persist as Org_Level_Template
    - Call `requireUniqueName(...)`; set `entityType` from the request; call `validatePlaceholders(emailContent, smsContent, entityType)` against the effective content (→ 400 on failure); then build and `save` the entity; return it
    - (Relocation adaptation: no Organization-existence or Facility-in-org `404` checks)
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 3.11, 9.1, 10.1, 11.2, 11.3, 11.4_

  - [ ]* 4.3 Write property test: Valid create round-trip (Property 1)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationCreatePropertyTest.java`
    - **Property 1: Valid create round-trip** — generate valid create requests; create then fetch; assert resolved field equality. Mocked collaborators.
    - **Validates: Requirements 2.2**

  - [ ]* 4.4 Write property test: Create organization resolution and scoping (Property 2)
    - **Property 2** — OWNER persists JWT org; ADMIN persists body org; omitted `facilityId` → org-level.
    - **Validates: Requirements 2.3, 2.4, 2.8**

  - [ ]* 4.5 Write property test: Create organization-resolution rejections (Property 3)
    - **Property 3** — ADMIN omitting `organizationId` and OWNER mismatched `organizationId` → 400 identifying `organizationId`; nothing persisted.
    - **Validates: Requirements 2.5, 2.6**

  - [ ]* 4.6 Write property test: Scope uniqueness of template names (Property 4)
    - **Property 4** — create/rename rejected with 409 iff a name already exists in the identical `(organizationId, facilityId)` scope; distinct scopes always permitted.
    - **Validates: Requirements 1.11, 2.10, 2.11, 6.12, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6**

  - [ ]* 4.7 Write property test: smsContent length validation (Property 12)
    - **Property 12** — `smsContent` around the 500-char boundary on create/update; 400 identifying `smsContent` when > 500, pass otherwise.
    - **Validates: Requirements 3.6, 6.5**

  - [ ]* 4.8 Write property test: templateName validity (Property 13)
    - **Property 13** — null/whitespace `templateName` on create → 400; whitespace-only supplied value on update → 400.
    - **Validates: Requirements 3.1, 3.7, 6.6**

- [x] 5. Checkpoint - Compile and verify create-path tests pass
  - Ensure the module compiles and the implemented tests pass (`mvn test`).

- [x] 6. Implement list, get, and update service operations
  - [x] 6.1 Implement `CommunicationService.list`
    - Annotate `@Transactional(readOnly = true)`
    - OWNER: effective org = JWT org (ignore param); ADMIN: effective org = `organizationIdParam`, throwing `IllegalArgumentException` identifying `organizationId` (→ 400) when null
    - Return `templateRepository.findByOrganizationIdOrderByCreatedAtDesc(effectiveOrgId, pageable)`
    - _Requirements: 4.2, 4.3, 4.4, 4.6, 4.8, 8.2, 8.3_

  - [x] 6.2 Implement `CommunicationService.get`
    - Annotate `@Transactional(readOnly = true)`
    - Load template else `EntityNotFoundException` (→ 404)
    - ADMIN: return regardless of org; OWNER: return only when `template.getOrganizationId()` equals JWT org, else `AccessDeniedException` (→ 403)
    - _Requirements: 5.2, 5.3, 5.4, 5.6, 8.2_

  - [x] 6.3 Implement `CommunicationService.update`
    - Load template else `EntityNotFoundException` (→ 404)
    - OWNER: require `template.getOrganizationId()` equals JWT org; and when `template.getFacilityId()` non-null, require `authz.canAccessFacility(template.getFacilityId())`; failure → `AccessDeniedException` (→ 403)
    - When `request.templateName()` non-null and differs from current, call `requireUniqueName` against the template's existing scope; duplicate → `ConflictException` (→ 409)
    - Apply `templateName`/`emailContent`/`smsContent`/`entityType` only when the corresponding request field is non-null; leave null fields unchanged (a null `entityType` leaves the existing association intact — clearing to null is out of scope this iteration)
    - After applying the request, call `validatePlaceholders(...)` against the template's effective (post-apply) `emailContent`, `smsContent`, and `entityType` (→ 400 on failure)
    - `save` the entity (the `@PreUpdate` callback refreshes `updatedAt`); return it
    - _Requirements: 6.3, 6.4, 6.8, 6.10, 6.11, 6.12, 6.14, 6.15, 6.16, 6.17, 9.6, 10.2, 10.3_

  - [ ]* 6.4 Write property test: List read-scoping (Property 6)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationListPropertyTest.java`
    - **Property 6** — list returns exactly the applied-scope templates, none from other orgs.
    - **Validates: Requirements 4.2, 4.3, 8.2**

  - [ ]* 6.5 Write property test: List rejects ADMIN without organization parameter (Property 7)
    - **Property 7** — ADMIN list omitting `organizationId` → 400 identifying `organizationId`.
    - **Validates: Requirements 4.4, 8.3**

  - [ ]* 6.6 Write property test: List ordering by creation time descending (Property 8)
    - **Property 8** — returned content ordered by `createdAt` descending.
    - **Validates: Requirements 4.6**

  - [ ]* 6.7 Write property test: Get read-scoping (Property 9)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationGetPropertyTest.java`
    - **Property 9** — get returns for ADMIN or org-matching OWNER; 403 for org-mismatched OWNER.
    - **Validates: Requirements 5.2, 5.3, 5.6, 8.2**

  - [ ]* 6.8 Write property test: Owner facility-access enforcement (Property 5)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationOwnerAccessPropertyTest.java`
    - **Property 5** — 403 when `authz.canAccessFacility` is false on OWNER create/update; success when true.
    - **Validates: Requirements 2.9, 6.11, 10.1, 10.2, 10.3**

  - [ ]* 6.9 Write property test: Partial update preserves null fields and applies non-null fields (Property 10)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationUpdatePropertyTest.java`
    - **Property 10** — non-null fields applied, null fields unchanged; extend the partial-apply generator to include `entityType` so a non-null `entityType` updates the association and a null `entityType` leaves it unchanged.
    - **Validates: Requirements 6.3, 6.4, 6.14, 6.15**

  - [ ]* 6.10 Write property test: Update refreshes updatedAt and preserves createdAt (Property 11)
    - **Property 11** — `updatedAt` not earlier than pre-update value; `createdAt` unchanged.
    - **Validates: Requirements 1.8, 1.9, 6.10**

  - [x]* 6.11 Write property test: Placeholder validity under an entity type (Property 16)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationPlaceholderValidityPropertyTest.java`
    - **Feature: communication-api, Property 16: Placeholder validity under an entity type** — for a non-null effective `entityType`, a generated global placeholder set, and arbitrary `emailContent`/`smsContent` (incl. null fields, arbitrary org/facility scope), validation passes iff every `{{...}}` inner name is well-formed and present in the set; otherwise `400` naming each distinct offender; zero occurrences always pass; outcome independent of org/facility and identical on create and update. Fake `EntityPropertyMappingRepository.findPlaceholdersByEntityType` returns the generated valid set.
    - **Validates: Requirements 3.11, 6.17, 11.1, 11.2, 11.3, 11.5, 11.6, 11.7, 11.9**

  - [x]* 6.12 Write property test: No placeholders without an entity type (Property 17)
    - **Feature: communication-api, Property 17: No placeholders without an entity type** — for a null effective `entityType` and arbitrary content, validation is rejected with `400` (placeholders not permitted) iff at least one `{{...}}` occurrence is present across the two fields (null content contributing none); zero occurrences pass; holds identically on create and update.
    - **Validates: Requirements 11.4, 11.6, 3.11, 6.17**

  - [x]* 6.13 Write property test: entityType persistence and response carry-through (Property 18)
    - **Feature: communication-api, Property 18: entityType persistence and response carry-through** — for a create or update resolving to an effective `entityType` (null or a defined value), the persisted template's `entityType` equals that effective value and `CommunicationTemplateResponse.from` carries the same `entityType` through, preserving null when no entity type is associated.
    - **Validates: Requirements 1.12, 6.14, 6.15, 7.3**

- [x] 7. Checkpoint - Ensure all service tests pass
  - Ensure the implemented tests pass (`mvn test`).

- [x] 8. Implement and wire the Communication Controller
  - [x] 8.1 Create `CommunicationController` with all four endpoints
    - Create `src/main/java/com/storagemanager/communications/controller/CommunicationController.java`
    - Annotate `@RestController`; constructor-inject `CommunicationService`
    - `POST /api/v1/communication-templates` → `@ResponseStatus(HttpStatus.CREATED)`, `@Valid @RequestBody CreateCommunicationTemplateRequest`
    - `GET /api/v1/communication-templates` → optional `organizationId`, `page` (default 0), `size` (default 20); validate `page >= 0` and `size >= 1` (→ 400); cap size via `Math.min(size, 100)`; `PagedResponse.from(...)`
    - `GET /api/v1/communication-templates/{id}` → `@PathVariable UUID id`
    - `PATCH /api/v1/communication-templates/{id}` → `@Valid @RequestBody UpdateCommunicationTemplateRequest`
    - Annotate every method with `@PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")`
    - _Requirements: 2.1, 4.1, 4.5, 4.7, 5.1, 6.1, 8.1, 8.3, 8.6, 8.7_

  - [ ]* 8.2 Write property test: Role gate precedes business logic (Property 15)
    - Create `src/test/java/com/storagemanager/communications/property/CommunicationRoleGatePropertyTest.java`
    - **Property 15** — STAFF/TENANT callers → 403 on every endpoint (incl. non-existent/malformed ids); no business logic runs.
    - **Validates: Requirements 8.1, 8.6, 8.7**

  - [ ]* 8.3 Write example-based web tests (MockMvc)
    - Create `src/test/java/com/storagemanager/communications/controller/CommunicationControllerTest.java`
    - Endpoint wiring/status: `POST` → 201, `GET` list/by-id → 200, `PATCH` → 200
    - Framework behavior: malformed JSON → 400; non-UUID `{id}` → 400; missing/invalid token → 401; multiple validation violations aggregated into one 400
    - Pagination: default `page=0`/`size=20`; `page < 0`/`size < 1` → 400; `size > 100` capped to 100; empty scope → 200 empty content
    - Not-found edges: non-existent template id on get/update → 404
    - _Requirements: 2.1, 3.8, 4.1, 4.5, 4.7, 4.8, 5.1, 5.4, 5.5, 6.1, 6.7, 6.8, 6.9, 8.5_

  - [ ]* 8.4 Write persistence integration test for cross-scope duplicates
    - Create `src/test/java/com/storagemanager/communications/communication/CommunicationTemplatePersistenceIT.java`
    - Persist an org-level and a facility-level template with the same `templateName` to confirm cross-scope duplicates are permitted
    - Attempt a same-scope duplicate and confirm the database constraint/partial index rejects it
    - _Requirements: 9.2, 9.3, 9.7, 9.8_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Run `mvn test` for the communications module and ensure it is green once the optional property/web tests are added.

## Notes

- Tasks marked with `*` are optional (pure test sub-tasks). The core CRUD feature, the migration smoke test (1.3), and the application-context test were already implemented and verified; the remaining `*` tasks are the ported per-property jqwik tests and MockMvc web tests, still to be written in this repo.
- **Entity-type update:** this iteration adds a nullable `entityType` association and write-time placeholder validation (Requirement 11, Properties 16–18). Previously-done CRUD tasks whose code must change were reset `[x] → [ ]` — 1.1 (entity gains `entityType`), 2.1/2.2/2.3 (DTOs + response gain `entityType`), 4.1 (service injects `EntityPropertyMappingRepository` and gains `validatePlaceholders`), 4.2 (create sets `entityType` and validates placeholders), and 6.3 (update applies `entityType` and validates against effective state). New work is 1.4 (V4 migration), 3.2 (`findPlaceholdersByEntityType`), and the three new property tests 6.11–6.13. Task 8.1 (controller) is intentionally left `[x]`: it delegates to the service and relies on Jackson to bind the new DTO `entityType` field and surface unknown-enum bodies as `400`, so no controller code changes.
- Relocation adaptations vs. the api original: `UserIdentity` is claims-derived (no `user_identities` table); `CommunicationService` has no `OrganizationRepository`/`FacilityRepository` and performs no Organization/Facility existence `404` checks; the migration is `V1` (not `V19`); `AuthorizationService.canAccessFacility` grants non-admins at this layer and defers authoritative org-scoping to the service's template-org comparison.
- Property-based tests use jqwik with `@Property(tries = 100)` and mocked collaborators (`CommunicationTemplateRepository`, `AuthorizationService`), following the pattern in the api's `src/test/java/com/storagemanager/api/property/`.
- Each property test is tagged **Feature: communication-api, Property {number}: {property_text}**.
- No new exception types are needed; the feature reuses `ConflictException`, `EntityNotFoundException`, and framework exceptions mapped by `GlobalExceptionHandler`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.4"] },
    { "id": 1, "tasks": ["1.3", "2.1", "2.2", "2.3", "3.2"] },
    { "id": 2, "tasks": ["2.4", "3.1"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2"] },
    { "id": 5, "tasks": ["4.3", "4.4", "4.5", "4.6", "4.7", "4.8", "6.1", "6.2", "6.3"] },
    { "id": 6, "tasks": ["6.4", "6.5", "6.6", "6.7", "6.8", "6.9", "6.10", "6.11", "6.12", "6.13"] },
    { "id": 7, "tasks": ["8.1"] },
    { "id": 8, "tasks": ["8.2", "8.3", "8.4"] }
  ]
}
```
