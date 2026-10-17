package com.storagemanager.communications.model;

import java.time.Instant;
import java.util.UUID;

public record CommunicationTemplateResponse(
        UUID id,
        UUID organizationId,
        UUID facilityId,
        String templateName,
        String emailContent,
        String smsContent,
        EntityType entityType,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommunicationTemplateResponse from(CommunicationTemplate template) {
        return new CommunicationTemplateResponse(
                template.getId(),
                template.getOrganizationId(),
                template.getFacilityId(),
                template.getTemplateName(),
                template.getEmailContent(),
                template.getSmsContent(),
                template.getEntityType(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
