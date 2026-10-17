# Requirements Document

## Introduction

This document captures all requirements for the Communication Template feature in the Storage Manager Communications service (`storage-manager-communications`). A Communication Template holds reusable email and SMS content that staff can apply when messaging customers. Each Communication Template belongs to an Organization and MAY optionally belong to a specific Facility within that Organization. A Communication Template with a null `facilityId` is an organization-level template that applies across the Organization; a Communication Template with a non-null `facilityId` is a facility-level template scoped to one Facility.

> **Relocation note.** This feature was originally built in `storage-manager-api` and has been relocated to the new `storage-manager-communications` service so that the component that renders and sends messages owns the template store (a single source of truth for templates). The behavior contract below is preserved. Two environment-driven adaptations apply because the communications service does **not** own the Organization, Facility, or user-identity tables: (a) the caller identity is derived directly from the validated JWT claims rather than from a persisted `user_identities` row, and (b) Organization-existence and Facility-in-Organization database checks are not performed in this service (those tables live in `storage-manager-api`). These adaptations are called out inline where they alter an acceptance criterion.

The feature introduces a `CommunicationController` exposing CRUD endpoints to list Communication Templates (paginated), fetch a single Communication Template by id, create a Communication Template, and partially update a Communication Template. The design follows the Storage Manager conventions already used by `storage-manager-api` (`LeadController`/`LeadService`/`Lead`): a JPA entity, Jakarta Bean Validation request DTO records, response DTO records with a static `from` mapper, a `@Transactional` service, a Spring Data repository, custom exceptions handled by the `GlobalExceptionHandler`, and role-based access control via Spring Security `@PreAuthorize` expressions backed by the `authz` (`AuthorizationService`) bean. All list results use the `PagedResponse<T>` wrapper, identifiers are UUIDs, and the API path prefix is `/api/v1/...`.

Authorization distinguishes two authenticated roles. An `ADMIN` is a cross-organization role and MUST supply the target Organization as a request parameter when listing. An `OWNER` is scoped to a single Organization, and the Organization for an OWNER is derived from the JWT token rather than supplied as a parameter. Both the OWNER and ADMIN roles are permitted to create and update Communication Template content. When an OWNER targets a facility-level Communication Template, the Communication_Service additionally validates facility access using the existing `authz.canAccessFacility(facilityId)` authorization convention.

Each Communication Template name is unique within its scope. The uniqueness scope is the `(organizationId, facilityId)` pair, so an Org_Level_Template (null `facilityId`) is unique among org-level templates for its Organization, and a Facility_Level_Template is unique within its Organization and Facility combination. The same `templateName` MAY exist across different Facilities within the same Organization and MAY exist independently as an Org_Level_Template. Violations are reported as `409 Conflict` responses following the `ConflictException` convention handled by the `GlobalExceptionHandler`.

This feature additionally associates each Communication Template with an optional entity type and validates the placeholders embedded in template content against the placeholders defined for that entity type. A Communication Template carries a single, nullable `entityType` field drawn from the Entity_Type enumeration owned by the `entity-api` feature in this same service. Template content (both `emailContent` and `smsContent`) may embed Placeholders using the `{{inner-name}}` syntax, where each inner name is expected to resolve to an Entity_Property_Mapping Placeholder for the template's entity type. The Communication_Service validates these Placeholders at write time (on both create and update, against the effective post-apply state of the Communication Template): when the effective `entityType` is non-null, every `{{...}}` occurrence across both content fields MUST resolve to a defined Entity_Property_Mapping Placeholder for that entity type; when the effective `entityType` is null, the content MUST contain no `{{...}}` occurrences at all. Placeholder validation reads the global Entity_Property_Mapping set and is independent of the template's org/facility scope. Validation is write-time only: existing Communication Templates are not re-validated when Entity_Property_Mappings are later added or removed.

## Glossary

