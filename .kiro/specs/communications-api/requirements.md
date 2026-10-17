# Requirements Document

## Introduction

This feature adds a **send-and-log** capability to the storage-manager-communications service. When invoked, the service sends a communication to a recipient over email or SMS, and records an immutable audit entry of what was sent. The actual third-party delivery (email provider, SMS provider) is **stubbed/no-op in this spec** — nothing is transmitted to any external vendor. The focus is the send-service seam, the sent-message audit entity and its persistence, the Flyway migration, request validation (exactly one of phone/email), and a REST controller (`CommunicationsController`) exposing a send endpoint plus read endpoints to retrieve the logged communications (by id and as a filterable, paginated list). All endpoints are rooted at `/api/v1/sent-communications`.

This spec mirrors the existing storage-manager-communications conventions established by the `entity-api` and `messaging` specs: JPA entity with a UUID id and `@PrePersist`/`@PreUpdate` timestamps, `EnumType.STRING` enums, Jakarta Bean Validation request DTO records, response DTO records with a static `from` mapper, a `@Transactional` service, a Spring Data repository, custom exceptions handled by `GlobalExceptionHandler`, role-based access via `@PreAuthorize` backed by the `authz` (`AuthorizationService`) bean, the `PagedResponse<T>` list wrapper, and the standard `ErrorResponse` format.

> **Naming note:** The controller class is named `CommunicationsController` (plural), rooted at `/api/v1/sent-communications`. The root path `/api/v1/communications` is already owned by the messaging `InboundEventController` (the event ingest endpoint), and `/api/v1/communication-templates` by the singular `CommunicationController`; `/api/v1/sent-communications` is distinct from both and does not collide.

## Glossary

