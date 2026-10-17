package com.storagemanager.communications.model;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCommunicationTemplateRequest(
        @Pattern(regexp = "\\S.*", message = "must not be blank") String templateName,
        String emailContent,
        @Size(max = 500) String smsContent,
        EntityType entityType
) {}
