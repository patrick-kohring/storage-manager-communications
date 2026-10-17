package com.storagemanager.communications.action.lead;

import java.util.Map;
import java.util.UUID;

import com.storagemanager.communications.model.MessageType;

/**
 * Read-seam projection carrying everything the {@code ActionProcessor} needs about a lead to
 * resolve its template scope, recipient, channel, and personalized content (Requirement 8.1).
 *
 * <p>Obtained via {@link LeadReadPort#findLead(UUID)}. The {@code properties} map is keyed by the
 * {@code Entity_Property_Mapping.jsonProperty} value (owned by the {@code entity-api} spec), which
 * is exactly how personalization looks property values up (Requirement 6.2).
 *
 * @param organizationId the lead's organization id; used for template scope resolution and the
 *                       send's organization (Requirement 3.1). Never {@code null}.
 * @param facilityId     the lead's facility id; used for facility-scoped template resolution and
 *                       the send's facility (Requirement 3.2). May be {@code null} for a lead with
 *                       no facility.
 * @param email          the lead's captured email contact; used for the {@code EMAIL} channel
 *                       (Requirements 4.3, 5.1). May be {@code null} when the lead has no email.
 * @param phone          the lead's captured phone contact; used for the {@code SMS} channel
 *                       (Requirements 4.4, 5.1). May be {@code null} when the lead has no phone.
 * @param preferredMethod the lead's preferred contact method, selecting the {@code LEAD}-path
 *                       channel (Requirement 4.1). Never {@code null}.
 * @param properties     the lead property values keyed by {@code jsonProperty}, used for
 *                       personalization (Requirements 6.2, 6.5). Never {@code null} but may be
 *                       empty; individual values may be {@code null}.
 */
public record LeadView(
        UUID organizationId,
        UUID facilityId,
        String email,
        String phone,
        MessageType preferredMethod,
        Map<String, Object> properties
) {}
