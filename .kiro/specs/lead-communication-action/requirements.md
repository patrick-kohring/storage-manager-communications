# Requirements Document

## Introduction

This feature adds the **lead communication action** to the `storage-manager-communications` service: the behavior that interprets a `SendCommunication` action produced by `storage-manager-events` in reaction to a `LeadCreated` event, resolves its recipient and channel, personalizes a template, and sends the resulting communication to the end user.

This spec is **step 6** of the overall lead event-driven flow. Steps 1-4 (the POST Lead API, the `LeadCreated` event, the customizable workflow, and the events outbox) already exist and are out of scope. Step 5 (the communications inbox that admits the action) is deferred and will be shaped by the action contract defined here. This spec owns two things:

1. **The action contract** — the concrete shape and semantics of the `SendCommunication` action payload that `storage-manager-events` produces and `storage-manager-communications` consumes. The action identifies that the subject entity is a lead, carries the lead id, names a template, optionally selects a recipient type, and (for the explicit path) carries a single literal address.
2. **The send behavior** — the Action_Processor that validates the action, resolves the template (scoped to the lead's organization or facility), resolves the single recipient and its channel, personalizes the template using the Entity_Property_Mapping set owned by the `entity-api` spec, and dispatches the resulting communication.

**Single-recipient model.** Each Send_Communication_Action targets exactly one end user and produces exactly one send. An action either selects the `LEAD` recipient type, or it supplies exactly one explicit alternative contact (one email or one SMS number) when no recipient type is selected. When a message must reach several individuals (for example every owner of the organization), the Events_Service emits one Send_Communication_Action per individual using the explicit email/phone path; fanning out to many recipients from a single action is not part of this spec.

This spec builds on and reuses existing capabilities rather than redefining them:

- The canonical **EventEnvelope** wire shape and the generic ingest/inbox mechanics are owned by the `messaging` spec. This spec defines the `SendCommunication` **payload carried inside** that envelope's `payload` field; it does not redefine the envelope or the inbox dedupe mechanics.
- The **Communication_Template** store (org/facility-scoped templates, `emailContent`/`smsContent`, `entityType`, `{{placeholder}}` syntax) is owned by the `communication-template-api` spec. This spec reads templates by name within the lead's scope; it does not create or modify templates.
- The **Entity_Property_Mapping** set (the `(entityType, placeholder)` → `jsonProperty` translation used for personalization) is owned by the `entity-api` spec. This spec applies those mappings to substitute placeholders; it does not create or modify mappings.
- The **send-and-log** seam (the Delivery_Provider stub, the Sent_Communication audit entry, the `EMAIL`/`SMS` channels, and the Recipient_Type enum) is owned by the `communications-api` spec. This spec invokes that send seam once per resolved recipient; it does not redefine the audit entity or the provider.

The **actual third-party delivery remains stubbed/no-op**, consistent with the `communications-api` spec. The focus here is action interpretation, recipient and channel resolution, template personalization, and the orchestration of one send per resolved recipient.

Because `storage-manager-communications` does not own the Lead, Organization, Facility, or user-identity tables, lead data (the lead's preferred contact method and the contact values captured at registration) is obtained from `storage-manager-api` through a read seam. In this spec that seam is defined as an interface with a single stub/fake implementation for testing; the live service-to-service wiring is recorded as a cross-repository assumption.

## Glossary

- **Communications_Service**: The `storage-manager-communications` Spring Boot application that consumes the lead communication action and performs the send behavior defined in this spec.
- **Events_Service**: The `storage-manager-events` service that reacts to a `LeadCreated` event and produces the Send_Communication_Action. Owned by a separate repository; referenced here only as the action producer.
- **EventEnvelope**: The canonical 13-field wire message owned by the `messaging` spec (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `organizationId`, `facilityId`, `entityType`, `entityId`, `actorType`, `actorId`, `correlationId`, `causationId`, `payload`). This spec defines the content of the `payload` field for the `SendCommunication` `eventType`.
- **Send_Communication_Action**: The command payload, carried in the EventEnvelope `payload` field, instructing the Communications_Service to send one communication derived from a lead. This spec defines its fields and semantics.
- **Action_Processor**: The component that interprets a Send_Communication_Action, resolves its template, recipient, and channel, personalizes the content, and invokes the send seam once per resolved recipient.
- **Entity_Kind**: The value on the Send_Communication_Action identifying the subject-entity kind. In this spec the only supported value is `LEAD`, drawn from the existing Entity_Type enumeration owned by the `entity-api` spec.
- **Lead_Id**: The identifier of the lead the Send_Communication_Action refers to, carried on the action as a UUID.
- **Template_Name**: The name of the Communication_Template the Send_Communication_Action selects, matched within the lead's organization-or-facility scope.
- **Communication_Template**: The reusable email/SMS template entity owned by the `communication-template-api` spec, scoped to an organization and optionally a facility, carrying `emailContent`, `smsContent`, a nullable `entityType`, and `{{placeholder}}` content.
- **Template_Scope**: The resolution scope for a Template_Name: a Facility_Level_Template whose `(organizationId, facilityId)` matches the lead's organization and facility, or an Org_Level_Template whose `(organizationId, null)` matches the lead's organization.
- **Recipient_Type**: The value on the Send_Communication_Action identifying who receives the communication. The Recipient_Type enumeration owned by the `communications-api` spec defines the values `LEAD` and `OWNER`; this spec uses only `LEAD` (plus the explicit path) and does not redefine the enum. On a Send_Communication_Action the Recipient_Type is either `LEAD` or absent, and the absence of a Recipient_Type activates the Explicit_Recipient path.
- **Explicit_Recipient**: The single literal contact (exactly one email address or exactly one phone number) carried on the Send_Communication_Action, used only when the Recipient_Type is absent.
- **Resolved_Recipient**: The single concrete send target produced by recipient resolution for an action, consisting of exactly one channel (`EMAIL` or `SMS`) and exactly one matching contact value (an email address for `EMAIL`, a phone number for `SMS`).
- **Message_Type**: The `EMAIL` or `SMS` delivery channel enumeration owned by the `communications-api` spec.
- **Preferred_Contact_Method**: The channel (`EMAIL` or `SMS`) a lead indicated as their preferred contact method when registering, obtained via the Lead_Read_Port.
- **Lead_Contact**: The email address and phone number captured for a lead when the lead registered, obtained via the Lead_Read_Port.
- **Lead_Read_Port**: The interface the Communications_Service depends on to read lead data (Lead_Contact, Preferred_Contact_Method, lead organization and facility, and lead property values) from `storage-manager-api`. In this spec the Lead_Read_Port has a stub/fake implementation for testing; the live implementation is a cross-repository assumption.
- **Entity_Property_Mapping**: The `(entityType, placeholder)` → `jsonProperty` translation row owned by the `entity-api` spec, used to resolve `{{placeholder}}` names to lead property values during personalization.
- **Personalization**: The process of replacing each `{{placeholder}}` occurrence in template content with the lead property value selected by the matching Entity_Property_Mapping.
- **Send_Seam**: The send-and-log capability owned by the `communications-api` spec that dispatches one communication over the Delivery_Provider stub and persists one Sent_Communication audit entry. This spec invokes the Send_Seam once per Resolved_Recipient.
- **Sent_Communication**: The immutable audit entry persisted by the Send_Seam, owned by the `communications-api` spec.
- **Correlation_Id**: The identifier tracing a dispatched communication back to the originating event, taken from the EventEnvelope's `correlationId` (or `eventId` when `correlationId` is null) and passed to the Send_Seam.

## Requirements

### Requirement 1: Send_Communication_Action contract

**User Story:** As the events platform, I want a well-defined action payload for sending a lead communication, so that the Communications_Service can interpret exactly what to send and to whom.

#### Acceptance Criteria

1. THE Send_Communication_Action SHALL carry an Entity_Kind field identifying the subject-entity kind.
2. WHERE the Send_Communication_Action carries an Entity_Kind value that is not `LEAD`, THE Action_Processor SHALL reject the action with a processing error identifying the Entity_Kind as unsupported.
3. THE Send_Communication_Action SHALL carry a Lead_Id field of type UUID identifying the lead the action refers to.
4. THE Send_Communication_Action SHALL carry a Template_Name field of type String naming the Communication_Template to apply.
5. THE Send_Communication_Action SHALL carry a Recipient_Type field whose value is `LEAD` or absent, where an absent value activates the Explicit_Recipient path of Requirement 5.
6. THE Send_Communication_Action SHALL carry an optional Explicit_Recipient value holding exactly one literal email address or exactly one literal phone number, used only when the Recipient_Type is absent.
7. IF a Send_Communication_Action omits a required field (Entity_Kind, Lead_Id, or Template_Name), THEN THE Action_Processor SHALL reject the action with a processing error naming the missing field and SHALL NOT invoke the Send_Seam.
8. IF a Send_Communication_Action supplies a Lead_Id that is not a syntactically valid UUID, THEN THE Action_Processor SHALL reject the action with a processing error naming the Lead_Id field and SHALL NOT invoke the Send_Seam.
9. THE Send_Communication_Action SHALL target exactly one Resolved_Recipient, and IF the action both selects a Recipient_Type of `LEAD` and supplies an Explicit_Recipient, THEN THE Action_Processor SHALL reject the action with a processing error indicating a single recipient selection is required and SHALL NOT invoke the Send_Seam.

### Requirement 2: Action envelope binding

**User Story:** As a developer, I want the Send_Communication_Action to travel inside the canonical EventEnvelope, so that the existing inbox mechanics admit it without a new transport.

#### Acceptance Criteria

1. THE Communications_Service SHALL interpret a Send_Communication_Action only when the action is carried in the `payload` field of an EventEnvelope whose `eventType` equals `SendCommunication`.
2. WHEN the Action_Processor derives the Correlation_Id for a Send_Communication_Action, THE Action_Processor SHALL use the EventEnvelope `correlationId` when it is non-null and SHALL use the EventEnvelope `eventId` when the `correlationId` is null.
3. IF an EventEnvelope whose `eventType` equals `SendCommunication` carries a `payload` that cannot be parsed into a Send_Communication_Action, THEN THE Action_Processor SHALL reject the action with a processing error indicating the payload is malformed and SHALL NOT invoke the Send_Seam.
4. WHEN the Action_Processor resolves the Correlation_Id, THE Action_Processor SHALL pass that Correlation_Id to the Send_Seam invocation produced from the action so that the Sent_Communication traces to the originating event.

### Requirement 3: Template resolution within the lead's scope

**User Story:** As an operator, I want the named template resolved against the lead's organization or facility, so that the right content is used for the lead.

#### Acceptance Criteria

1. WHEN the Action_Processor resolves a Template_Name for a lead, THE Action_Processor SHALL obtain the lead's organization id and facility id via the Lead_Read_Port.
2. WHEN a Communication_Template exists whose `(organizationId, facilityId)` equals the lead's organization and facility and whose `templateName` equals the Template_Name, THE Action_Processor SHALL select that Facility_Level_Template.
3. WHEN no Facility_Level_Template matches and a Communication_Template exists whose `organizationId` equals the lead's organization, whose `facilityId` is null, and whose `templateName` equals the Template_Name, THE Action_Processor SHALL select that Org_Level_Template.
4. IF no Communication_Template matches the Template_Name within either the lead's facility scope or the lead's organization scope, THEN THE Action_Processor SHALL reject the action with a processing error indicating the template was not found and SHALL NOT invoke the Send_Seam.
5. WHERE both a Facility_Level_Template and an Org_Level_Template match the Template_Name for the lead, THE Action_Processor SHALL select the Facility_Level_Template in preference to the Org_Level_Template.

### Requirement 4: Channel selection per recipient

**User Story:** As a lead, I want messages sent to me on my preferred contact method, so that I receive communications the way I chose at registration.

#### Acceptance Criteria

1. WHILE the Recipient_Type is `LEAD`, THE Action_Processor SHALL set the Resolved_Recipient channel to the lead's Preferred_Contact_Method obtained via the Lead_Read_Port.
2. WHILE the Recipient_Type is absent, THE Action_Processor SHALL set the Resolved_Recipient channel to `EMAIL` when the Explicit_Recipient is an email address and to `SMS` when the Explicit_Recipient is a phone number.
3. WHEN the Resolved_Recipient channel is `EMAIL`, THE Action_Processor SHALL use the recipient's email address as the single matching contact value and SHALL apply the Communication_Template's `emailContent`.
4. WHEN the Resolved_Recipient channel is `SMS`, THE Action_Processor SHALL use the recipient's phone number as the single matching contact value and SHALL apply the Communication_Template's `smsContent`.
5. IF the Resolved_Recipient channel is `EMAIL` and the selected Communication_Template has no `emailContent`, THEN THE Action_Processor SHALL reject the action with a processing error indicating the template lacks content for the channel and SHALL NOT invoke the Send_Seam.
6. IF the Resolved_Recipient channel is `SMS` and the selected Communication_Template has no `smsContent`, THEN THE Action_Processor SHALL reject the action with a processing error indicating the template lacks content for the channel and SHALL NOT invoke the Send_Seam.
7. IF the Recipient_Type is `LEAD` and the lead has no contact value for the Preferred_Contact_Method channel, THEN THE Action_Processor SHALL reject the action with a processing error indicating the lead lacks a contact for the preferred channel and SHALL NOT invoke the Send_Seam.

### Requirement 5: Recipient resolution by recipient type

**User Story:** As an operator, I want each action to control whether the lead or one explicit address receives the communication, so that each action reaches exactly one intended end user.

#### Acceptance Criteria

1. WHEN the Recipient_Type is `LEAD`, THE Action_Processor SHALL resolve exactly one Resolved_Recipient using the Lead_Contact captured for the lead at registration and the lead's Preferred_Contact_Method, both obtained via the Lead_Read_Port.
2. WHEN the Recipient_Type is absent, THE Action_Processor SHALL resolve exactly one Resolved_Recipient from the Explicit_Recipient, using the `EMAIL` channel when the Explicit_Recipient is an email address and the `SMS` channel when the Explicit_Recipient is a phone number.
3. IF the Recipient_Type is absent and the action supplies no Explicit_Recipient, THEN THE Action_Processor SHALL reject the action with a processing error indicating that an Explicit_Recipient is required and SHALL NOT invoke the Send_Seam.
4. WHEN the Action_Processor records the Resolved_Recipient for a `LEAD` send, THE Action_Processor SHALL pass the `LEAD` Recipient_Type to the Send_Seam so the Sent_Communication records that the lead was the recipient.
5. IF the Explicit_Recipient value is neither a syntactically valid email address nor a syntactically valid phone number, THEN THE Action_Processor SHALL reject the action with a processing error naming the Explicit_Recipient value and SHALL NOT invoke the Send_Seam.

### Requirement 6: Template personalization using entity-api mappings

**User Story:** As a lead, I want the message personalized with my information, so that the communication is relevant to me.

#### Acceptance Criteria

1. WHEN the Action_Processor personalizes a Communication_Template for a lead, THE Action_Processor SHALL replace each `{{placeholder}}` occurrence in the applied content with the lead property value selected by the Entity_Property_Mapping whose `entityType` is `LEAD` and whose `placeholder` equals the inner name.
2. WHEN the Action_Processor selects a lead property value for a placeholder, THE Action_Processor SHALL read the lead property named by the matching Entity_Property_Mapping's `jsonProperty` from the lead data obtained via the Lead_Read_Port.
3. IF the applied content contains a `{{placeholder}}` whose inner name has no Entity_Property_Mapping for entity type `LEAD`, THEN THE Action_Processor SHALL reject the action with a processing error naming the unresolved placeholder and SHALL NOT invoke the Send_Seam.
4. WHEN the applied content contains no `{{placeholder}}` occurrences, THE Action_Processor SHALL use the content unchanged as the personalized message content.
5. WHEN a placeholder's matching Entity_Property_Mapping resolves to a lead property that is absent or null in the lead data, THE Action_Processor SHALL replace that `{{placeholder}}` occurrence with an empty string.
6. WHEN the Action_Processor personalizes content containing the same `{{placeholder}}` inner name more than once, THE Action_Processor SHALL replace every occurrence of that placeholder with the same resolved lead property value.

### Requirement 7: Dispatch via the send seam

**User Story:** As a compliance stakeholder, I want each resolved recipient to produce one audited send, so that every dispatched communication is recorded.

#### Acceptance Criteria

1. WHEN the Action_Processor has resolved the template, the single Resolved_Recipient, its channel, and the personalized content for a Send_Communication_Action, THE Action_Processor SHALL invoke the Send_Seam exactly once.
2. WHEN the Action_Processor invokes the Send_Seam for the Resolved_Recipient, THE Action_Processor SHALL supply the Resolved_Recipient channel as the Message_Type, the single matching contact value, the personalized message content, the lead's facility id, the lead's organization id, the Lead_Id as the tenant reference, and the Correlation_Id.
3. WHEN the Send_Seam dispatches a communication produced by a Send_Communication_Action, THE Send_Seam SHALL perform no external third-party transmission and SHALL report a successful dispatch outcome, consistent with the stubbed Delivery_Provider owned by the `communications-api` spec.
4. WHEN the Resolved_Recipient for a Send_Communication_Action has been dispatched, THE Action_Processor SHALL report the action as successfully processed so the inbox marks the originating message processed exactly once.
5. IF the Send_Seam raises an error while dispatching the Resolved_Recipient, THEN THE Action_Processor SHALL surface a processing error for the action so the originating message remains eligible for redelivery.

### Requirement 8: Lead read seam

**User Story:** As a developer, I want lead data obtained through a defined read seam, so that the Communications_Service can resolve recipients without owning the lead tables.

#### Acceptance Criteria

1. THE Communications_Service SHALL define a Lead_Read_Port interface exposing, for a Lead_Id, the lead's organization id, the lead's facility id, the Lead_Contact, the Preferred_Contact_Method, and the lead property values used for Personalization.
2. THE Communications_Service SHALL provide a stub Lead_Read_Port implementation for testing that returns configured lead data without calling `storage-manager-api`.
3. IF the Lead_Read_Port reports that no lead exists for the Lead_Id, THEN THE Action_Processor SHALL reject the action with a processing error indicating the lead was not found and SHALL NOT invoke the Send_Seam.
4. IF the Lead_Read_Port raises an error while reading lead data, THEN THE Action_Processor SHALL surface a processing error for the action so the originating message remains eligible for redelivery.

### Requirement 9: Per-action processing outcome

**User Story:** As an operator, I want each action to either succeed with its single send or be retried, so that an action never leaves an ambiguous partial outcome.

#### Acceptance Criteria

1. WHEN the Action_Processor rejects a Send_Communication_Action for a validation error defined in Requirements 1, 2, 3, 4, 5, 6, or 8, THE Action_Processor SHALL invoke the Send_Seam zero times for that action.
2. IF the Action_Processor surfaces a retryable processing error defined in Requirement 7.5 or Requirement 8.4, THEN THE Action_Processor SHALL surface the error so the originating message remains eligible for redelivery, acknowledging that because each action produces exactly one Send_Seam invocation, a retry re-attempts only that single send.
3. THE Send_Communication_Action SHALL select exactly one of a `LEAD` recipient or an Explicit_Recipient, so that a single action never addresses both a lead and an explicit contact, and reaching several individuals for one lead SHALL be expressed as distinct Send_Communication_Actions processed independently.

## External Assumptions (Out of Scope for storage-manager-communications)

The following are relied upon as cross-repository context but are NOT owned or changed by this spec.

- **A1 — Events-side LeadCreated workflow and action production:** The workflow in `storage-manager-events` that reacts to `LeadCreated` and emits the `SendCommunication` action (including which template name and recipient each configured action carries) is owned by the events service. When a communication must reach every owner of an organization, the Events_Service emits one `SendCommunication` action per owner using the explicit email/phone path. This spec consumes the action shape and does not own the workflow that produces it.
- **A2 — Communications inbox admission (step 5):** The inbox path that admits a `SendCommunication` EventEnvelope and invokes the Action_Processor through `processOnce` builds on the generic inbox mechanics owned by the `messaging` spec. The concrete wiring of the Action_Processor as the inbox handler for `eventType = SendCommunication` is expected to be defined when step 5 is specified; this spec defines the Action_Processor contract that step 5 will invoke.
- **A3 — Live Lead_Read_Port implementation:** The live service-to-service read from `storage-manager-communications` to `storage-manager-api` for lead contact, preferred contact method, and lead property values is owned by platform/producer configuration and a future spec. This spec defines the Lead_Read_Port interface and a stub implementation only.
- **A4 — Lead field vocabulary and preferred-contact capture:** The Lead entity fields (including the contact values captured at registration and the stored preferred contact method) and their JSON property names referenced by Entity_Property_Mapping `jsonProperty` values are owned by the `lead-api` spec in `storage-manager-api`. This spec reads those values through the Lead_Read_Port and does not define the Lead schema.
- **A5 — Real delivery provider:** Actual third-party email/SMS transmission remains stubbed, consistent with the `communications-api` spec. Replacing the stub Delivery_Provider with a real vendor adapter is out of scope.

## Open Decisions / Recommended Defaults (user may override)

- **D1 — Action eventType value:** Recommended default `SendCommunication` for the EventEnvelope `eventType` that carries a Send_Communication_Action, matching the name used in the `messaging` spec's deferred-scope notes. Confirm whether a different event type name is preferred. Reflected in Requirement 2.1.
- **D2 — Recipient-type representation:** Recommended default is an optional Recipient_Type field whose absence activates the single Explicit_Recipient path (Requirements 1.5, 5.2). Confirm whether an explicit enum value (for example `EXPLICIT`) is preferred over an absent value.
- **D3 — Null placeholder value handling:** Recommended default is to substitute an empty string for a placeholder whose resolved lead property is absent or null (Requirement 6.5). Confirm whether an absent value should instead reject the send.
- **D4 — Tenant reference on the send:** Recommended default maps the Lead_Id onto the Sent_Communication tenant reference (Requirement 7.2), since the `communications-api` Sent_Communication carries a nullable `tenantId`. Confirm whether the lead should map onto a different audit field.
