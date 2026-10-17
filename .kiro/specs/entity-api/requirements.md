# Requirements Document

## Introduction

This document captures all requirements for the **Entity Property Mapping API** in the Storage Manager Communications service (`storage-manager-communications`). An Entity Property Mapping is a single row that translates a JSON property on an upstream domain entity into a kebab-case template placeholder name used later for template personalization. For example, a mapping may associate the entity type `LEAD` and the JSON property `first-name` source `firstName` with the placeholder `first-name`, which a template author later references as `{{first-name}}`.

The scope of this spec is **narrow and deliberate**: it introduces the mapping table and its CRUD API only. This spec does **not** render templates, does **not** call or read from any upstream service, and does **not** wire endpoint routing beyond exposing the CRUD endpoints themselves. Template rendering, placeholder substitution, upstream entity reads, and template-authoring validation are all deferred to future, separate specs.

This spec affects **only** the `storage-manager-communications` component. It introduces no changes to `storage-manager-api`, `storage-manager-events`, or any other component. Any reliance on another service (for example, the shape of the upstream Lead response) is recorded in the External Assumptions section as a cross-repository context item, not as work owned here.

The feature introduces an `EntityPropertyMappingController` exposing CRUD endpoints to create a mapping, list mappings (paginated, filterable by entity type), fetch a single mapping by id, partially update a mapping, and delete a mapping. The design follows the Storage Manager conventions already used by the `communication-api` spec in this service: a JPA entity with timestamps managed via `@PrePersist`/`@PreUpdate`, Jakarta Bean Validation request DTO records, response DTO records with a static `from` mapper, a `@Transactional` service, a Spring Data repository, custom exceptions handled by the `GlobalExceptionHandler`, and role-based access control via Spring Security `@PreAuthorize` expressions backed by the `authz` (`AuthorizationService`) bean. All list results use the `PagedResponse<T>` wrapper, identifiers are UUIDs, the Flyway migration continues this service's linear history after `V2`, and the API path prefix is `/api/v1/...`.

Authorization for this feature is stricter than the `communication-api` spec. Every CRUD endpoint is restricted to the `ADMIN` role only. Callers holding the `OWNER` role or any other role receive `403 Forbidden`, and callers without a verified JWT receive `401 Unauthorized`.

Each mapping's `(entityType, placeholder)` combination is unique, enforced both by a database-level unique constraint and by a service-level check that produces a clean `409 Conflict` message following the `ConflictException` convention. Many placeholders MAY map to the same `jsonProperty`; uniqueness is **not** enforced on `jsonProperty`.

## Glossary