- **Communication_Template**: A JPA entity representing reusable email and SMS content, mapped to the `communication_templates` database table, scoped to an Organization and optionally to a Facility.
- **Communication_Service**: The Spring service class (`CommunicationService`) responsible for Communication Template CRUD and authorization business logic.
- **Communication_Controller**: The Spring REST controller (`CommunicationController`) exposing Communication Template endpoints under `/api/v1`.
- **CreateCommunicationTemplateRequest**: The request DTO record used when creating a new Communication Template, validated with Jakarta Bean Validation.
- **UpdateCommunicationTemplateRequest**: The request DTO record used when partially updating an existing Communication Template via PATCH; all fields are optional (nullable).
- **CommunicationTemplateResponse**: The response DTO record returned from Communication Template API endpoints.
- **Organization**: A tenant-of-the-platform concept owned by `storage-manager-api`; every Communication Template belongs to exactly one Organization identified by `organizationId`. The communications service references `organizationId` as a scope value and does not persist Organization rows.
- **Facility**: A physical storage property belonging to an Organization; a Communication Template MAY optionally belong to exactly one Facility within its Organization. The communications service references `facilityId` as a scope value and does not persist Facility rows.
- **Org_Level_Template**: A Communication Template whose `facilityId` is null, applying at the Organization level.
- **Facility_Level_Template**: A Communication Template whose `facilityId` is non-null, applying at a single Facility within the Organization.
- **API**: The Storage Manager Communications REST API.
- **Authz**: The `AuthorizationService` Spring bean (registered as `authz`) that evaluates role-based access rules in `@PreAuthorize` expressions and exposes the current user identity derived from the JWT token.
- **OWNER**: A `UserRole` scoped to a single Organization, whose Organization is derived from the JWT token.
- **ADMIN**: A cross-organization `UserRole`.
- **PagedResponse**: A paginated response wrapper containing `content`, `page`, `totalPages`, and `totalElements` fields.
- **JWT_Token**: The authentication token presented by a caller, from which an OWNER's `organizationId` and the caller's role are derived.
- **Uniqueness_Scope**: The `(organizationId, facilityId)` pair within which a Communication_Template's `templateName` MUST be unique; an Org_Level_Template uses the scope `(organizationId, null)` and a Facility_Level_Template uses the scope `(organizationId, facilityId)`.
- **ConflictException**: The API exception, handled by the `GlobalExceptionHandler` to produce a `409 Conflict` response in the standard error response format, used for duplicate-resource violations.
- **Entity_Type**: The enumeration (`com.storagemanager.communications.model.EntityType`) identifying the upstream domain entity a Communication_Template is associated with. Owned by the `entity-api` feature in this service; the predefined set currently contains exactly one value, `LEAD`. A Communication_Template references a single, nullable Entity_Type value.
- **Placeholder**: An inner name embedded in Communication_Template content using the Placeholder_Syntax, intended to be substituted later with a value from the associated entity. The inner name corresponds to an Entity_Property_Mapping Placeholder (kebab-case, matching `^[a-z0-9]+(-[a-z0-9]+)*$`) for a given Entity_Type.
- **Entity_Property_Mapping**: A row owned by the `entity-api` feature in this service that associates an Entity_Type and an upstream JSON property with a Placeholder inner name, keyed by the `(entityType, placeholder)` pair. Entity_Property_Mappings are global and are referenced here only to validate Communication_Template Placeholders; this feature does not create, modify, or delete them.
- **Placeholder_Syntax**: The double-curly-brace delimiter pair `{{...}}` used to embed a Placeholder within Communication_Template content. The presence of a `{{...}}` delimiter pair is what triggers Placeholder validation; the inner name between the delimiters is the Entity_Property_Mapping Placeholder, and there is no supported way to include a literal unescaped `{{...}}` that is not treated as a Placeholder.

## Requirements

### Requirement 1: Communication Template Entity

**User Story:** As a developer, I want a Communication Template entity that persists reusable email and SMS content scoped to an organization and optionally a facility, so that templates can be stored, queried, and maintained.

#### Acceptance Criteria

