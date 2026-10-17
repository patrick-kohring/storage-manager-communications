package com.storagemanager.communications.action.lead;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal, tolerant read DTO for deserializing {@code storage-manager-api}'s
 * {@code GET /api/v1/leads/{id}} response into the fields the live {@code HttpLeadReadPort} needs
 * to build a {@link LeadView}.
 *
 * <p>The api's {@code LeadDetailResponse} is a plain Java record with no {@code @JsonProperty}
 * annotations, so its JSON property names equal its record component names. The component names
 * here therefore match the api's JSON names exactly.
 *
 * <p>Annotated {@link JsonIgnoreProperties}({@code ignoreUnknown = true}) so the api fields this
 * adapter does not consume (timestamps, isActive/isConverted, notes, etc.) are silently ignored,
 * keeping the adapter decoupled from the api's full response type (Design D2).
 *
 * <p><strong>organizationId.</strong> The api exposes the lead's organization id on the nested
 * {@code facility} object ({@code facility.organizationId}), not at the top level, so it is read
 * from {@link FacilitySummaryDto} here.
 *
 * <p><strong>properties.</strong> The {@code LeadView.properties} personalization map is NOT a
 * field on the api response; it is assembled by {@code HttpLeadReadPort} from the lead's own
 * returned fields, keyed by their JSON property names (the same keys an admin configures as an
 * {@code EntityPropertyMapping.jsonProperty}). This DTO therefore carries the individual
 * personalizable lead fields rather than a pre-built map.
 *
 * @param facilityId             the lead's facility id; maps to {@link LeadView#facilityId()} (may be null).
 * @param firstName              the lead's first name (personalization field).
 * @param lastName               the lead's last name (personalization field).
 * @param email                  the lead's email contact; maps to {@link LeadView#email()} and a
 *                               personalization field.
 * @param homePhone              the lead's home phone (personalization field; may be null).
 * @param mobilePhone            the lead's mobile phone; maps to {@link LeadView#phone()} (the SMS
 *                               contact) and a personalization field.
 * @param city                   the lead's city (personalization field).
 * @param state                  the lead's state (personalization field).
 * @param postalCode             the lead's postal code (personalization field).
 * @param dateOfBirth            the lead's date of birth as an ISO string (personalization field; may be null).
 * @param source                 the lead source (personalization field).
 * @param preferredContactMethod the lead's preferred contact method; translated to the
 *                               communications {@code MessageType} by name, and also a
 *                               personalization field.
 * @param facility               the nested facility summary carrying the lead's organization id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeadReadResponse(
        UUID facilityId,
        String firstName,
        String lastName,
        String email,
        String homePhone,
        String mobilePhone,
        String city,
        String state,
        String postalCode,
        String dateOfBirth,
        String source,
        ContactMethodDto preferredContactMethod,
        FacilitySummaryDto facility
) {

    /**
     * The adapter's own local contact-method enum, decoupled from the api's {@code ContactMethod}
     * type. Its constant names ({@code EMAIL}, {@code SMS}) match the communications
     * {@code com.storagemanager.communications.model.MessageType} constants so the adapter can
     * translate between them by name.
     */
    public enum ContactMethodDto {
        EMAIL,
        SMS
    }

    /**
     * The nested {@code facility} object on the api lead response. Only {@code organizationId} is
     * consumed (to populate {@link LeadView#organizationId()}); other facility fields are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FacilitySummaryDto(
            UUID id,
            UUID organizationId,
            String name
    ) {}
}