- **Entity_Property_Mapping**: A JPA entity representing a single translation from an upstream entity JSON property to a template placeholder, mapped to the `entity_property_mappings` database table.
- **Mapping_Service**: The Spring service class (`EntityPropertyMappingService`) responsible for Entity Property Mapping CRUD, validation, and uniqueness business logic.
- **Mapping_Controller**: The Spring REST controller (`EntityPropertyMappingController`) exposing Entity Property Mapping endpoints under `/api/v1`.
- **CreateEntityPropertyMappingRequest**: The request DTO record used when creating a new Entity Property Mapping, validated with Jakarta Bean Validation.
- **UpdateEntityPropertyMappingRequest**: The request DTO record used when partially updating an existing Entity Property Mapping via PATCH; all fields are optional (nullable).
- **EntityPropertyMappingResponse**: The response DTO record returned from Entity Property Mapping API endpoints.
- **Entity_Type**: An enumeration (`EntityType`) identifying the upstream domain entity a mapping applies to. The predefined set contains exactly one value in this spec: `LEAD`. The enumeration is modeled in code so that additional values MAY be added in a future code change.
- **Json_Property**: A String field on the Entity_Property_Mapping holding the name of the property on the upstream entity's JSON response. The value MAY be a flat name (for example `firstName`) or a dot-path (for example `address.city`). This spec stores the value as-is and does not interpret, traverse, or validate it against any upstream shape.
- **Placeholder**: A String field on the Entity_Property_Mapping holding the kebab-case template placeholder name (for example `first-name`). The value is the inner name only; the surrounding `{{` and `}}` delimiters are not stored.
- **Kebab_Case_Pattern**: The regular expression `^[a-z0-9]+(-[a-z0-9]+)*$` that a valid Placeholder value MUST match.
- **Uniqueness_Scope**: The `(entityType, placeholder)` pair within which an Entity_Property_Mapping MUST be unique.
- **API**: The Storage Manager Communications REST API.
- **Authz**: The `AuthorizationService` Spring bean (registered as `authz`) that evaluates role-based access rules in `@PreAuthorize` expressions and exposes the current caller identity derived from the JWT.
- **ADMIN**: A cross-organization `UserRole`. The only role permitted to access Entity Property Mapping endpoints in this spec.
- **OWNER**: A `UserRole` scoped to a single Organization. Not permitted to access Entity Property Mapping endpoints in this spec.
- **JWT_Token**: The authentication token presented by a caller, validated by the `CustomJwtAuthenticationConverter`, from which the caller's role is derived.
- **PagedResponse**: A paginated response wrapper containing `content`, `page`, `totalPages`, and `totalElements` fields.
- **ConflictException**: The API exception, handled by the `GlobalExceptionHandler` to produce a `409 Conflict` response in the standard error response format, used for duplicate-resource violations.
- **GlobalExceptionHandler**: The service's `@RestControllerAdvice` component that maps exceptions to the standard error response format and HTTP status codes.
- **Host_Migration**: A versioned Flyway migration owned and numbered by the Communications_Service, continuing after `V2` (that is, `V3` or later), that creates the `entity_property_mappings` table.

## Requirements

### Requirement 1: Entity Property Mapping Entity

**User Story:** As a developer, I want an Entity Property Mapping entity that persists a translation from an upstream entity JSON property to a template placeholder, so that mappings can be stored, queried, and maintained for later template personalization.

#### Acceptance Criteria

1. THE Entity_Property_Mapping entity SHALL be mapped to a database table named `entity_property_mappings`.
2. THE Entity_Property_Mapping entity SHALL include an `id` field of type UUID generated by the persistence provider as the primary key.
3. THE Entity_Property_Mapping entity SHALL include an `entityType` field of type Entity_Type persisted as a String via `EnumType.STRING`, mapped to a NOT NULL column.
4. THE Entity_Property_Mapping entity SHALL include a `jsonProperty` field of type String mapped to a NOT NULL column, stored as supplied without interpretation or traversal.
5. THE Entity_Property_Mapping entity SHALL include a `placeholder` field of type String mapped to a NOT NULL column.
6. THE Entity_Property_Mapping entity SHALL include a `createdAt` field of type Instant mapped to a NOT NULL, non-updatable column set on first persist via the `@PrePersist` lifecycle callback.
7. THE Entity_Property_Mapping entity SHALL include an `updatedAt` field of type Instant mapped to a NOT NULL column set on first persist via the `@PrePersist` lifecycle callback and refreshed on each update via the `@PreUpdate` lifecycle callback.
8. THE Entity_Property_Mapping persistence schema SHALL enforce a database-level uniqueness constraint on the combination of `entityType` and `placeholder`.
9. THE Entity_Property_Mapping persistence schema SHALL NOT enforce any uniqueness constraint on the `jsonProperty` column, so that multiple mappings MAY reference the same `jsonProperty`.

### Requirement 2: Entity Type Enumeration

**User Story:** As a developer, I want an Entity_Type enumeration, so that each mapping records a controlled value identifying the upstream entity it applies to and new entity types can be added in code later.

#### Acceptance Criteria