1. THE Communication_Template entity SHALL be mapped to a database table named `communication_templates`.
2. THE Communication_Template entity SHALL include an `id` field of type UUID generated by the persistence provider as the primary key.
3. THE Communication_Template entity SHALL include an `organizationId` field of type UUID mapped to a NOT NULL column identifying the Organization the Communication Template belongs to.
4. THE Communication_Template entity SHALL include a `facilityId` field of type UUID mapped to a nullable column identifying the Facility the Communication Template belongs to, where a null value denotes an Org_Level_Template.
5. THE Communication_Template entity SHALL include a `templateName` field of type String mapped to a NOT NULL column.
6. THE Communication_Template entity SHALL include an `emailContent` field of type String mapped to a nullable column whose definition supports at least 100000 characters to accommodate long HTML content.
7. THE Communication_Template entity SHALL include an `smsContent` field of type String mapped to a nullable column whose definition supports at most 500 characters.
8. THE Communication_Template entity SHALL include a `createdAt` field of type Instant mapped to a NOT NULL, non-updatable column set on first persist.
9. THE Communication_Template entity SHALL include an `updatedAt` field of type Instant mapped to a NOT NULL column set on first persist and refreshed on each update.
10. THE Communication_Template persistence schema SHALL enforce a database-level uniqueness constraint on the combination of `organizationId`, `facilityId`, and `templateName` for Facility_Level_Templates.
11. WHERE the database treats NULL values as distinct within unique constraints, THE Communication_Template persistence schema SHALL enforce org-level uniqueness of `templateName` among Org_Level_Templates (rows where `facilityId` is null) for a given `organizationId` using a partial unique index, and THE Communication_Service SHALL also enforce the same uniqueness at the service level so that a clean error message is produced.
12. THE Communication_Template entity SHALL include an `entityType` field of type Entity_Type persisted as a String via `EnumType.STRING`, mapped to a nullable column, where a null value denotes a Communication_Template not associated with any Entity_Type.
13. THE Communication_Template persistence schema SHALL add the `entityType` field as a nullable `entity_type VARCHAR(64)` column on the `communication_templates` table via a Flyway migration numbered `V4` within this service's linear history (continuing after `V1` communication_templates, `V2` messaging_inbox_outbox, and `V3` entity_property_mappings).

### Requirement 2: Create Communication Template

**User Story:** As an owner or administrator, I want to create a communication template, so that reusable email and SMS content is available for messaging customers.

#### Acceptance Criteria