- **Communications_Service**: The storage-manager-communications Spring Boot application that owns the send-and-log behavior defined in this spec.
- **Send_Service**: The `@Transactional` application service that performs a send operation: it invokes the delivery provider seam and persists a Sent_Communication audit entry.
- **Delivery_Provider**: The provider-interface seam abstracting third-party email/SMS delivery. In this spec every implementation is a stub that performs no external transmission and reports success.
- **Sent_Communication**: The JPA entity and database row representing one logged (audited) communication send attempt and its outcome.
- **Communications_Controller**: The `CommunicationsController` REST controller exposing the send endpoint and the read (by-id and list) endpoints.
- **Message_Type**: An `EnumType.STRING` enumeration of the delivery channel of a communication: `EMAIL` or `SMS`.
- **Send_Status**: An `EnumType.STRING` enumeration of the outcome state of a Sent_Communication. In this spec the only value is `DISPATCHED`, which is the default.
- **Recipient_Type**: An `EnumType.STRING` enumeration identifying the recipient role of a communication: `LEAD` or `OWNER`.
- **Authz**: The `AuthorizationService` bean (registered as `@Service("authz")`) whose predicates back the `@PreAuthorize` expressions, matching the existing service convention.
- **Paged_Response**: The existing `PagedResponse<T>` wrapper record (`content`, `page`, `totalPages`, `totalElements`) used for all list results.
- **Error_Response**: The existing `ErrorResponse` record (`timestamp`, `status`, `error`, `message`) returned by `GlobalExceptionHandler` for error outcomes.
- **Public_Id**: A human-referenceable, system-generated public identifier string stored alongside the opaque UUID primary key.
- **Correlation_Id**: The identifier tracing a Sent_Communication back to the originating event/workflow (sourced from the inbound event envelope's correlation/event id), stored as a nullable UUID.
- **ADMIN**: A cross-organization `UserRole`. An ADMIN caller may access Sent_Communication entries across all organizations and facilities.
- **OWNER**: A `UserRole` scoped to a single Organization. An OWNER caller may access only Sent_Communication entries belonging to that caller's own organization (that is, their own facilities), consistent with the organization-scoping convention enforced by the existing API methods in this component.

## Requirements

### Requirement 1: Send a communication and log it

**User Story:** As the communications platform, I want to send a communication to a recipient and record an audit entry of what was sent, so that there is a durable log of every communication the system dispatched.

#### Acceptance Criteria

1. THE Communications_Controller SHALL expose the send endpoint as a `POST` at `/api/v1/sent-communications`.
2. WHEN a valid send request is received, THE Send_Service SHALL invoke the Delivery_Provider seam for the request's Message_Type exactly once and SHALL persist exactly one Sent_Communication entry recording the sent communication within a single transaction.
3. WHEN the Delivery_Provider seam is invoked in this spec, THE Delivery_Provider SHALL perform no external third-party transmission and SHALL report a successful dispatch outcome.
4. WHEN a Sent_Communication entry is persisted, THE Send_Service SHALL set the Send_Status to `DISPATCHED` as the default value.
5. WHEN a Sent_Communication entry is persisted, THE Send_Service SHALL record the facility id, the message content, the Message_Type, and exactly the single recipient contact value accepted by the recipient-contact validation of Requirement 2 (the recipient phone number WHEN Message_Type is `SMS`, or the recipient email address WHEN Message_Type is `EMAIL`), leaving the unused recipient-contact field null.
6. WHEN a Sent_Communication entry is persisted, THE Send_Service SHALL set the date-sent value exactly once to the instant the send operation completed, and that instant SHALL NOT be later than the instant the send request response is produced.
7. WHEN a send request completes successfully, THE Communications_Service SHALL respond with HTTP 201 and a response body that represents the persisted Sent_Communication, including its system-generated UUID id and Public_Id so that the created entry is retrievable via the retrieve-by-id endpoint.
8. IF persistence of the Sent_Communication entry fails after the Delivery_Provider seam is invoked, THEN THE Communications_Service SHALL roll back the transaction so that no partial Sent_Communication entry is persisted and SHALL respond with HTTP 500 and an Error_Response.

### Requirement 2: Recipient-contact validation (exactly one of phone/email)

**User Story:** As an operator, I want the service to reject malformed send requests, so that every logged communication has exactly one valid delivery address.

#### Acceptance Criteria

1. IF a send request supplies neither a phone number nor an email address (each treated as supplied only when present and non-blank after trimming), THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response indicating that exactly one recipient contact is required.
2. IF a send request supplies both a phone number and an email address (each present and non-blank after trimming), THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response indicating that only one recipient contact may be supplied.
3. WHEN a send request supplies a phone number (present and non-blank after trimming) and no email address, THE Communications_Service SHALL accept the request as having exactly one recipient contact.
4. WHEN a send request supplies an email address (present and non-blank after trimming) and no phone number, THE Communications_Service SHALL accept the request as having exactly one recipient contact.
5. IF a send request's Message_Type is `EMAIL` while the request supplies a phone number and no email address, THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response indicating the contact does not match the Message_Type channel.
6. IF a send request's Message_Type is `SMS` while the request supplies an email address and no phone number, THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response indicating the contact does not match the Message_Type channel.
7. IF a send request omits a required field (facility id, Message_Type, or message content), THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response naming the offending field.
8. IF a send request supplies a Message_Type value that is not a defined Message_Type enum value, THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response indicating the Message_Type value is invalid.
9. IF a send request supplies a recipient contact for its Message_Type channel whose format is malformed (an email address that is not a syntactically valid email, or a phone number that is not a syntactically valid phone number), THEN THE Communications_Service SHALL reject the request with HTTP 400 and an Error_Response naming the offending field.

### Requirement 3: Retrieve a logged communication by id

**User Story:** As an operator, I want to retrieve a single logged communication by its identifier, so that I can inspect what was sent for a specific audit entry.

#### Acceptance Criteria

1. THE Communications_Controller SHALL permit the GET-by-id endpoint only when the caller holds the ADMIN role or the OWNER role.
2. WHEN a GET request to `/api/v1/sent-communications/{id}` from an ADMIN caller, or from an OWNER caller whose organization owns the referenced Sent_Communication, supplies the id of an existing Sent_Communication, THE Communications_Controller SHALL respond with HTTP 200 and a response body representing that Sent_Communication's persisted audit fields.
3. IF a GET request supplies an id that matches no Sent_Communication, THEN THE Communications_Service SHALL respond with HTTP 404 and an Error_Response.
4. IF a GET request from an OWNER caller supplies the id of a Sent_Communication that exists but belongs to a different organization than the caller's, THEN THE Communications_Service SHALL respond with HTTP 403 and an Error_Response, consistent with the organization-scoping convention of the existing API methods in this component.
5. IF a GET request supplies an id that is not a syntactically valid UUID, THEN THE Communications_Service SHALL respond with HTTP 400 and an Error_Response.

### Requirement 4: List logged communications (paginated and filterable)

**User Story:** As an operator, I want to list logged communications filtered by facility and channel, so that I can review what communications were sent.

#### Acceptance Criteria

1. THE Communications_Controller SHALL permit the list endpoint only when the caller holds the ADMIN role or the OWNER role.
2. WHEN a GET list request to `/api/v1/sent-communications` is received, THE Communications_Controller SHALL respond with HTTP 200 and a Paged_Response of Sent_Communication entries ordered newest-first by date sent, and SHALL break ties between entries with equal date-sent values by ordering those entries by their id so the ordering is deterministic.
3. WHERE a GET list request supplies a facility id filter, THE Communications_Service SHALL restrict the result to Sent_Communication entries whose facility id equals the supplied value.
4. WHERE a GET list request supplies a Message_Type filter, THE Communications_Service SHALL restrict the result to Sent_Communication entries whose Message_Type equals the supplied value.
5. WHERE a GET list request is made by an OWNER caller, THE Communications_Service SHALL restrict the result to Sent_Communication entries belonging to that caller's own organization, consistent with the organization-scoping convention of the existing API methods in this component.
6. WHERE a GET list request is made by an ADMIN caller, THE Communications_Service SHALL return Sent_Communication entries across all organizations and facilities subject only to the supplied filters.
7. WHEN a GET list request supplies no page parameter, THE Communications_Controller SHALL default the page number to 0.
8. WHEN a GET list request supplies no size parameter, THE Communications_Controller SHALL default the page size to 20.
9. WHERE a GET list request supplies a page size greater than 100, THE Communications_Controller SHALL cap the applied page size at 100.
10. IF a GET list request supplies a page number less than 0 or a page size less than 1, THEN THE Communications_Controller SHALL respond with HTTP 400 and an Error_Response.
11. WHEN a GET list request matches no Sent_Communication entries, THE Communications_Controller SHALL respond with HTTP 200 and a Paged_Response whose content is empty, whose totalElements is 0, and whose totalPages is 0.
12. IF a GET list request supplies a Message_Type filter value that is not a defined Message_Type enum value, THEN THE Communications_Controller SHALL respond with HTTP 400 and an Error_Response indicating the filter value is invalid.
13. IF a GET list request supplies a facility id filter value that is not a valid UUID, THEN THE Communications_Controller SHALL respond with HTTP 400 and an Error_Response indicating the filter value is invalid.

### Requirement 5: Sent_Communication persistence and audit fields

**User Story:** As a compliance stakeholder, I want each logged communication to capture a defined set of audit fields, so that the record is complete and traceable to its originating event.

#### Acceptance Criteria

1. THE Sent_Communication SHALL be persisted as a JPA entity with a `GenerationType.UUID` primary key id.
2. THE Sent_Communication SHALL persist the facility id as a non-null UUID.
3. THE Sent_Communication SHALL persist the date-sent value as a non-null timestamp stored in UTC with at least millisecond precision.
4. THE Sent_Communication SHALL persist the message content as a non-null value of 1 to 10,000 characters.
5. THE Sent_Communication SHALL persist the Message_Type as a non-null `EnumType.STRING` value.
6. THE Sent_Communication SHALL persist the recipient phone number as a nullable value of at most 32 characters.
7. THE Sent_Communication SHALL persist the recipient email address as a nullable value of at most 254 characters.
8. WHEN a Sent_Communication is persisted, THE Sent_Communication SHALL store exactly one of the recipient phone number or the recipient email address as non-null and the other as null.
9. THE Sent_Communication SHALL persist the Send_Status as a non-null `EnumType.STRING` value defaulting to `DISPATCHED`.
10. THE Sent_Communication SHALL persist the tenant id as a nullable UUID.
11. THE Sent_Communication SHALL persist the user id as a nullable UUID.
12. THE Sent_Communication SHALL persist a non-null, unique, system-generated Public_Id of at most 64 characters.
13. THE Sent_Communication SHALL persist the organization id as a non-null UUID.
14. THE Sent_Communication SHALL persist the Recipient_Type as a nullable `EnumType.STRING` value.
15. THE Sent_Communication SHALL persist an email subject as a nullable value of at most 255 characters.
16. THE Sent_Communication SHALL persist the Correlation_Id as a nullable UUID tracing to the originating event or workflow.
17. THE Sent_Communication SHALL persist the Delivery_Provider identifier as a nullable value of at most 64 characters.
18. THE Sent_Communication SHALL persist a provider message id as a nullable value of at most 255 characters.
19. THE Sent_Communication SHALL persist a failure reason as a nullable value of at most 1,000 characters.
20. WHEN a Sent_Communication is first persisted, THE Communications_Service SHALL set the created-at and updated-at timestamps, in UTC with at least millisecond precision, via the `@PrePersist` callback, independently of the date-sent value.
21. WHEN a Sent_Communication is modified, THE Communications_Service SHALL refresh the updated-at timestamp, in UTC with at least millisecond precision, via the `@PreUpdate` callback while leaving the created-at timestamp unchanged.

### Requirement 6: Flyway migration continuing the linear history

**User Story:** As a developer, I want the sent-communications table created by a Flyway migration that continues the service's existing history, so that schema state is reproducible across environments.

#### Acceptance Criteria

1. THE Communications_Service SHALL define the sent-communications table in a new Flyway migration whose version number immediately follows the latest existing migration (`V4__add_entity_type_to_communication_templates.sql`), that is `V5`.
2. THE Flyway migration SHALL declare the sent-communications table with a column for every persisted Sent_Communication field defined in Requirement 5, declaring each column NOT NULL or nullable to match that field's non-null or nullable designation in Requirement 5, and declaring the `id` field as the primary key.
3. WHEN the application starts with `hibernate ddl-auto=validate`, THE Sent_Communication entity mapping SHALL match the migration-created table so validation passes, and IF the entity mapping does not match the migration-created table, THEN the application SHALL fail to start.
4. THE Flyway migration SHALL define an index over the facility id and date-sent columns supporting the newest-first, facility-filtered list query of Requirement 4.

### Requirement 7: Role-based access control

**User Story:** As a security stakeholder, I want the send and read endpoints gated by role, so that only authorized callers can dispatch or inspect communications.

#### Acceptance Criteria

1. IF a request to any Communications_Controller endpoint carries no verified authentication token, THEN THE Communications_Service SHALL respond with HTTP 401 and an Error_Response.
2. IF an authenticated caller lacks a role permitted for the requested Communications_Controller endpoint, THEN THE Communications_Service SHALL respond with HTTP 403 and an Error_Response.
3. THE Communications_Controller SHALL gate every endpoint with a `@PreAuthorize` expression backed by the Authz bean so the role check runs before any business logic.
4. THE Communications_Controller SHALL permit the GET-by-id endpoint and the list endpoint only when the caller holds the ADMIN role or the OWNER role.
5. WHERE an OWNER caller requests the list, THE Communications_Service SHALL restrict visible Sent_Communication entries to those belonging to the caller's own organization, consistent with the existing `AuthorizationService` organization-scoping convention.
6. WHERE an ADMIN caller requests the list, THE Communications_Service SHALL return Sent_Communication entries across all organizations and facilities consistent with the existing `AuthorizationService` convention.
7. IF an OWNER caller requests a Sent_Communication by id that exists but belongs to a different organization than the caller's, THEN THE Communications_Service SHALL respond with HTTP 403 and an Error_Response, consistent with the organization-scoping convention of the existing API methods in this component.
8. WHERE an OWNER caller submits a send request, THE Communications_Service SHALL confine the persisted Sent_Communication's organization id to the caller's organization, consistent with the existing `AuthorizationService` organization-scoping convention.

### Requirement 8: Standard error responses

**User Story:** As an API consumer, I want errors returned in a consistent shape, so that clients can handle failures uniformly.

#### Acceptance Criteria

1. IF a request to a Communications_Controller endpoint fails validation, authorization, or lookup, THEN THE Communications_Service SHALL respond with an Error_Response body in the standard `ErrorResponse` format produced by `GlobalExceptionHandler`.
2. WHEN the Communications_Service produces an Error_Response, THE Error_Response SHALL populate all four `ErrorResponse` fields (`timestamp`, `status`, `error`, `message`) as non-null, and its `status` field SHALL equal the HTTP status code of the response.
3. IF a request references a Sent_Communication that does not exist, THEN THE Communications_Service SHALL respond with HTTP 404 and an Error_Response, consistent with the existing `GlobalExceptionHandler` mappings.
4. IF a request fails input validation, THEN THE Communications_Service SHALL respond with HTTP 400 and an Error_Response, consistent with the existing `GlobalExceptionHandler` mappings.
5. IF a request carries no verified token, THEN THE Communications_Service SHALL respond with HTTP 401 and an Error_Response, consistent with the existing `GlobalExceptionHandler` mappings.
6. IF a request is from a caller with insufficient role, THEN THE Communications_Service SHALL respond with HTTP 403 and an Error_Response, consistent with the existing `GlobalExceptionHandler` mappings.

## Open Decisions

1. **Send endpoint shape.** The send operation is modeled as `POST` to the collection path `/api/v1/sent-communications` (send-and-log in one call) returning 201 with the persisted entry. This spec exposes the POST for direct invocation/testing while leaving an internal-invocation path (for example, invocation by an inbox processor) open.