1. THE Entity_Type enumeration SHALL define the value `LEAD`.
2. THE Entity_Type enumeration SHALL define exactly one value, `LEAD`, in this spec.
3. WHEN an Entity_Property_Mapping is persisted, THE Mapping_Service SHALL store the Entity_Type value as its String name via `EnumType.STRING`.
4. IF a create or update request supplies an `entityType` value that is not a defined Entity_Type value, THEN THE API SHALL return a `400 Bad Request` response that identifies the `entityType` field as the failed field.

### Requirement 3: Host Flyway Migration for the Mapping Table

**User Story:** As a Communications_Service developer, I want a Flyway migration that creates the `entity_property_mappings` table in this service's own database, so that the entity validates under `ddl-auto=validate` and mapping rows persist correctly.

#### Acceptance Criteria

1. THE Host_Migration SHALL be numbered within the Communications_Service Flyway history continuing after `V2` (that is, `V3` or later).
2. THE Host_Migration SHALL create an `entity_property_mappings` table with an `id` UUID primary key column, an `entity_type` NOT NULL string column, a `json_property` NOT NULL string column, a `placeholder` NOT NULL string column, a `created_at` NOT NULL timestamp column, and an `updated_at` NOT NULL timestamp column.
3. THE Host_Migration SHALL define a unique constraint named with the `uq_` prefix on the combination of the `entity_type` and `placeholder` columns.
4. THE Host_Migration SHALL define an index named with the `idx_` prefix supporting newest-first ordering by `created_at` for list queries.

### Requirement 4: Create Entity Property Mapping

**User Story:** As an administrator, I want to create an entity property mapping, so that an upstream entity property is associated with a template placeholder for later personalization.

#### Acceptance Criteria

1. THE Mapping_Controller SHALL expose a POST endpoint at `/api/v1/entity-property-mappings` that accepts a CreateEntityPropertyMappingRequest body and returns an EntityPropertyMappingResponse with HTTP `201 Created`.
2. WHEN a create request is received with all required fields present and valid, THE Mapping_Service SHALL persist a new Entity_Property_Mapping and return the created Entity_Property_Mapping.
3. WHEN a create request is received with all required fields present and valid, THE API SHALL return an HTTP `201 Created` response with a body containing the created EntityPropertyMappingResponse.
4. WHEN a create request is received, THE Mapping_Service SHALL store the `jsonProperty` value exactly as supplied without interpreting, traversing, or validating the value against any upstream entity shape.
5. IF a create request would persist an Entity_Property_Mapping whose `(entityType, placeholder)` combination already exists, THEN THE Mapping_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.
6. WHEN an Entity_Property_Mapping is created, THE Mapping_Service SHALL set the `createdAt` and `updatedAt` fields to the current timestamp.

### Requirement 5: Create Entity Property Mapping Request Validation

**User Story:** As an API consumer, I want the create endpoint to validate fields, so that invalid mapping data is rejected before persistence.

#### Acceptance Criteria

1. THE CreateEntityPropertyMappingRequest SHALL require a non-null `entityType` field of type Entity_Type.
2. THE CreateEntityPropertyMappingRequest SHALL require a non-blank `jsonProperty` field of type String.
3. THE CreateEntityPropertyMappingRequest SHALL require a non-blank `placeholder` field of type String.
4. IF a create request supplies a `placeholder` value that does not match the Kebab_Case_Pattern `^[a-z0-9]+(-[a-z0-9]+)*$`, THEN THE API SHALL return a `400 Bad Request` response that identifies the `placeholder` field as the failed field.
5. IF a create request supplies an `entityType` value that is not a defined Entity_Type value, THEN THE API SHALL return a `400 Bad Request` response that identifies the `entityType` field as the failed field.
6. WHEN a create request is submitted with the `entityType`, `jsonProperty`, or `placeholder` field null or blank, THE API SHALL return a `400 Bad Request` response containing a message that identifies each field that failed validation.
7. WHEN a create request is submitted with a malformed request body that cannot be deserialized, THE API SHALL return a `400 Bad Request` response containing a message indicating the body is malformed.

### Requirement 6: List Entity Property Mappings