1. THE Communication_Controller SHALL expose a POST endpoint at `/api/v1/communication-templates` that accepts a CreateCommunicationTemplateRequest body and returns a CommunicationTemplateResponse with HTTP `201 Created`.
2. WHEN a create request is received with all required fields present and valid, THE Communication_Service SHALL persist a new Communication_Template and return the created Communication_Template.
3. WHEN a create request is received from a caller with the OWNER role, THE Communication_Service SHALL set the Communication_Template's `organizationId` to the `organizationId` derived from the OWNER's JWT_Token.
4. WHEN a create request is received from a caller with the ADMIN role, THE Communication_Service SHALL set the Communication_Template's `organizationId` to the `organizationId` supplied in the CreateCommunicationTemplateRequest.
5. IF a create request from a caller with the ADMIN role omits the `organizationId` field, THEN THE API SHALL return a `400 Bad Request` response that identifies the `organizationId` field as the failed field.
6. IF a create request from a caller with the OWNER role supplies an `organizationId` that does not equal the `organizationId` derived from the OWNER's JWT_Token, THEN THE API SHALL return a `400 Bad Request` response that identifies the `organizationId` field as the failed field.
7. WHEN a create request supplies a non-null `facilityId` and the caller has the OWNER role, THE Communication_Service SHALL validate facility access via the `authz.canAccessFacility(facilityId)` authorization convention. (Relocation adaptation: this service does not own the Organization or Facility tables, so Organization-existence and Facility-in-Organization database verification — originally returning `404` — are NOT performed here; the `organizationId`/`facilityId` are recorded as the template's scope. A future service-to-service read to `storage-manager-api` MAY reintroduce those existence checks.)
8. WHEN a create request omits the `facilityId` field, THE Communication_Service SHALL persist the Communication_Template as an Org_Level_Template with `facilityId` set to null.
9. IF a create request from a caller with the OWNER role supplies a non-null `facilityId` for a Facility the caller may not access, as evaluated by the `authz.canAccessFacility(facilityId)` authorization convention, THEN THE API SHALL return a `403 Forbidden` response whose body matches the standard error response format.
10. IF a create request would persist a Facility_Level_Template whose `templateName` already exists within the same Uniqueness_Scope of `(organizationId, facilityId)`, THEN THE Communication_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.
11. IF a create request would persist an Org_Level_Template whose `templateName` already exists among Org_Level_Templates (rows where `facilityId` is null) for the same `organizationId`, THEN THE Communication_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.

### Requirement 3: Create Communication Template Request Validation

**User Story:** As an API consumer, I want the create endpoint to validate fields, so that invalid template data is rejected before persistence.

#### Acceptance Criteria

1. THE CreateCommunicationTemplateRequest SHALL require a non-blank `templateName` field of type String.
2. THE CreateCommunicationTemplateRequest SHALL accept an optional `organizationId` field of type UUID that is required only when the caller has the ADMIN role.
3. THE CreateCommunicationTemplateRequest SHALL accept an optional `facilityId` field of type UUID.
4. THE CreateCommunicationTemplateRequest SHALL accept an optional `emailContent` field of type String.
5. THE CreateCommunicationTemplateRequest SHALL accept an optional `smsContent` field of type String.
6. IF a create request supplies an `smsContent` value that exceeds 500 characters, THEN THE API SHALL return a `400 Bad Request` response that identifies the `smsContent` field as the failed field.
7. WHEN a create request is submitted with the `templateName` field null or blank, THE API SHALL return a `400 Bad Request` response containing a message that identifies the `templateName` field.
8. WHEN a create request is submitted with a malformed request body that cannot be deserialized, THE API SHALL return a `400 Bad Request` response containing a message indicating the body is malformed.
9. THE CreateCommunicationTemplateRequest SHALL accept an optional (nullable) `entityType` field of type Entity_Type that associates the Communication_Template with a single Entity_Type.
10. IF a create request supplies an `entityType` value that is not a defined Entity_Type value, THEN THE API SHALL return a `400 Bad Request` response that identifies the `entityType` field as the failed field.
11. WHEN a create request is received, THE Communication_Service SHALL validate the Placeholders embedded in the Communication_Template's `emailContent` and `smsContent` as defined in Requirement 11 (Template Placeholder Validation), and SHALL return a `400 Bad Request` response when that validation fails.

### Requirement 4: List Communication Templates

**User Story:** As an owner or administrator, I want to list communication templates, so that I can review the templates available for an organization or facility.

#### Acceptance Criteria

1. THE Communication_Controller SHALL expose a GET endpoint at `/api/v1/communication-templates` that returns a PagedResponse of CommunicationTemplateResponse objects with HTTP `200 OK`.
2. WHEN a list request is received from a caller with the OWNER role, THE Communication_Service SHALL return only Communication_Templates whose `organizationId` equals the `organizationId` derived from the OWNER's JWT_Token.
3. WHEN a list request is received from a caller with the ADMIN role and an `organizationId` request parameter is supplied, THE Communication_Service SHALL return only Communication_Templates whose `organizationId` equals the supplied `organizationId` request parameter.
4. IF a list request is received from a caller with the ADMIN role without an `organizationId` request parameter, THEN THE API SHALL return a `400 Bad Request` response that identifies the `organizationId` parameter as required for ADMIN callers.
5. THE list endpoint SHALL accept optional `page` (default 0, minimum 0) and `size` (default 20, minimum 1) query parameters.
6. WHEN a list request is received, THE Communication_Service SHALL return Communication_Templates ordered by creation time descending within the PagedResponse, including `content`, `page`, `totalPages`, and `totalElements` fields.
7. IF the `size` parameter exceeds 100, THEN THE Communication_Controller SHALL cap the page size at 100 without returning an error, and THE PagedResponse SHALL report the effective capped size of 100 in its pagination metadata.
8. WHEN a list request is received and no Communication_Templates match the applied scope, THE Communication_Controller SHALL return HTTP `200 OK` with an empty `content` array and `totalElements` of 0.

### Requirement 5: Get Communication Template By Id

**User Story:** As an owner or administrator, I want to fetch a single communication template by id, so that I can view its full email and SMS content.

#### Acceptance Criteria

1. THE Communication_Controller SHALL expose a GET endpoint at `/api/v1/communication-templates/{id}` that returns a CommunicationTemplateResponse with HTTP `200 OK`.
2. WHEN a get-by-id request from a caller with the ADMIN role references an existing Communication_Template, THE Communication_Service SHALL return the Communication_Template regardless of its `organizationId`, and THE get-by-id endpoint SHALL NOT require an `organizationId` request parameter.
3. WHEN a get-by-id request from a caller with the OWNER role references an existing Communication_Template whose `organizationId` equals the `organizationId` derived from the OWNER's JWT_Token, THE Communication_Service SHALL return the Communication_Template.
4. IF a get-by-id request references a non-existent Communication_Template id, THEN THE API SHALL return a `404 Not Found` response.
5. IF a get-by-id request references a Communication_Template id that is not a valid UUID, THEN THE API SHALL return a `400 Bad Request` response.
6. IF a caller with the OWNER role requests a Communication_Template whose `organizationId` does not equal the `organizationId` derived from the OWNER's JWT_Token, THEN THE API SHALL return an error response whose body matches the standard error response format used by other error responses in the API.

### Requirement 6: Update Communication Template (Partial)

**User Story:** As an owner or administrator, I want to partially update a communication template's content, so that I can correct or revise email and SMS content without recreating the template.

#### Acceptance Criteria

1. THE Communication_Controller SHALL expose a PATCH endpoint at `/api/v1/communication-templates/{id}` that accepts an UpdateCommunicationTemplateRequest body and returns a CommunicationTemplateResponse with HTTP `200 OK`.
2. THE UpdateCommunicationTemplateRequest SHALL contain the following optional (nullable) fields: `templateName`, `emailContent`, and `smsContent`.
3. WHEN an update request includes a non-null field value, THE Communication_Service SHALL update the corresponding field on the Communication_Template.
4. WHEN an update request includes a null field value, THE Communication_Service SHALL leave the corresponding field on the Communication_Template unchanged.
5. IF an update request supplies an `smsContent` value that exceeds 500 characters, THEN THE API SHALL return a `400 Bad Request` response that identifies the `smsContent` field as the failed field.
6. IF an update request supplies a `templateName` value that is blank, THEN THE API SHALL return a `400 Bad Request` response that identifies the `templateName` field as the failed field.
7. WHEN an update request violates more than one field validation constraint, THE API SHALL return a single `400 Bad Request` response that identifies every field that failed validation.
8. IF an update request references a non-existent Communication_Template id, THEN THE API SHALL return a `404 Not Found` response.
9. IF an update request references a Communication_Template id that is not a valid UUID, THEN THE API SHALL return a `400 Bad Request` response.
10. WHEN a Communication_Template is updated, THE Communication_Service SHALL refresh the Communication_Template's `updatedAt` field to the current timestamp.
11. IF an update request from a caller with the OWNER role targets a Facility_Level_Template whose `facilityId` identifies a Facility the caller may not access, as evaluated by the `authz.canAccessFacility(facilityId)` authorization convention, THEN THE API SHALL return a `403 Forbidden` response whose body matches the standard error response format.
12. IF an update request supplies a non-null `templateName` that differs from the Communication_Template's current `templateName` and already exists within the Communication_Template's existing Uniqueness_Scope of `(organizationId, facilityId)`, THEN THE Communication_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.
13. THE UpdateCommunicationTemplateRequest SHALL accept an optional (nullable) `entityType` field of type Entity_Type.
14. WHEN an update request includes a non-null `entityType` value, THE Communication_Service SHALL update the Communication_Template's `entityType` field to the supplied value.
15. WHEN an update request includes a null `entityType` value, THE Communication_Service SHALL leave the Communication_Template's `entityType` field unchanged, consistent with the existing partial-update semantics; consequently clearing an `entityType` back to null is not supported in this iteration unless a distinct sentinel is introduced in a future spec.
16. IF an update request supplies an `entityType` value that is not a defined Entity_Type value, THEN THE API SHALL return a `400 Bad Request` response that identifies the `entityType` field as the failed field.
17. WHEN an update request is received, THE Communication_Service SHALL validate the Placeholders embedded in the Communication_Template's effective (post-apply) `emailContent` and `smsContent` against the Communication_Template's effective (post-apply) `entityType` as defined in Requirement 11 (Template Placeholder Validation), and SHALL return a `400 Bad Request` response when that validation fails.

### Requirement 7: Communication Template Response Structure

**User Story:** As an API consumer, I want the communication template response to include all template fields, so that I can display complete template details in the UI.

#### Acceptance Criteria

1. THE CommunicationTemplateResponse SHALL contain the following fields: `id` (UUID), `organizationId` (UUID), `facilityId` (UUID, nullable), `templateName` (String), `emailContent` (String, nullable), `smsContent` (String, nullable), `createdAt` (Instant), and `updatedAt` (Instant).
2. WHEN a Communication_Template entity is mapped to a CommunicationTemplateResponse, THE CommunicationTemplateResponse SHALL populate each response field with the value of the identically-named Communication_Template entity field, preserving null values for nullable fields.
3. THE CommunicationTemplateResponse SHALL contain an `entityType` field of type Entity_Type that is nullable, populated from the identically-named Communication_Template entity field and preserving a null value when the Communication_Template is not associated with any Entity_Type.

### Requirement 8: Authorization

**User Story:** As a system administrator, I want communication template endpoints to enforce role-based and organization-scoped access control, so that only authorized users can list, read, create, or update templates.

#### Acceptance Criteria

1. THE list, get-by-id, create, and update endpoints SHALL permit the caller when the caller has the ADMIN role or the OWNER role.
2. WHEN a caller with the OWNER role accesses the list, get-by-id, create, or update endpoint, THE Communication_Service SHALL scope the operation to the `organizationId` derived from the OWNER's JWT_Token.
3. WHEN a caller with the ADMIN role accesses the list endpoint, THE Communication_Controller SHALL require the Organization to be supplied as the `organizationId` request parameter, and SHALL NOT require that parameter on the get-by-id endpoint.
4. THE Communication_Service SHALL permit the create and update operations for both the OWNER role and the ADMIN role.
5. IF a request is made to any Communication_Template endpoint without a valid JWT_Token, THEN THE API SHALL return a `401 Unauthorized` response with a body indicating the token is invalid or missing.
6. IF an authenticated caller lacks both the ADMIN role and the OWNER role, THEN THE API SHALL return a `403 Forbidden` response with a body indicating insufficient permissions.
7. THE API SHALL evaluate authorization rules before executing endpoint business logic, so that an unauthorized caller receives a `403 Forbidden` response regardless of whether the referenced Communication_Template exists.

### Requirement 9: Template Name Uniqueness

**User Story:** As an owner or administrator, I want communication template names to be unique within their organization and facility scope, so that staff are not confused by duplicate template names when messaging customers.

#### Acceptance Criteria

1. THE Communication_Service SHALL enforce that a Communication_Template's `templateName` is unique within its Uniqueness_Scope of `(organizationId, facilityId)`.
2. THE Communication_Service SHALL permit the same `templateName` to exist across different Facilities within the same Organization, where each Facility_Level_Template occupies a distinct Uniqueness_Scope of `(organizationId, facilityId)`.
3. THE Communication_Service SHALL permit an Org_Level_Template and a Facility_Level_Template within the same Organization to share the same `templateName`, where the Org_Level_Template occupies the scope `(organizationId, null)` and the Facility_Level_Template occupies the scope `(organizationId, facilityId)`.
4. WHILE at most one Org_Level_Template with a given `templateName` exists for an Organization, THE Communication_Service SHALL reject a request that would create a second Org_Level_Template with the same `templateName` for that Organization.
5. WHILE at most one Facility_Level_Template with a given `templateName` exists for an `(organizationId, facilityId)` pair, THE Communication_Service SHALL reject a request that would create a second Facility_Level_Template with the same `templateName` for that pair.
6. IF a create or update request would violate the uniqueness defined in this requirement, THEN THE Communication_Service SHALL raise a ConflictException and THE API SHALL return a `409 Conflict` response whose body matches the standard error response format.
7. THE Communication_Template persistence schema SHALL enforce the Facility_Level_Template uniqueness defined in this requirement with a database-level unique constraint on `(organizationId, facilityId, templateName)`.
8. WHERE the database treats NULL values as distinct within unique constraints, THE Communication_Template persistence schema SHALL enforce Org_Level_Template uniqueness with a partial unique index covering rows where `facilityId` is null, and THE Communication_Service SHALL perform an equivalent service-level uniqueness check so that a ConflictException with a clean error message is produced before persistence.

### Requirement 10: Owner Facility Access Validation

**User Story:** As a system administrator, I want owners to be allowed to create or update facility-level templates only for facilities they may access, so that an owner cannot manage templates for facilities outside their organization.

#### Acceptance Criteria

1. WHEN a caller with the OWNER role submits a create request that supplies a non-null `facilityId`, THE Communication_Service SHALL validate facility access using the `authz.canAccessFacility(facilityId)` authorization convention.
2. WHEN a caller with the OWNER role submits an update request that targets a Facility_Level_Template, THE Communication_Service SHALL validate that the Communication_Template's `facilityId` identifies a Facility the caller may access using the `authz.canAccessFacility(facilityId)` authorization convention.
3. IF a caller with the OWNER role targets a Facility the caller may not access, THEN THE API SHALL return a `403 Forbidden` response whose body matches the standard error response format.

### Requirement 11: Template Placeholder Validation

**User Story:** As a template author, I want the Placeholders I embed in template content validated against the entity type the template is associated with, so that a template never references a Placeholder that cannot be resolved for its entity type.

#### Acceptance Criteria

1. WHEN the Communication_Service validates a Communication_Template, THE Communication_Service SHALL extract every `{{...}}` occurrence from both the `emailContent` and the `smsContent` fields, where an occurrence is matched as the literal `{{` delimiter, followed by inner content, followed by the literal `}}` delimiter, and SHALL derive each occurrence's inner name by removing all leading and trailing ASCII whitespace from the inner content, treating that trimmed inner name as a Placeholder, and SHALL treat a null `emailContent` field or a null `smsContent` field as contributing zero occurrences without producing an error.
2. WHILE a Communication_Template's effective (post-apply) `entityType` is non-null, THE Communication_Service SHALL require every extracted Placeholder inner name to exist as an Entity_Property_Mapping Placeholder for that `entityType`, and SHALL treat the presence of zero `{{...}}` occurrences as satisfying this requirement with no Placeholder to validate.
3. IF a Communication_Template's effective (post-apply) `entityType` is non-null and at least one extracted Placeholder inner name does not exist as an Entity_Property_Mapping Placeholder for that `entityType`, THEN THE API SHALL return a `400 Bad Request` response whose message names each distinct offending Placeholder inner name.
4. IF a Communication_Template's effective (post-apply) `entityType` is null and at least one `{{...}}` occurrence is present in the `emailContent` or the `smsContent`, THEN THE API SHALL return a `400 Bad Request` response whose message indicates that Placeholders are not permitted when no `entityType` is associated.
5. THE Communication_Service SHALL treat the presence of a `{{...}}` delimiter pair as the trigger for Placeholder validation, so that any string matching the Placeholder_Syntax MUST resolve to a valid Entity_Property_Mapping Placeholder for the effective `entityType`, and there is no supported way to include a literal unescaped `{{...}}` that bypasses this validation.
6. THE Communication_Service SHALL perform Placeholder validation at write time on both the create operation and the update operation, evaluating the Communication_Template's effective (post-apply) `emailContent`, `smsContent`, and `entityType` after applying the request.
7. THE Communication_Service SHALL evaluate extracted Placeholders against the global Entity_Property_Mapping set keyed by `(entityType, placeholder)`, independent of the Communication_Template's `organizationId` and `facilityId` scope.
8. THE Communication_Service SHALL perform Placeholder validation only at write time, and SHALL NOT re-validate an existing Communication_Template when Entity_Property_Mappings are later added or removed, so that a previously-valid Communication_Template MAY reference a now-unknown Placeholder after a mapping is deleted; such re-validation is out of scope for this feature.
9. IF a `{{...}}` occurrence's inner name, after removing all leading and trailing ASCII whitespace, is empty or does not match the Placeholder_Syntax inner-name form `^[a-z0-9]+(-[a-z0-9]+)*$`, THEN THE API SHALL return a `400 Bad Request` response whose message names each distinct offending inner name as an invalid Placeholder.
