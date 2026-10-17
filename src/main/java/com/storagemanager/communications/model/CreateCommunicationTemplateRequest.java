package com.storagemanager.communications.model;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommunicationTemplateRequest(
        UUID organizationId,
        UUID facilityId,
        @NotBlank String templateName,
        String emailContent,
        @Size(max = 500) String smsContent,
        EntityType entityType
) {}