**User Story:** As an administrator, I want to list entity property mappings with the ability to filter by entity type, so that I can review the mappings defined for the system.

#### Acceptance Criteria

1. THE Mapping_Controller SHALL expose a GET endpoint at `/api/v1/entity-property-mappings` that returns a PagedResponse of EntityPropertyMappingResponse objects with HTTP `200 OK`.
2. THE list endpoint SHALL accept an optional `entityType` query parameter of type Entity_Type.
3. WHEN a list request is received with the `entityType` parameter present, THE Mapping_Service SHALL return only Entity_Property_Mappings whose `entityType` equals the supplied `entityType` parameter.
4. WHEN a list request is received without the `entityType` parameter, THE Mapping_Service SHALL return Entity_Property_Mappings of all entity types.
5. IF a list request supplies an `entityType` parameter value that is not a defined Entity_Type value, THEN THE API SHALL return a `400 Bad Request` response that identifies the `entityType` parameter as the failed field.
6. THE list endpoint SHALL accept optional `page` (default 0, minimum 0) and `size` (default 20, minimum 1) query parameters.
7. WHEN a list request is received, THE Mapping_Service SHALL return Entity_Property_Mappings ordered by `createdAt` descending within the PagedResponse, including `content`, `page`, `totalPages`, and `totalElements` fields.
8. IF the `size` parameter exceeds 100, THEN THE Mapping_Controller SHALL cap the page size at 100 without returning an error, and THE PagedResponse SHALL report the effective capped size of 100 in its pagination metadata.
9. WHEN a list request is received and no Entity_Property_Mappings match the applied filter, THE Mapping_Controller SHALL return HTTP `200 OK` with an empty `content` array and `totalElements` of 0.

### Requirement 7: Get Entity Property Mapping By Id

**User Story:** As an administrator, I want to fetch a single entity property mapping by id, so that I can view its full details.

#### Acceptance Criteria

1. THE Mapping_Controller SHALL expose a GET endpoint at `/api/v1/entity-property-mappings/{id}` that returns an EntityPropertyMappingResponse with HTTP `200 OK`.
2. WHEN a get-by-id request references an existing Entity_Property_Mapping, THE Mapping_Service SHALL return the Entity_Property_Mapping.
3. IF a get-by-id request references a non-existent Entity_Property_Mapping id, THEN THE API SHALL return a `404 Not Found` response.
4. IF a get-by-id request references an Entity_Property_Mapping id that is not a valid UUID, THEN THE API SHALL return a `400 Bad Request` response.

### Requirement 8: Update Entity Property Mapping (Partial)

**User Story:** As an administrator, I want to partially update an entity property mapping, so that I can correct or revise a mapping without recreating it.

#### Acceptance Criteria

1. THE Mapping_Controller SHALL expose a PATCH endpoint at `/api/v1/entity-property-mappings/{id}` that accepts an UpdateEntityPropertyMappingRequest body and returns an EntityPropertyMappingResponse with HTTP `200 OK`.
2. THE UpdateEntityPropertyMappingRequest SHALL contain the following optional (nullable) fields: `entityType`, `jsonProperty`, and `placeholder`.
3. WHEN an update request includes a non-null field value, THE Mapping_Service SHALL update the corresponding field on the Entity_Property_Mapping.
4. WHEN an update request includes a null field value, THE Mapping_Service SHALL leave the corresponding field on the Entity_Property_Mapping unchanged.
5. IF an update request supplies a `placeholder` value that does not match the Kebab_Case_Pattern `^[a-z0-9]+(-[a-z0-9]+)*$`, THEN THE API SHALL return a `400 Bad Request` response that identifies the `placeholder` field as the failed field.
6. IF an update request supplies an `entityType` value that is not a defined Entity_Type value, THEN THE API SHALL return a `400 Bad Request` response that identifies the `entityType` field as the failed field.
7. IF an update request references a non-existent Entity_Property_Mapping id, THEN THE API SHALL return a `404 Not Found` response.
8. IF an update request references an Entity_Property_Mapping id that is not a valid UUID, THEN THE API SHALL return a `400 Bad Request` response.
9. IF an update request changes `entityType` or `placeholder` such that the resulting `(entityType, placeholder)` combination already exists on a different Entity_Property_Mapping, THEN THE Mapping_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.
10. WHEN an Entity_Property_Mapping is updated, THE Mapping_Service SHALL refresh the Entity_Property_Mapping's `updatedAt` field to the current timestamp.

### Requirement 9: Delete Entity Property Mapping

**User Story:** As an administrator, I want to delete an entity property mapping, so that mappings that are no longer needed can be removed.

#### Acceptance Criteria

1. THE Mapping_Controller SHALL expose a DELETE endpoint at `/api/v1/entity-property-mappings/{id}`.
2. WHEN a delete request references an existing Entity_Property_Mapping, THE Mapping_Service SHALL remove the Entity_Property_Mapping and THE API SHALL return an HTTP `204 No Content` response.
3. IF a delete request references a non-existent Entity_Property_Mapping id, THEN THE API SHALL return a `404 Not Found` response.
4. IF a delete request references an Entity_Property_Mapping id that is not a valid UUID, THEN THE API SHALL return a `400 Bad Request` response.

### Requirement 10: Entity Property Mapping Response Structure

**User Story:** As an API consumer, I want the mapping response to include all mapping fields, so that I can display complete mapping details in the UI.

#### Acceptance Criteria

1. THE EntityPropertyMappingResponse SHALL contain the following fields: `id` (UUID), `entityType` (Entity_Type), `jsonProperty` (String), `placeholder` (String), `createdAt` (Instant), and `updatedAt` (Instant).
2. WHEN an Entity_Property_Mapping entity is mapped to an EntityPropertyMappingResponse, THE EntityPropertyMappingResponse SHALL populate each response field with the value of the identically-named Entity_Property_Mapping entity field via a static `from` mapper.

### Requirement 11: Placeholder Format Enforcement

**User Story:** As an administrator, I want placeholder values to be enforced as kebab-case, so that placeholders are consistent and valid for later template substitution.

#### Acceptance Criteria

1. THE Mapping_Service SHALL accept a `placeholder` value only when the value matches the Kebab_Case_Pattern `^[a-z0-9]+(-[a-z0-9]+)*$`.
2. WHEN an Entity_Property_Mapping is persisted, THE Mapping_Service SHALL store the `placeholder` value as the inner placeholder name only, without the surrounding `{{` and `}}` delimiters.
3. IF a create or update request supplies a `placeholder` value that does not match the Kebab_Case_Pattern, THEN THE API SHALL return a `400 Bad Request` response that identifies the `placeholder` field as the failed field.

### Requirement 12: Entity Type and Placeholder Uniqueness

**User Story:** As an administrator, I want each entity-type-and-placeholder combination to be unique, so that a placeholder resolves to exactly one mapping for a given entity type while still allowing many placeholders to source the same property.

#### Acceptance Criteria

1. THE Mapping_Service SHALL enforce that an Entity_Property_Mapping's `(entityType, placeholder)` combination is unique within the Uniqueness_Scope.
2. THE Mapping_Service SHALL permit multiple Entity_Property_Mappings to reference the same `jsonProperty` value, including within the same `entityType`.
3. IF a create request or an update request would produce a second Entity_Property_Mapping with the same `(entityType, placeholder)` combination, THEN THE Mapping_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.
4. THE Entity_Property_Mapping persistence schema SHALL enforce the `(entityType, placeholder)` uniqueness with a database-level unique constraint on the `entity_type` and `placeholder` columns.
5. THE Mapping_Service SHALL perform a service-level uniqueness check before persistence so that a ConflictException with a clean error message is produced in addition to the database-level constraint.

### Requirement 13: Authorization

**User Story:** As a system administrator, I want entity property mapping endpoints to be restricted to administrators, so that only administrators can create, read, update, or delete mappings.

#### Acceptance Criteria

1. THE create, list, get-by-id, update, and delete endpoints SHALL permit the caller only when the caller has the ADMIN role.
2. IF a request is made to any Entity_Property_Mapping endpoint without a valid JWT_Token, THEN THE API SHALL return a `401 Unauthorized` response with a body indicating the token is invalid or missing.
3. IF an authenticated caller holds the OWNER role and does not hold the ADMIN role, THEN THE API SHALL return a `403 Forbidden` response with a body indicating insufficient permissions.
4. IF an authenticated caller holds neither the ADMIN role nor any other role granting access, THEN THE API SHALL return a `403 Forbidden` response with a body indicating insufficient permissions.
5. THE API SHALL evaluate authorization rules before executing endpoint business logic, so that an unauthorized caller receives a `403 Forbidden` response regardless of whether the referenced Entity_Property_Mapping exists.

### Requirement 14: Property Availability Scope Boundary

**User Story:** As a template author, I want to reference only properties that have a defined mapping, so that personalization relies on an explicit mapping table rather than ad hoc property names, while understanding that mapping existence is the only guarantee this feature provides.

#### Acceptance Criteria

1. THE Mapping_Service SHALL treat "a property is available" as meaning only that an Entity_Property_Mapping row exists for the corresponding `(entityType, placeholder)` combination.
2. THE Mapping_Service SHALL NOT validate that a mapping's `jsonProperty` value corresponds to an actual property on the upstream entity's real response shape.
3. WHEN a create or update request supplies a `jsonProperty` value that does not exist on the upstream entity, THE Mapping_Service SHALL still persist the Entity_Property_Mapping, because detection of such a mismatch is out of scope and surfaces later at render time in a future spec.

## External Assumptions (Out of Scope for storage-manager-communications)

The following are relied upon as cross-repository context but are NOT owned or changed by this spec.

- **A1 — Upstream Lead endpoint and schema:** The upstream Lead entity and its read endpoint `GET /api/v1/leads/{id}` are owned by `storage-manager-api` (the `lead-api` spec). Mapping `jsonProperty` values (for example `firstName`, `email`, `address.city`) will eventually reference fields of that Lead response, but this spec does not read, call, or validate against that endpoint or schema. The Lead field vocabulary is referenced only as context for authoring mappings.
- **A2 — Endpoint routing configuration:** Any gateway, service-to-service routing, or endpoint wiring that connects a future template-rendering flow to the upstream Lead endpoint is owned by platform/producer configuration and future specs. This spec exposes only the CRUD endpoints for the mapping table and performs no outbound routing.
- **A3 — Future template-authoring validation spec:** A separate future spec will validate that placeholders referenced by a template correspond to defined Entity_Property_Mapping rows and, potentially, to real upstream properties. This spec owns only the mapping table and its CRUD; it does not validate template authoring and does not detect a typo in `jsonProperty`.
- **A4 — JWT issuance and role assignment:** Issuing JWTs and assigning the ADMIN/OWNER roles carried in a token are owned by the identity/auth infrastructure. This spec owns only the validation and authorization posture on its own endpoints via the existing `CustomJwtAuthenticationConverter`, `authz` (`AuthorizationService`), and `GlobalExceptionHandler` conventions.

## Open Decisions / Recommended Defaults (user may override)

- **D1 — Resource path naming:** Recommended default `/api/v1/entity-property-mappings`, to align with this service's existing `/api/v1/*` kebab-case plural-noun convention (for example `/api/v1/communication-templates`). The spec name is `entity-api`; confirm whether the resource path should instead read `/api/v1/entity-properties`, `/api/v1/property-mappings`, or another plural noun. Reflected in Requirements 4.1, 6.1, 7.1, 8.1, and 9.1.
- **D2 — Delete success status:** Recommended default HTTP `204 No Content` for a successful delete (Requirement 9.2). Confirm whether `200 OK` with a representation body is preferred to match any established delete convention elsewhere in the service.
